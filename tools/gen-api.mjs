#!/usr/bin/env node
/**
 * tools/api-source/*.json  ->  model/gen/*.gen.c4
 *
 *   node tools/gen-api.mjs
 *
 * Разделение труда:
 *   - анализатор Java (парсер + LLM) знает про код и выдаёт JSON;
 *   - этот генератор знает про LikeC4 и ничего не знает про Java;
 *   - человек не трогает ни то, ни другое.
 *
 * Граф хранит ИДЕНТИЧНОСТЬ и СВЯЗИ (кто кого зовёт).
 * Полная сигнатура — в metadata и в исходном JSON рядом.
 * Пихать схемы целиком в граф не надо: граф про рёбра, схема про документ.
 */

import { readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { opId, esc } from './ids.mjs'

import { dataPath } from './root.mjs'

const SRC = dataPath('tools/api-source')
const OUT = dataPath('model/gen')
const compactParams = (params = []) =>
  params
    .map((p) => `${p.name}:${p.in}:${p.type}${p.required ? '' : '?'}`)
    .join(', ')
const compactResponses = (rs = []) =>
  rs.map((r) => `${r.status} ${r.type ?? ''}`.trim()).join(', ')

let files = 0
mkdirSync(OUT, { recursive: true })

const seen = new Map()
const claim = (container, id, label) => {
  const key = `${container}.${id}`
  if (seen.has(key)) {
    console.error(`КОЛЛИЗИЯ id: ${label} и ${seen.get(key)} дают ${key}`)
    process.exit(1)
  }
  seen.set(key, label)
}

for (const f of readdirSync(SRC).filter((f) => f.endsWith('.json'))) {
  const d = JSON.parse(readFileSync(join(SRC, f), 'utf8'))
  // v2-доки (с containerInfo) обрабатывает tools/gen-model.mjs
  if (d.containerInfo) continue
  const L = []

  L.push(`// СГЕНЕРИРОВАНО, РУКАМИ НЕ ПРАВИТЬ.`)
  L.push(`// Источник: ${SRC}/${f}`)
  L.push(`// Репозиторий: ${d.source.repo}@${d.source.commit}`)
  L.push(`// Извлечено: ${d.source.extractedAt} (${d.source.extractor})`)
  L.push(``)
  L.push(`model {`)
  L.push(`  extend ${d.container} {`)

  // ---- HTTP-контракт ----
  if (d.api) {
    const tags = ['#inferred', d.api.public ? '#public' : null].filter(Boolean)
    L.push(``)
    L.push(`    ${d.api.id} = api '${esc(d.api.title)}' {`)
    L.push(`      ${tags.join(' ')}`)
    L.push(`      technology '${esc(d.api.technology)}'`)
    L.push(`      metadata {`)
    L.push(`        base-path '${esc(d.api.basePath)}'`)
    L.push(`        source-repo '${esc(d.source.repo)}'`)
    L.push(`        extracted-at '${esc(d.source.extractedAt)}'`)
    L.push(`      }`)

    for (const op of d.operations ?? []) {
      const id = opId(op.method, op.path)
      claim(d.container, id, `${op.method} ${op.path}`)
      const t = ['#inferred', op.deprecated ? '#deprecated' : null].filter(Boolean)
      L.push(``)
      L.push(`      ${id} = operation '${esc(op.method)} ${esc(op.path)}' {`)
      L.push(`        ${t.join(' ')}`)
      if (op.summary) L.push(`        description '${esc(op.summary)}'`)
      L.push(`        metadata {`)
      L.push(`          method '${esc(op.method)}'`)
      L.push(`          path '${esc(op.path)}'`)
      if (op.params?.length)
        L.push(`          params '${esc(compactParams(op.params))}'`)
      if (op.request)
        L.push(`          request '${esc(op.request.type)}'`)
      if (op.responses?.length)
        L.push(`          responses '${esc(compactResponses(op.responses))}'`)
      if (op.auth) L.push(`          auth '${esc(op.auth)}'`)
      if (op.sunset) L.push(`          sunset '${esc(op.sunset)}'`)
      L.push(`          source '${esc(op.source)}'`)
      L.push(`          confidence '${op.confidence}'`)
      L.push(`        }`)
      L.push(`      }`)
    }
    L.push(`    }`)
  }
  L.push(`  }`)

  // ---- схемы сообщений: живут в канале, а не в отправителе ----
  for (const p of d.publishes ?? []) {
    L.push(``)
    L.push(`  extend ${p.channel} {`)
    L.push(`    ${p.schema.toLowerCase()} = message '${esc(p.schema)}' {`)
    L.push(`      #inferred`)
    L.push(`      description '${esc(p.fields)}'`)
    L.push(`      metadata {`)
    L.push(`        key '${esc(p.key)}'`)
    L.push(`        producer '${esc(d.container)}'`)
    L.push(`        source '${esc(p.source)}'`)
    L.push(`        confidence '${p.confidence}'`)
    L.push(`      }`)
    L.push(`    }`)
    L.push(`  }`)
  }

  L.push(`}`)
  L.push(``)

  const out = join(OUT, `${d.container}.gen.c4`)
  writeFileSync(out, L.join('\n'))
  console.log(`${out}  <-  ${f}  (${d.operations?.length ?? 0} операций)`)
  files++
}

console.log(`\nсгенерировано файлов: ${files}`)
