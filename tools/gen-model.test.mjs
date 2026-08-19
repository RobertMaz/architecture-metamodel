/**
 * Тесты генератора: node --test tools/gen-model.test.mjs
 * Раскладка: система целиком — model/systems/<id>/<id>.c4 (модель + виды);
 * в model/gen/ остаются только догадки (unknown/observed/externals).
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, statSync, existsSync } from 'node:fs'
import { execSync } from 'node:child_process'
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

const systemFile = (root, id = 'petclinic') => join(root, `model/systems/${id}/${id}.c4`)

test('файл системы: декларация, контейнер, api, рёбра — вся кухня в одном месте', () => {
  const root = makeRoot()
  generate(root)
  const text = readFileSync(systemFile(root), 'utf8')

  assert.match(text, /petclinic = system 'PetClinic' \{/)
  assert.match(text, /description 'Демо'/)
  assert.match(text, /customers = service 'customers-service' \{/)
  assert.match(text, /#inferred/)
  assert.match(text, /commit 'abc1234'/)
  assert.match(text, /get_owners_p = operation 'GET \/owners\/\{ownerId\}' \{/)
  assert.match(text, /params 'ownerId:path:int'/)
  assert.match(text, /petclinic\.customers -\[read\]-> petclinic\.db_petclinic/)
  assert.match(text, /petclinic\.customers -\[write\]-> petclinic\.db_petclinic/)
  assert.match(text, /petclinic\.customers -\[publish\]-> petclinic\.ch_payment_succeeded 'PaymentSucceeded'/)
  // старой раскладки нет
  assert.equal(existsSync(join(root, 'model/gen/petclinic.customers.gen.c4')), false)
  assert.equal(existsSync(join(root, 'model/gen/_shared.gen.c4')), false)
})

test('сторы и каналы живут в файле системы-владельца, deliver на месте', () => {
  const root = makeRoot()
  generate(root)
  const text = readFileSync(systemFile(root), 'utf8')

  assert.match(text, /db_petclinic = store 'petclinic' \{/)
  assert.match(text, /technology 'HSQLDB'/)
  assert.match(text, /address 'jdbc:hsqldb:mem:petclinic'/)
  assert.match(text, /ch_payment_succeeded = channel 'payment\.succeeded' \{/)
  assert.match(text, /paymentsucceeded = message 'PaymentSucceeded' \{/)
  assert.match(text, /ch_order_created = channel 'order\.created' \{/)
  assert.match(text, /petclinic\.ch_order_created -\[deliver\]-> petclinic\.customers 'group: cg'/)
})

test('views: обзорный вид системы и api-вид контейнера с контрактом', () => {
  const root = makeRoot()
  generate(root)
  const text = readFileSync(systemFile(root), 'utf8')

  assert.match(text, /views 'PetClinic' \{/, 'папка видов по имени системы (dsl/views/organize)')
  assert.match(text, /view petclinic_containers of petclinic \{/)
  assert.match(text, /title 'Контейнеры'/)
  assert.match(text, /global predicate noContracts/)
  assert.match(text, /view petclinic_customers_api of petclinic\.customers \{/)
  assert.match(text, /title 'API \/ customers-service'/, 'api-виды в подпапке API')
  assert.match(text, /include petclinic\.customers\.\*\*/, 'три уровня через .**')
  assert.match(text, /include \* -> petclinic\.customers\.\*\*/)
})

test('повторный прогон не переписывает файлы', () => {
  const root = makeRoot()
  generate(root)
  const file = systemFile(root)
  const before = statSync(file).mtimeMs
  generate(root)
  assert.equal(statSync(file).mtimeMs, before)
})

test('незакоммиченные ручные правки бэкапятся перед перезаписью', () => {
  const root = makeRoot()
  const run = (cmd) => execSync(cmd, { cwd: root, stdio: 'ignore' })
  run('git init -q && git config user.email t@t && git config user.name t')
  generate(root)
  run('git add -A && git commit -qm base')

  // руками правим файл системы, НЕ коммитим, и приходит регенерация с изменением
  const file = systemFile(root)
  writeFileSync(file, readFileSync(file, 'utf8').replace("service 'customers-service'", "service 'Клиенты (правка руками)'"))
  writeFileSync(
    join(root, 'tools/api-source/petclinic.customers.json'),
    JSON.stringify({ ...doc, containerInfo: { ...doc.containerInfo, title: 'customers-v2' } }),
  )
  generate(root)

  const backup = join(root, 'workspace/_backup/petclinic.c4')
  assert.equal(existsSync(backup), true, 'ручная версия спасена')
  assert.match(readFileSync(backup, 'utf8'), /Клиенты \(правка руками\)/)
  assert.match(readFileSync(file, 'utf8'), /customers-v2/, 'файл перегенерирован')
})

test('система, исчезнувшая из systems.yml, прунится', () => {
  const root = makeRoot()
  generate(root)
  assert.equal(existsSync(systemFile(root)), true)
  writeFileSync(join(root, 'registry/systems.yml'), 'systems: []\n')
  writeFileSync(join(root, 'tools/api-source/petclinic.customers.json'), JSON.stringify({ ...doc, calls: [] }))
  generate(root)
  assert.equal(existsSync(systemFile(root)), false)
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
  const text = readFileSync(systemFile(root), 'utf8')
  assert.match(text, /db_customers = store 'customers'/)
  assert.match(text, /db_vets = store 'vets'/)
})

test('операции с доменами раскладываются по api-группам, рёбра ведут в них', () => {
  const root = makeRoot()
  writeFileSync(
    join(root, 'tools/api-source/petclinic.visits.json'),
    JSON.stringify({
      ...visitsDoc,
      operations: [
        { method: 'GET', path: '/pets/visits', group: 'visit', source: 's#L1', confidence: 0.95 },
        { method: 'GET', path: '/internal/ping', source: 's#L2', confidence: 0.95 },
      ],
    }),
  )
  writeFileSync(join(root, 'tools/api-source/petclinic.customers.json'), JSON.stringify(withCall({ host: 'visits-service' })))
  writeFileSync(join(root, 'registry/aliases.yml'), 'aliases:\n  visits-service: petclinic.visits\n')
  generate(root)
  const text = readFileSync(systemFile(root), 'utf8')

  assert.match(text, /api_visit = api 'visit' \{/, 'доменная группа')
  assert.match(text, /domain 'visit'/)
  assert.match(text, /api = api 'visits API' \{/, 'базовый api для операций без домена')
  // операция в группе, ребро ведёт именно туда
  assert.match(text, /petclinic\.customers -\[call\]-> petclinic\.visits\.api_visit\.get_pets_visits 'GET \/pets\/visits'/)
  // drill-down вид на группу: клик по api_visit проваливается в операции
  assert.match(text, /view petclinic_visits_visit of petclinic\.visits\.api_visit \{/)
  assert.match(text, /title 'API \/ visits-service \/ visit'/)
  // контейнерный api-вид — три уровня: .** разворачивает группы с операциями
  assert.match(text, /include petclinic\.visits\.\*\*/)
})

test('легаси-док без containerInfo игнорируется', () => {
  const root = makeRoot()
  writeFileSync(
    join(root, 'tools/api-source/shop.legacy.json'),
    JSON.stringify({ container: 'shop.legacy', source: {}, api: null }),
  )
  generate(root)
  assert.equal(existsSync(join(root, 'model/gen/shop.legacy.gen.c4')), false)
  assert.equal(existsSync(join(root, 'model/systems/shop/shop.c4')), false)
})

// ---------- разрешение вызовов ----------

test('алиас host -> ребро в операцию цели', () => {
  const root = makeRoot()
  writeFileSync(join(root, 'tools/api-source/petclinic.visits.json'), JSON.stringify(visitsDoc))
  writeFileSync(join(root, 'tools/api-source/petclinic.customers.json'), JSON.stringify(withCall({ host: 'visits-service' })))
  writeFileSync(join(root, 'registry/aliases.yml'), 'aliases:\n  visits-service: petclinic.visits\n')
  generate(root)
  const text = readFileSync(systemFile(root), 'utf8')
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
  const text = readFileSync(systemFile(root), 'utf8')
  assert.match(text, /petclinic\.customers -\[call\]-> petclinic\.visits\.api 'POST \/nope'/)
})

test('target.role без адреса -> stub по роли и запись в unresolved', () => {
  const root = makeRoot()
  writeFileSync(
    join(root, 'tools/api-source/petclinic.customers.json'),
    JSON.stringify(withCall({ role: 'config-server', prop: 'spring-cloud-starter-config' }, {})),
  )
  generate(root)
  const stub = readFileSync(join(root, 'model/gen/unknown/config_server.gen.c4'), 'utf8')
  assert.match(stub, /config_server = service 'config-server' \{/)
  assert.match(stub, /roles 'config-server'/)
  const caller = readFileSync(systemFile(root), 'utf8')
  assert.match(caller, /petclinic\.customers -\[call\]-> unknown\.config_server\.api$/m)
  const unresolved = JSON.parse(readFileSync(join(root, 'registry/unresolved.json'), 'utf8'))
  assert.equal(unresolved.unresolved[0].stubId, 'unknown.config_server')
  assert.deepEqual(unresolved.unresolved[0].signature.roles, ['config-server'])
})

test('алиас роли -> ребро в контейнер', () => {
  const root = makeRoot()
  writeFileSync(join(root, 'tools/api-source/petclinic.visits.json'), JSON.stringify(visitsDoc))
  writeFileSync(
    join(root, 'tools/api-source/petclinic.customers.json'),
    JSON.stringify(withCall({ role: 'discovery' }, {})),
  )
  writeFileSync(join(root, 'registry/aliases.yml'), 'aliases:\n  discovery: petclinic.visits\n')
  generate(root)
  const text = readFileSync(systemFile(root), 'utf8')
  assert.match(text, /petclinic\.customers -\[call\]-> petclinic\.visits\.api$/m)
  assert.equal(existsSync(join(root, 'model/gen/unknown/discovery.gen.c4')), false)
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
  assert.match(stub, /post_api_v1_invoices = operation 'POST \/api\/v1\/invoices'/)
  const caller = readFileSync(systemFile(root), 'utf8')
  assert.match(caller, /petclinic\.customers -\[call\]-> unknown\.legacy_billing\.api\.post_api_v1_invoices/)
  const unresolved = JSON.parse(readFileSync(join(root, 'registry/unresolved.json'), 'utf8'))
  assert.equal(unresolved.unresolved[0].stubId, 'unknown.legacy_billing')
})

test('единственный кандидат со score 1.0 -> автосклейка вместо stub', () => {
  const root = makeRoot()
  writeFileSync(join(root, 'tools/api-source/petclinic.visits.json'), JSON.stringify(visitsDoc))
  writeFileSync(join(root, 'tools/api-source/petclinic.customers.json'), JSON.stringify(withCall({ host: 'visits-internal' })))
  generate(root)
  const text = readFileSync(systemFile(root), 'utf8')
  assert.match(text, /petclinic\.customers -\[call\]-> petclinic\.visits\.api\.get_pets_visits/)
  assert.equal(existsSync(join(root, 'model/gen/unknown/visits_internal.gen.c4')), false)
})

test('assign: stub переезжает контейнером в чужую систему и дообогащается', () => {
  const root = makeRoot()
  writeFileSync(
    join(root, 'registry/systems.yml'),
    'systems:\n  - id: petclinic\n    kind: system\n    title: PetClinic\n  - id: auth\n    kind: orgSystem\n    title: Авторизация\n',
  )
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
  assert.match(observed, /post_oauth_token = operation 'POST \/oauth\/token'/)
  assert.match(observed, /get_userinfo = operation 'GET \/userinfo'/, 'эндпоинты объединяются от всех вызывающих')

  const petclinic = readFileSync(systemFile(root), 'utf8')
  assert.match(petclinic, /petclinic\.customers -\[call\]-> auth\.sso\.api\.post_oauth_token/)
  assert.match(petclinic, /petclinic\.visits -\[call\]-> auth\.sso\.api\.get_userinfo/)

  // у оргсистемы auth тоже свой файл с декларацией и видом
  const authFile = readFileSync(systemFile(root, 'auth'), 'utf8')
  assert.match(authFile, /auth = orgSystem 'Авторизация' \{/)
  assert.match(authFile, /view auth_containers of auth \{/)

  assert.equal(existsSync(join(root, 'model/gen/unknown/sso_corp.gen.c4')), false, 'stub исчез')
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
  generate(root)
  assert.equal(existsSync(join(root, 'model/gen/unknown/legacy_billing.gen.c4')), true)

  writeFileSync(
    join(root, 'registry/resolutions.yml'),
    'resolutions:\n' +
      '  unknown.legacy_billing:\n    container: petclinic.visits\n' +
      '  unknown.api_stripe_com:\n    external:\n      id: stripe\n      title: Stripe\n      contract: MSA-1\n',
  )
  generate(root)
  assert.equal(existsSync(join(root, 'model/gen/unknown/legacy_billing.gen.c4')), false, 'stub удалён после склейки')
  const caller = readFileSync(systemFile(root), 'utf8')
  assert.match(caller, /petclinic\.customers -\[call\]-> petclinic\.visits\.api 'POST \/x'/)
  assert.match(caller, /petclinic\.customers -\[call\]-> stripe 'POST \/charge'/)
  const ext = readFileSync(join(root, 'model/gen/unknown/_externals.gen.c4'), 'utf8')
  assert.match(ext, /stripe = externalSystem 'Stripe' \{/)
})
