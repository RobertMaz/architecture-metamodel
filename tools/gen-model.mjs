#!/usr/bin/env node
/**
 * Генератор v2: registry/*.yml + tools/api-source/*.json (доки с containerInfo)
 *   -> model/gen/systems/<id>.gen.c4   каркасы систем
 *   -> model/gen/<container>.gen.c4    контейнер #inferred + api + исходящие рёбра
 *   -> model/gen/_shared.gen.c4        общие узлы: сторы, каналы, message, deliver
 *
 * Легаси-доки v1 (без containerInfo) обрабатывает tools/gen-api.mjs.
 * Детерминизм: всё отсортировано, файл переписывается только при изменении —
 * чистый git-diff = чистый прогон.
 */

import { readdirSync, readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { parse as parseYaml } from 'yaml'
import { opId, slug, esc } from './ids.mjs'

const systemOf = (id) => id.split('.')[0]
const shortName = (id) => id.split('.').pop()

/** Имя БД из адреса: последний сегмент пути/двоеточия, без query. */
function storeName(address, containerId) {
  if (!address) return shortName(containerId)
  const base = address.split('?')[0]
  const seg = base.split(/[/:]/).filter(Boolean).pop()
  return seg || shortName(containerId)
}

function writeIfChanged(path, content) {
  if (existsSync(path) && readFileSync(path, 'utf8') === content) return false
  writeFileSync(path, content)
  return true
}

function header(source) {
  return [`// СГЕНЕРИРОВАНО, РУКАМИ НЕ ПРАВИТЬ.`, `// Источник: ${source}`, ``]
}

export function generate(root = '.') {
  const genDir = join(root, 'model/gen')
  mkdirSync(join(genDir, 'systems'), { recursive: true })

  // --- системы из registry/systems.yml -------------------------------
  const systemsFile = join(root, 'registry/systems.yml')
  const systems = existsSync(systemsFile)
    ? (parseYaml(readFileSync(systemsFile, 'utf8'))?.systems ?? [])
    : []
  for (const s of [...systems].sort((a, b) => a.id.localeCompare(b.id))) {
    const L = header('registry/systems.yml')
    L.push(`model {`)
    L.push(`  ${s.id} = ${s.kind} '${esc(s.title)}' {`)
    if (s.description) L.push(`    description '${esc(s.description)}'`)
    L.push(`  }`)
    L.push(`}`)
    L.push(``)
    writeIfChanged(join(genDir, 'systems', `${s.id}.gen.c4`), L.join('\n'))
  }

  // --- v2-доки ---------------------------------------------------------
  const srcDir = join(root, 'tools/api-source')
  const docs = (existsSync(srcDir) ? readdirSync(srcDir) : [])
    .filter((f) => f.endsWith('.json'))
    .sort()
    .map((f) => ({ file: f, d: JSON.parse(readFileSync(join(srcDir, f), 'utf8')) }))
    .filter(({ d }) => d.containerInfo)

  // Общие узлы собираются по всем докам: идентичность — kind|адрес и топик.
  const stores = new Map() // key -> {id, system, title, technology, address, entities:Set, accessBy:Map(container->access)}
  const channels = new Map() // topic -> {id, system, topic, messages:Map(schema->{producer,fields,source,confidence}), delivers:[]}

  const claimStore = (doc, st) => {
    const key = `${st.kind}|${st.address}`
    if (!stores.has(key)) {
      stores.set(key, {
        id: `db_${slug(storeName(st.address, doc.container))}`,
        system: systemOf(doc.container),
        title: storeName(st.address, doc.container),
        technology: st.technology ?? st.kind.toUpperCase(),
        address: st.address,
        entities: new Set(),
        accessBy: new Map(),
      })
    }
    const s = stores.get(key)
    // Размещение — у лексикографически первой системы-писателя (детерминизм).
    if (systemOf(doc.container) < s.system) s.system = systemOf(doc.container)
    for (const e of (st.entities ?? '').split(',').map((x) => x.trim()).filter(Boolean)) s.entities.add(e)
    s.accessBy.set(doc.container, st.access)
    return s
  }

  const claimChannel = (system, topic) => {
    if (!channels.has(topic)) {
      channels.set(topic, { id: `ch_${slug(topic)}`, system, topic, messages: new Map(), delivers: [] })
    }
    const c = channels.get(topic)
    if (system < c.system) c.system = system
    return c
  }

  let unresolvedCalls = 0

  for (const { file, d } of docs) {
    const sys = systemOf(d.container)
    const name = shortName(d.container)
    const info = d.containerInfo
    const L = header(`tools/api-source/${file}`)
    L.splice(2, 0,
      `// Репозиторий: ${d.source.repo}@${d.source.commit}`,
      `// Извлечено: ${d.source.extractedAt} (${d.source.extractor})`,
    )

    L.push(`model {`)
    L.push(`  extend ${sys} {`)
    L.push(``)
    L.push(`    ${name} = ${info.kind} '${esc(info.title)}' {`)
    L.push(`      #inferred`)
    L.push(`      technology '${esc(info.technology)}'`)
    if (d.source.repo?.startsWith('http')) L.push(`      link ${d.source.repo} 'repo'`)
    L.push(`      metadata {`)
    if (info.appName) L.push(`        app-name '${esc(info.appName)}'`)
    L.push(`        repo '${esc(d.source.repo)}'`)
    L.push(`        extracted-at '${esc(d.source.extractedAt)}'`)
    L.push(`      }`)

    // --- контрактный слой (как в gen-api, но версия v2) ---------------
    if (d.api) {
      const tags = ['#inferred', d.api.public ? '#public' : null].filter(Boolean)
      const seen = new Map()
      L.push(``)
      L.push(`      ${d.api.id} = api '${esc(d.api.title)}' {`)
      L.push(`        ${tags.join(' ')}`)
      L.push(`        technology '${esc(d.api.technology)}'`)
      L.push(`        metadata {`)
      L.push(`          base-path '${esc(d.api.basePath)}'`)
      L.push(`        }`)
      for (const op of d.operations ?? []) {
        const id = opId(op.method, op.path)
        if (seen.has(id)) {
          console.error(`КОЛЛИЗИЯ id: ${op.method} ${op.path} и ${seen.get(id)} дают ${d.container}.${d.api.id}.${id}`)
          process.exit(1)
        }
        seen.set(id, `${op.method} ${op.path}`)
        const t = ['#inferred', op.deprecated ? '#deprecated' : null].filter(Boolean)
        L.push(``)
        L.push(`        ${id} = operation '${esc(op.method)} ${esc(op.path)}' {`)
        L.push(`          ${t.join(' ')}`)
        if (op.summary) L.push(`          description '${esc(op.summary)}'`)
        L.push(`          metadata {`)
        L.push(`            method '${esc(op.method)}'`)
        L.push(`            path '${esc(op.path)}'`)
        if (op.params) L.push(`            params '${esc(op.params)}'`)
        if (op.request) L.push(`            request '${esc(op.request)}'`)
        if (op.response) L.push(`            responses '${esc(op.response)}'`)
        if (op.sunset) L.push(`            sunset '${esc(op.sunset)}'`)
        L.push(`            source '${esc(op.source)}'`)
        L.push(`            confidence '${op.confidence}'`)
        L.push(`          }`)
        L.push(`        }`)
      }
      L.push(`      }`)
    }

    L.push(`    }`)
    L.push(`  }`)

    // --- исходящие рёбра контейнера -----------------------------------
    const edges = []
    for (const st of [...(d.stores ?? [])].sort((a, b) => `${a.kind}|${a.address}`.localeCompare(`${b.kind}|${b.address}`))) {
      const s = claimStore(d, st)
      const target = `${s.system}.${s.id}`
      if (st.access === 'read' || st.access === 'readwrite') edges.push(`  ${d.container} -[read]-> ${target}`)
      if (st.access === 'write' || st.access === 'readwrite') edges.push(`  ${d.container} -[write]-> ${target}`)
    }
    for (const p of [...(d.publishes ?? [])].sort((a, b) => a.channel.localeCompare(b.channel))) {
      const c = claimChannel(sys, p.channel)
      if (p.schema) c.messages.set(p.schema, { producer: d.container, fields: p.fields, source: p.source, confidence: p.confidence })
      edges.push(`  ${d.container} -[publish]-> ${c.system}.${c.id}${p.schema ? ` '${esc(p.schema)}'` : ''}`)
    }
    for (const sub of [...(d.subscribes ?? [])].sort((a, b) => a.channel.localeCompare(b.channel))) {
      const c = claimChannel(sys, sub.channel)
      c.delivers.push({ to: d.container, group: sub.group })
    }
    for (const call of d.calls ?? []) {
      if (!call.target?.container) unresolvedCalls++
      // Разрешённые вызовы (target.container) появятся в подпроекте 3.
    }

    if (edges.length) {
      L.push(``)
      L.push(...edges.sort())
    }
    L.push(`}`)
    L.push(``)
    writeIfChanged(join(genDir, `${d.container}.gen.c4`), L.join('\n'))
    console.log(`model/gen/${d.container}.gen.c4  <-  ${file}  (${d.operations?.length ?? 0} операций)`)
  }

  // --- общие узлы ------------------------------------------------------
  if (docs.length) {
    const L = [
      `// СГЕНЕРИРОВАНО, РУКАМИ НЕ ПРАВИТЬ.`,
      `// Общие узлы: сторы и каналы. Идентичность — нормализованный адрес/топик:`,
      `// два сервиса с одним адресом сходятся в один узел, и инвариант`,
      `// «у store один писатель» превращается в детектор shared database.`,
      ``,
    ]
    // Группировка узлов по системам.
    const bySystem = new Map()
    const claimNode = (system, render) => {
      if (!bySystem.has(system)) bySystem.set(system, [])
      bySystem.get(system).push(render)
    }
    for (const [, c] of [...channels.entries()].sort(([a], [b]) => a.localeCompare(b))) {
      claimNode(c.system, () => {
        const B = []
        B.push(`    ${c.id} = channel '${esc(c.topic)}' {`)
        B.push(`      #inferred`)
        B.push(`      technology 'Kafka topic'`)
        for (const [schema, m] of [...c.messages.entries()].sort(([a], [b]) => a.localeCompare(b))) {
          B.push(``)
          B.push(`      ${slug(schema)} = message '${esc(schema)}' {`)
          B.push(`        #inferred`)
          if (m.fields) B.push(`        description '${esc(m.fields)}'`)
          B.push(`        metadata {`)
          B.push(`          producer '${esc(m.producer)}'`)
          B.push(`          source '${esc(m.source)}'`)
          B.push(`          confidence '${m.confidence}'`)
          B.push(`        }`)
          B.push(`      }`)
        }
        B.push(`    }`)
        return B
      })
    }
    for (const [, s] of [...stores.entries()].sort(([a], [b]) => a.localeCompare(b))) {
      claimNode(s.system, () => {
        const B = []
        B.push(`    ${s.id} = store '${esc(s.title)}' {`)
        B.push(`      #inferred`)
        B.push(`      technology '${esc(s.technology)}'`)
        B.push(`      metadata {`)
        if (s.address) B.push(`        address '${esc(s.address)}'`)
        const entities = [...s.entities].sort().join(', ')
        if (entities) B.push(`        entities '${esc(entities)}'`)
        B.push(`      }`)
        B.push(`    }`)
        return B
      })
    }

    L.push(`model {`)
    for (const [system, nodes] of [...bySystem.entries()].sort(([a], [b]) => a.localeCompare(b))) {
      L.push(`  extend ${system} {`)
      nodes.forEach((render, i) => {
        if (i > 0) L.push(``)
        L.push(...render())
      })
      L.push(`  }`)
    }

    const delivers = []
    for (const [, c] of [...channels.entries()].sort(([a], [b]) => a.localeCompare(b))) {
      for (const dlv of [...c.delivers].sort((a, b) => a.to.localeCompare(b.to))) {
        delivers.push(`  ${c.system}.${c.id} -[deliver]-> ${dlv.to}${dlv.group ? ` 'group: ${esc(dlv.group)}'` : ''}`)
      }
    }
    if (delivers.length) {
      L.push(``)
      L.push(...delivers)
    }
    L.push(`}`)
    L.push(``)
    writeIfChanged(join(genDir, `_shared.gen.c4`), L.join('\n'))
  }

  if (unresolvedCalls) {
    console.log(`вызовов ждут разрешения (подпроект 3 — реестр): ${unresolvedCalls}`)
  }
  return { docs: docs.length, stores: stores.size, channels: channels.size, unresolvedCalls }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const r = generate('.')
  console.log(`\nv2-доков: ${r.docs}, сторов: ${r.stores}, каналов: ${r.channels}`)
}
