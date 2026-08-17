#!/usr/bin/env node
/**
 * «Кто сломается, если я поменяю это?»
 *
 *   node tools/impact.mjs build/model.json shop.orders.api.post_api_v1_orders
 *   node tools/impact.mjs build/model.json shop.orderCreated
 *   node tools/impact.mjs build/model.json shop.orders          # весь сервис
 *
 * Обход в обратную сторону по связям, с учётом вложенности:
 * меняешь эндпоинт — задет api, задет сервис, задеты все, кто зовёт.
 *
 * Это же место, куда потом встаёт бот в PR: собрать изменённые эндпоинты
 * из диффа и вывалить список потребителей с командами-владельцами.
 */

import { readFileSync } from 'node:fs'
import { ownerOf as ownerFromCodeowners } from './owners.mjs'

const [file, target] = process.argv.slice(2)
const m = JSON.parse(readFileSync(file, 'utf8'))
const elements = m.elements ?? {}

if (!elements[target]) {
  console.error(`нет такого элемента: ${target}`)
  process.exit(2)
}

const rels = Object.values(m.relations ?? {}).map((r) => ({
  src: r.source.model ?? r.source,
  dst: r.target.model ?? r.target,
  kind: r.kind ?? 'call',
  title: r.title ?? '',
}))

// Элемент задет, если задет он сам или любой его потомок.
const selfAndDescendants = (id) =>
  Object.keys(elements).filter((x) => x === id || x.startsWith(id + '.'))

const ownerOf = (id) => ownerFromCodeowners(id) ?? '—'

/**
 * Направление ЗАВИСИМОСТИ не всегда совпадает с направлением стрелки.
 * call / read / write / publish : зависит источник (идём против стрелки).
 * deliver                      : зависит получатель — он разбирает схему
 *                                события (идём ПО стрелке).
 * Ровно поэтому направление зависимости выводится из kind связи,
 * а не из того, куда нарисован наконечник.
 */
const BACKWARD = new Set(['call', 'read', 'write', 'publish'])
const FORWARD = new Set(['deliver'])

const affected = new Map() // id -> {depth, via}
let frontier = new Set(selfAndDescendants(target))
const seen = new Set(frontier)

for (let depth = 1; frontier.size && depth < 10; depth++) {
  const next = new Set()
  for (const r of rels) {
    const dependent = BACKWARD.has(r.kind)
      ? frontier.has(r.dst) && r.src
      : FORWARD.has(r.kind)
        ? frontier.has(r.src) && r.dst
        : null
    if (!dependent) continue
    // Задет сам зависимый элемент и все его родители
    let cur = dependent
    while (cur) {
      if (!seen.has(cur)) {
        seen.add(cur)
        next.add(cur)
        affected.set(cur, { depth, via: r.kind })
      }
      cur = cur.includes('.') ? cur.slice(0, cur.lastIndexOf('.')) : null
    }
  }
  frontier = next
}

const t = elements[target]
console.log(`\nИзменение: ${target}  (${t.title})`)
if (t.metadata?.method) {
  console.log(`  ${t.metadata.method} ${t.metadata.path}`)
  if (t.metadata.params) console.log(`  params:    ${t.metadata.params}`)
  if (t.metadata.request) console.log(`  request:   ${t.metadata.request}`)
  if (t.metadata.responses) console.log(`  responses: ${t.metadata.responses}`)
  if (t.metadata.source) console.log(`  код:       ${t.metadata.source}`)
}

if (!affected.size) {
  console.log(`\nПотребителей не найдено. Если контракт публичный — они могут быть снаружи.`)
  process.exit(0)
}


const rows = [...affected.entries()]
  .filter(([id]) => elements[id].kind !== 'system')
  .sort((a, b) => a[1].depth - b[1].depth)
console.log(`\nЗатронуто: ${rows.length}`)
for (const [id, info] of rows) {
  const e = elements[id]
  console.log(
    `  ${'·'.repeat(info.depth)} ${id.padEnd(40)} ${String(e.title).padEnd(20)} ${info.via.padEnd(8)} ${ownerOf(id)}`,
  )
}

const teams = [...new Set(rows.map(([id]) => ownerOf(id)))].filter((t) => t !== '—')
console.log(`\nПредупредить команды: ${teams.join(', ')}`)
