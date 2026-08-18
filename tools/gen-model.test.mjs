/**
 * Тесты генератора v2: node --test tools/
 * Временная директория с реестром и одним v2-доком -> проверка .gen.c4.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, statSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { generate } from './gen-model.mjs'

const doc = {
  container: 'petclinic.customers',
  source: {
    repo: 'https://github.com/acme/petclinic',
    commit: 'abc1234',
    extractedAt: '2026-08-17',
    extractor: 'arch-analyzer source+config v1',
  },
  containerInfo: {
    kind: 'service',
    title: 'customers-service',
    technology: 'Java, Spring Boot',
    appName: 'customers-service',
  },
  api: {
    id: 'api',
    title: 'customers-service API',
    technology: 'HTTP/JSON',
    basePath: '/owners',
    public: true,
  },
  operations: [
    {
      method: 'GET',
      path: '/owners/{ownerId}',
      params: 'ownerId:path:int',
      response: 'OwnerDto',
      source: 'src/main/java/demo/OwnerController.java#L11',
      confidence: 0.95,
    },
  ],
  publishes: [
    { channel: 'payment.succeeded', schema: 'PaymentSucceeded', source: 'src/E.java#L5', confidence: 0.85 },
  ],
  subscribes: [
    { channel: 'order.created', group: 'cg', source: 'src/E.java#L9', confidence: 0.9 },
  ],
  calls: [
    { method: 'GET', path: '/pets/visits', target: { host: 'visits-service' }, source: 'src/V.java#L3', confidence: 0.8 },
  ],
  stores: [
    {
      kind: 'jdbc', address: 'jdbc:hsqldb:mem:petclinic', technology: 'HSQLDB',
      access: 'readwrite', entities: 'Owner', source: 'src/R.java#L1', confidence: 0.95,
    },
  ],
}

function makeRoot() {
  const root = mkdtempSync(join(tmpdir(), 'gen-model-'))
  mkdirSync(join(root, 'registry'), { recursive: true })
  mkdirSync(join(root, 'tools/api-source'), { recursive: true })
  writeFileSync(
    join(root, 'registry/systems.yml'),
    'systems:\n  - id: petclinic\n    kind: system\n    title: PetClinic\n    description: Демо\n',
  )
  writeFileSync(join(root, 'registry/repos.yml'), 'repos: {}\n')
  writeFileSync(join(root, 'tools/api-source/petclinic.customers.json'), JSON.stringify(doc, null, 2))
  return root
}

test('каркас системы генерируется из systems.yml', () => {
  const root = makeRoot()
  generate(root)
  const text = readFileSync(join(root, 'model/gen/systems/petclinic.gen.c4'), 'utf8')
  assert.equal(
    text,
    `// СГЕНЕРИРОВАНО, РУКАМИ НЕ ПРАВИТЬ.
// Источник: registry/systems.yml

model {
  petclinic = system 'PetClinic' {
    description 'Демо'
  }
}
`,
  )
})

test('контейнер: extend системы, api, операции, рёбра', () => {
  const root = makeRoot()
  generate(root)
  const text = readFileSync(join(root, 'model/gen/petclinic.customers.gen.c4'), 'utf8')

  assert.match(text, /extend petclinic \{/)
  assert.match(text, /customers = service 'customers-service' \{/)
  assert.match(text, /#inferred/)
  assert.match(text, /link https:\/\/github\.com\/acme\/petclinic 'repo'/)
  assert.match(text, /get_owners_p = operation 'GET \/owners\/\{ownerId\}' \{/)
  assert.match(text, /params 'ownerId:path:int'/)
  assert.match(text, /petclinic\.customers -\[read\]-> petclinic\.db_petclinic/)
  assert.match(text, /petclinic\.customers -\[write\]-> petclinic\.db_petclinic/)
  assert.match(text, /petclinic\.customers -\[publish\]-> petclinic\.ch_payment_succeeded 'PaymentSucceeded'/)
})

test('общие узлы: стор, каналы, message, deliver', () => {
  const root = makeRoot()
  generate(root)
  const text = readFileSync(join(root, 'model/gen/_shared.gen.c4'), 'utf8')

  assert.match(text, /db_petclinic = store 'petclinic' \{/)
  assert.match(text, /technology 'HSQLDB'/)
  assert.match(text, /address 'jdbc:hsqldb:mem:petclinic'/)
  assert.match(text, /ch_payment_succeeded = channel 'payment\.succeeded' \{/)
  assert.match(text, /paymentsucceeded = message 'PaymentSucceeded' \{/)
  assert.match(text, /ch_order_created = channel 'order\.created' \{/)
  assert.match(text, /petclinic\.ch_order_created -\[deliver\]-> petclinic\.customers 'group: cg'/)
})

test('повторный прогон не переписывает файлы', () => {
  const root = makeRoot()
  generate(root)
  const file = join(root, 'model/gen/petclinic.customers.gen.c4')
  const before = statSync(file).mtimeMs
  generate(root)
  assert.equal(statSync(file).mtimeMs, before)
})

test('пустой адрес БД — свой стор на контейнер, а не общий узел', () => {
  const root = makeRoot()
  const mk = (container) => ({
    ...doc,
    container,
    api: null,
    operations: [],
    publishes: [],
    subscribes: [],
    calls: [],
    stores: [{ kind: 'jdbc', address: '', access: 'readwrite', source: 's', confidence: 0.9 }],
  })
  writeFileSync(join(root, 'tools/api-source/petclinic.customers.json'), JSON.stringify(mk('petclinic.customers')))
  writeFileSync(join(root, 'tools/api-source/petclinic.vets.json'), JSON.stringify(mk('petclinic.vets')))
  generate(root)
  const shared = readFileSync(join(root, 'model/gen/_shared.gen.c4'), 'utf8')
  assert.match(shared, /db_customers = store 'customers'/)
  assert.match(shared, /db_vets = store 'vets'/)
})

test('легаси-док без containerInfo игнорируется', () => {
  const root = makeRoot()
  writeFileSync(
    join(root, 'tools/api-source/shop.legacy.json'),
    JSON.stringify({ container: 'shop.legacy', source: {}, api: null }),
  )
  generate(root)
  assert.equal(existsSync(join(root, 'model/gen/shop.legacy.gen.c4')), false)
})

// ---------- подпроект 3: разрешение вызовов ----------

const visitsDoc = {
  container: 'petclinic.visits',
  source: { repo: 'r', commit: 'c', extractedAt: '2026-08-17', extractor: 'x' },
  containerInfo: { kind: 'service', title: 'visits-service', technology: 'Java' },
  api: { id: 'api', title: 'visits API', technology: 'HTTP/JSON', basePath: '/', public: true },
  operations: [
    { method: 'GET', path: '/pets/visits', source: 's#L1', confidence: 0.95 },
  ],
}

function withCall(target, methodPath = { method: 'GET', path: '/pets/visits' }) {
  return {
    ...doc,
    publishes: [],
    subscribes: [],
    stores: [],
    calls: [{ ...methodPath, target, source: 'src/C.java#L9', confidence: 0.8 }],
  }
}

test('алиас host -> ребро в операцию цели', () => {
  const root = makeRoot()
  writeFileSync(join(root, 'tools/api-source/petclinic.visits.json'), JSON.stringify(visitsDoc))
  writeFileSync(join(root, 'tools/api-source/petclinic.customers.json'), JSON.stringify(withCall({ host: 'visits-service' })))
  writeFileSync(join(root, 'registry/aliases.yml'), 'aliases:\n  visits-service: petclinic.visits\n')
  generate(root)
  const text = readFileSync(join(root, 'model/gen/petclinic.customers.gen.c4'), 'utf8')
  assert.match(text, /petclinic\.customers -\[call\]-> petclinic\.visits\.api\.get_pets_visits 'GET \/pets\/visits'/)
})

test('алиас есть, операции нет -> ребро в api цели', () => {
  const root = makeRoot()
  writeFileSync(join(root, 'tools/api-source/petclinic.visits.json'), JSON.stringify(visitsDoc))
  writeFileSync(
    join(root, 'tools/api-source/petclinic.customers.json'),
    JSON.stringify(withCall({ host: 'visits-service' }, { method: 'POST', path: '/nope' })),
  )
  writeFileSync(join(root, 'registry/aliases.yml'), 'aliases:\n  visits-service: petclinic.visits\n')
  generate(root)
  const text = readFileSync(join(root, 'model/gen/petclinic.customers.gen.c4'), 'utf8')
  assert.match(text, /petclinic\.customers -\[call\]-> petclinic\.visits\.api 'POST \/nope'/)
})

test('нет алиаса и кандидатов -> stub в unknown и запись в unresolved', () => {
  const root = makeRoot()
  writeFileSync(
    join(root, 'tools/api-source/petclinic.customers.json'),
    JSON.stringify(withCall({ host: 'legacy-billing' }, { method: 'POST', path: '/api/v1/invoices' })),
  )
  generate(root)
  const stub = readFileSync(join(root, 'model/gen/unknown/legacy_billing.gen.c4'), 'utf8')
  assert.match(stub, /legacy_billing = service 'legacy-billing' \{/)
  assert.match(stub, /#stub #inferred/)
  assert.match(stub, /post_api_v1_invoices = operation 'POST \/api\/v1\/invoices'/)
  const caller = readFileSync(join(root, 'model/gen/petclinic.customers.gen.c4'), 'utf8')
  assert.match(caller, /petclinic\.customers -\[call\]-> unknown\.legacy_billing\.api\.post_api_v1_invoices/)
  const unresolved = JSON.parse(readFileSync(join(root, 'registry/unresolved.json'), 'utf8'))
  assert.equal(unresolved.unresolved[0].stubId, 'unknown.legacy_billing')
  assert.deepEqual(unresolved.unresolved[0].observedEndpoints, [{ method: 'POST', path: '/api/v1/invoices' }])
})

test('единственный кандидат со score 1.0 -> автосклейка вместо stub', () => {
  const root = makeRoot()
  writeFileSync(join(root, 'tools/api-source/petclinic.visits.json'), JSON.stringify(visitsDoc))
  writeFileSync(join(root, 'tools/api-source/petclinic.customers.json'), JSON.stringify(withCall({ host: 'visits-internal' })))
  generate(root)
  const text = readFileSync(join(root, 'model/gen/petclinic.customers.gen.c4'), 'utf8')
  assert.match(text, /petclinic\.customers -\[call\]-> petclinic\.visits\.api\.get_pets_visits/)
  assert.equal(existsSync(join(root, 'model/gen/unknown/visits_internal.gen.c4')), false)
})

test('assign: stub переезжает контейнером в чужую систему и дообогащается', () => {
  const root = makeRoot()
  writeFileSync(
    join(root, 'registry/systems.yml'),
    'systems:\n  - id: petclinic\n    kind: system\n    title: PetClinic\n  - id: auth\n    kind: orgSystem\n    title: Авторизация\n',
  )
  // Два разных вызывающих в разное время зовут один host — эндпоинты объединяются.
  writeFileSync(
    join(root, 'tools/api-source/petclinic.customers.json'),
    JSON.stringify(withCall({ host: 'sso.corp' }, { method: 'POST', path: '/oauth/token' })),
  )
  writeFileSync(
    join(root, 'tools/api-source/petclinic.visits.json'),
    JSON.stringify({
      ...visitsDoc,
      calls: [{ method: 'GET', path: '/userinfo', target: { host: 'sso.corp' }, source: 's#L2', confidence: 0.8 }],
    }),
  )
  writeFileSync(
    join(root, 'registry/resolutions.yml'),
    'resolutions:\n  unknown.sso_corp:\n    assign:\n      container: auth.sso\n',
  )
  generate(root)

  const observed = readFileSync(join(root, 'model/gen/observed/auth.sso.gen.c4'), 'utf8')
  assert.match(observed, /extend auth \{/)
  assert.match(observed, /sso = service 'sso' \{/)
  assert.match(observed, /#stub #inferred/)
  assert.match(observed, /hosts 'sso\.corp'/)
  assert.match(observed, /post_oauth_token = operation 'POST \/oauth\/token'/)
  assert.match(observed, /get_userinfo = operation 'GET \/userinfo'/, 'эндпоинты объединяются от всех вызывающих')

  const c1 = readFileSync(join(root, 'model/gen/petclinic.customers.gen.c4'), 'utf8')
  assert.match(c1, /petclinic\.customers -\[call\]-> auth\.sso\.api\.post_oauth_token/)
  const c2 = readFileSync(join(root, 'model/gen/petclinic.visits.gen.c4'), 'utf8')
  assert.match(c2, /petclinic\.visits -\[call\]-> auth\.sso\.api\.get_userinfo/)

  assert.equal(existsSync(join(root, 'model/gen/unknown/sso_corp.gen.c4')), false, 'stub исчез')
  const unresolved = JSON.parse(readFileSync(join(root, 'registry/unresolved.json'), 'utf8'))
  assert.equal(unresolved.unresolved.length, 0)
})

test('resolutions: container-склейка убирает stub, external рождает externalSystem', () => {
  const root = makeRoot()
  writeFileSync(join(root, 'tools/api-source/petclinic.visits.json'), JSON.stringify(visitsDoc))
  writeFileSync(
    join(root, 'tools/api-source/petclinic.customers.json'),
    JSON.stringify({
      ...withCall({ host: 'legacy-billing' }, { method: 'POST', path: '/x' }),
      calls: [
        { method: 'POST', path: '/x', target: { host: 'legacy-billing' }, source: 's#L1', confidence: 0.8 },
        { method: 'POST', path: '/charge', target: { host: 'api.stripe.com' }, source: 's#L2', confidence: 0.8 },
      ],
    }),
  )
  // Сначала без решений — оба stub'а на месте
  generate(root)
  assert.equal(existsSync(join(root, 'model/gen/unknown/legacy_billing.gen.c4')), true)
  // Теперь решения: склейка и external
  writeFileSync(
    join(root, 'registry/resolutions.yml'),
    'resolutions:\n' +
      '  unknown.legacy_billing:\n    container: petclinic.visits\n' +
      '  unknown.api_stripe_com:\n    external:\n      id: stripe\n      title: Stripe\n      contract: MSA-1\n',
  )
  generate(root)
  assert.equal(existsSync(join(root, 'model/gen/unknown/legacy_billing.gen.c4')), false, 'stub удалён после склейки')
  const caller = readFileSync(join(root, 'model/gen/petclinic.customers.gen.c4'), 'utf8')
  assert.match(caller, /petclinic\.customers -\[call\]-> petclinic\.visits\.api 'POST \/x'/)
  assert.match(caller, /petclinic\.customers -\[call\]-> stripe 'POST \/charge'/)
  const ext = readFileSync(join(root, 'model/gen/unknown/_externals.gen.c4'), 'utf8')
  assert.match(ext, /stripe = externalSystem 'Stripe' \{/)
  assert.match(ext, /contract 'MSA-1'/)
})
