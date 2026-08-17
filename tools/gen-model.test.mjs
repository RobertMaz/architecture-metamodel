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

test('легаси-док без containerInfo игнорируется', () => {
  const root = makeRoot()
  writeFileSync(
    join(root, 'tools/api-source/shop.legacy.json'),
    JSON.stringify({ container: 'shop.legacy', source: {}, api: null }),
  )
  generate(root)
  assert.equal(existsSync(join(root, 'model/gen/shop.legacy.gen.c4')), false)
})
