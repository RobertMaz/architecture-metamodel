#!/usr/bin/env node
/**
 * Генератор v2 + движок разрешения вызовов.
 *
 * Входы:  registry/systems.yml, registry/aliases.yml, registry/resolutions.yml,
 *         tools/api-source/*.json (доки с containerInfo; легаси v1 обрабатывает gen-api.mjs)
 * Выходы: model/systems/<id>/<id>.c4         система целиком: контейнеры, сторы,
 *                                            каналы, связи и views (один файл — вся кухня)
 *         model/gen/unknown/<slug>.gen.c4    stub-заглушки нераспознанных целей (догадки)
 *         model/gen/observed/<cid>.gen.c4    наблюдаемые контейнеры чужих систем (assign)
 *         model/gen/unknown/_externals.gen.c4  внешние системы из resolutions.yml
 *         registry/unresolved.json           журнал нераспознанного (генерируется)
 *
 * Разрешение цели вызова (по порядку):
 *   target.container -> алиас feignName -> алиас host -> алиас первого label host ->
 *   автосклейка (единственный кандидат, все наблюдённые эндпоинты совпали) ->
 *   ручное решение из resolutions.yml -> stub в системе unknown.
 *
 * Детерминизм: всё отсортировано, файлы переписываются только при изменении,
 * устаревшие stub-файлы удаляются.
 */

import { readdirSync, readFileSync, writeFileSync, mkdirSync, existsSync, unlinkSync, rmdirSync } from 'node:fs'
import { execSync } from 'node:child_process'
import { join, basename } from 'node:path'
import { parse as parseYaml } from 'yaml'
import { opId, slug, esc } from './ids.mjs'

const systemOf = (id) => id.split('.')[0]
const shortName = (id) => id.split('.').pop()
const normPath = (p) => String(p).replace(/\{[^}]*\}/g, '{_p_}')

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

/**
 * Файлы систем можно править руками; git — арбитр. Но git спасает только
 * закоммиченное: незакоммиченную ручную правку перезапись уничтожила бы молча.
 * Поэтому перед перезаписью такая версия уезжает в workspace/_backup/ (gitignore).
 */
function backupHandEdits(root, relPath, content) {
  const abs = join(root, relPath)
  if (!existsSync(abs)) return
  const current = readFileSync(abs, 'utf8')
  if (current === content) return
  let head
  try {
    head = execSync(`git -C "${root}" show HEAD:"${relPath}"`, { stdio: ['ignore', 'pipe', 'ignore'] }).toString()
  } catch {
    return // не git-репо или файла нет в HEAD — бэкапить не от чего
  }
  if (current === head) return
  const dir = join(root, 'workspace/_backup')
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, basename(abs)), current)
  console.error(`ВНИМАНИЕ: незакоммиченные правки ${relPath} перезаписаны — копия в workspace/_backup/${basename(abs)}`)
}

function readYaml(path, key) {
  if (!existsSync(path)) return null
  return parseYaml(readFileSync(path, 'utf8'))?.[key] ?? null
}

function header(source) {
  return [`// СГЕНЕРИРОВАНО, РУКАМИ НЕ ПРАВИТЬ.`, `// Источник: ${source}`, ``]
}

export function generate(root = '.') {
  const genDir = join(root, 'model/gen')
  const unknownDir = join(genDir, 'unknown')
  mkdirSync(genDir, { recursive: true })

  // --- реестры ---------------------------------------------------------
  const systems = readYaml(join(root, 'registry/systems.yml'), 'systems') ?? []
  const aliases = readYaml(join(root, 'registry/aliases.yml'), 'aliases') ?? {}
  const resolutions = readYaml(join(root, 'registry/resolutions.yml'), 'resolutions') ?? {}
  const knownSystems = new Set(systems.map((s) => s.id))

  // --- доки ------------------------------------------------------------
  const srcDir = join(root, 'tools/api-source')
  const docs = (existsSync(srcDir) ? readdirSync(srcDir) : [])
    .filter((f) => f.endsWith('.json'))
    .sort()
    .map((f) => ({ file: f, d: JSON.parse(readFileSync(join(srcDir, f), 'utf8')) }))
    .filter(({ d }) => d.containerInfo)

  // Индекс контрактов: containerId -> {apiId, ops: 'METHOD normPath' -> opId}
  const apiIndex = new Map()
  for (const { d } of docs) {
    if (!d.api) continue
    const ops = new Map()
    for (const op of d.operations ?? []) ops.set(`${op.method} ${normPath(op.path)}`, opId(op.method, op.path))
    apiIndex.set(d.container, { apiId: d.api.id, ops })
  }

  // --- ПРОХОД 1: общие узлы + сбор вызовов -----------------------------
  const stores = new Map()
  const channels = new Map()

  const claimStore = (doc, st) => {
    const key = st.address ? `${st.kind}|${st.address}` : `${st.kind}|${doc.container}`
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

  // Записи вызовов для глобального разрешения.
  const callRecords = [] // {caller, call}
  for (const { d } of docs) {
    for (const call of d.calls ?? []) callRecords.push({ caller: d.container, call })
  }

  // --- ПРОХОД 2: разрешение --------------------------------------------
  const resolveByAlias = (t) => {
    if (t.container) return t.container
    if (t.feignName && aliases[t.feignName]) return aliases[t.feignName]
    if (t.host) {
      if (aliases[t.host]) return aliases[t.host]
      const label = t.host.split('.')[0]
      if (aliases[label]) return aliases[label]
    }
    return null
  }

  /** Ссылка для ребра в известный контейнер: операция -> api -> контейнер. */
  const targetRef = (cid, call) => {
    const idx = apiIndex.get(cid)
    if (idx && call.method && call.path) {
      const op = idx.ops.get(`${call.method} ${normPath(call.path)}`)
      if (op) return `${cid}.${idx.apiId}.${op}`
    }
    if (idx) return `${cid}.${idx.apiId}`
    return cid
  }

  // Группировка неразрешённого по сигнатуре цели.
  const stubGroups = new Map() // slug -> {...}
  const looseEntries = [] // записи unresolved без stub-узла
  const edgesByCaller = new Map() // container -> [edge lines]
  const externals = new Map() // extId -> {title, contract}
  // Наблюдаемые контейнеры: stub, перенесённый человеком в явную систему (assign).
  // Мы их не анализируем — их api собирается из наблюдённых вызовов и дообогащается
  // при каждой регенерации: тот же host/feign -> та же резолюция -> union эндпоинтов.
  const observedContainers = new Map() // containerId -> {system, name, hosts, feignNames, urlTemplates, endpoints}

  const addEdge = (caller, ref, label) => {
    if (!edgesByCaller.has(caller)) edgesByCaller.set(caller, [])
    edgesByCaller.get(caller).push(`  ${caller} -[call]-> ${ref}${label ? ` '${esc(label)}'` : ''}`)
  }

  const callLabel = (call) =>
    call.method && call.path ? `${call.method} ${call.path}` : call.method ?? ''

  for (const { caller, call } of callRecords) {
    const t = call.target ?? {}
    const aliased = resolveByAlias(t)
    if (aliased) {
      if (apiIndex.has(aliased) || docs.some(({ d }) => d.container === aliased)) {
        addEdge(caller, targetRef(aliased, call), callLabel(call))
      } else {
        looseEntries.push({
          stubId: null,
          note: `цель разрешена в «${aliased}», но контейнер ещё не проанализирован`,
          observedEndpoints: call.method && call.path ? [{ method: call.method, path: call.path }] : [],
          callers: [{ container: caller, source: call.source }],
          candidates: [{ container: aliased, score: 1, matched: 'alias' }],
        })
      }
      continue
    }

    const key = t.feignName ?? t.host ?? t.urlTemplate
    if (!key) {
      looseEntries.push({
        stubId: null,
        note: 'нет сигнатуры цели (ни host, ни feign, ни url)',
        observedEndpoints: call.method && call.path ? [{ method: call.method, path: call.path }] : [],
        callers: [{ container: caller, source: call.source }],
        candidates: [],
      })
      continue
    }

    const sl = slug(key)
    if (!stubGroups.has(sl)) {
      stubGroups.set(sl, {
        slug: sl,
        key,
        feignNames: new Set(),
        hosts: new Set(),
        urlTemplates: new Set(),
        endpoints: new Map(),
        callers: [],
        calls: [],
      })
    }
    const g = stubGroups.get(sl)
    if (t.feignName) g.feignNames.add(t.feignName)
    if (t.host) g.hosts.add(t.host)
    if (t.urlTemplate) g.urlTemplates.add(t.urlTemplate)
    if (call.method && call.path) g.endpoints.set(`${call.method} ${call.path}`, { method: call.method, path: call.path })
    g.callers.push({ container: caller, source: call.source })
    g.calls.push({ caller, call })
  }

  // Кандидаты и автосклейка / ручные решения.
  const unresolvedEntries = [...looseEntries]
  const liveStubs = []
  for (const g of [...stubGroups.values()].sort((a, b) => a.slug.localeCompare(b.slug))) {
    const stubId = `unknown.${g.slug}`
    const observed = [...g.endpoints.values()].sort((a, b) => `${a.method} ${a.path}`.localeCompare(`${b.method} ${b.path}`))

    const decision = resolutions[stubId]
    if (decision?.container) {
      for (const { caller, call } of g.calls) addEdge(caller, targetRef(decision.container, call), callLabel(call))
      continue
    }
    if (decision?.assign?.container) {
      const cid = decision.assign.container
      if (!observedContainers.has(cid)) {
        observedContainers.set(cid, {
          system: systemOf(cid),
          name: shortName(cid),
          hosts: new Set(),
          feignNames: new Set(),
          urlTemplates: new Set(),
          endpoints: new Map(),
        })
      }
      const o = observedContainers.get(cid)
      for (const h of g.hosts) o.hosts.add(h)
      for (const f of g.feignNames) o.feignNames.add(f)
      for (const u of g.urlTemplates) o.urlTemplates.add(u)
      for (const [k, e] of g.endpoints) o.endpoints.set(k, e)
      for (const { caller, call } of g.calls) {
        const ref = call.method && call.path ? `${cid}.api.${opId(call.method, call.path)}` : `${cid}.api`
        addEdge(caller, ref, callLabel(call))
      }
      continue
    }
    if (decision?.external) {
      const e = decision.external
      externals.set(e.id, { title: e.title ?? e.id, contract: e.contract })
      for (const { caller, call } of g.calls) addEdge(caller, e.id, callLabel(call))
      continue
    }

    const candidates = []
    if (observed.length) {
      for (const [cid, idx] of [...apiIndex.entries()].sort(([a], [b]) => a.localeCompare(b))) {
        const matched = observed.filter((e) => idx.ops.has(`${e.method} ${normPath(e.path)}`))
        if (matched.length) {
          candidates.push({
            container: cid,
            score: Math.round((matched.length / observed.length) * 100) / 100,
            matched: matched.map((e) => `${e.method} ${e.path}`).join(', '),
          })
        }
      }
      candidates.sort((a, b) => b.score - a.score || a.container.localeCompare(b.container))
    }

    const perfect = candidates.filter((c) => c.score === 1)
    if (perfect.length === 1 && observed.length > 0) {
      // Автосклейка: сервис с ровно такими эндпоинтами уже в модели.
      for (const { caller, call } of g.calls) addEdge(caller, targetRef(perfect[0].container, call), callLabel(call))
      continue
    }

    // Остаёмся stub'ом: узел в unknown + запись в журнал.
    liveStubs.push(g)
    for (const { caller, call } of g.calls) {
      const ref =
        call.method && call.path
          ? `${stubId}.api.${opId(call.method, call.path)}`
          : `${stubId}.api`
      addEdge(caller, ref, callLabel(call))
    }
    unresolvedEntries.push({
      stubId,
      signature: {
        feignNames: [...g.feignNames].sort(),
        hosts: [...g.hosts].sort(),
        urlTemplates: [...g.urlTemplates].sort(),
      },
      observedEndpoints: observed,
      callers: [...g.callers].sort((a, b) => `${a.container} ${a.source}`.localeCompare(`${b.container} ${b.source}`)),
      candidates,
    })
  }

  // --- РЕНДЕР-ПОДГОТОВКА: блоки и рёбра раскладываются по системам ------
  // Система анализатора = один файл со всей кухней (model/systems/<id>/<id>.c4):
  // контейнеры, сторы, каналы, связи и виды. В model/gen/ остаются только догадки.
  const containerBlocks = new Map() // system -> [[lines]]
  const systemEdges = new Map() // system -> [edge lines]
  const apiViews = new Map() // system -> [{name, title}]
  const pushBlock = (sys, lines) => {
    if (!containerBlocks.has(sys)) containerBlocks.set(sys, [])
    containerBlocks.get(sys).push(lines)
  }
  const pushEdges = (sys, lines) => {
    if (!systemEdges.has(sys)) systemEdges.set(sys, [])
    systemEdges.get(sys).push(...lines)
  }

  for (const { file, d } of docs) {
    const sys = systemOf(d.container)
    const name = shortName(d.container)
    const info = d.containerInfo
    if (!knownSystems.has(sys)) {
      console.error(`ПРОПУСК ${d.container}: система «${sys}» не заведена в registry/systems.yml`)
      continue
    }
    const L = []
    L.push(`    ${name} = ${info.kind} '${esc(info.title)}' {`)
    L.push(`      #inferred`)
    if (info.description) L.push(`      description '${esc(info.description)}'`)
    L.push(`      technology '${esc(info.technology)}'`)
    if (d.source.repo?.startsWith('http')) L.push(`      link ${d.source.repo} 'repo'`)
    L.push(`      metadata {`)
    if (info.appName) L.push(`        app-name '${esc(info.appName)}'`)
    L.push(`        repo '${esc(d.source.repo)}'`)
    L.push(`        commit '${esc(d.source.commit)}'`)
    L.push(`        extracted-at '${esc(d.source.extractedAt)}'`)
    L.push(`      }`)

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
    pushBlock(sys, L)
    if ((d.operations ?? []).length) {
      if (!apiViews.has(sys)) apiViews.set(sys, [])
      apiViews.get(sys).push({ name, title: info.title })
    }

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
    edges.push(...(edgesByCaller.get(d.container) ?? []))
    pushEdges(sys, edges)
    console.log(`${d.container}  <-  ${file}  (${d.operations?.length ?? 0} операций)`)
  }

  // --- Узлы сторов/каналов — в файл системы-владельца -------------------
  // Идентичность общих узлов не меняется: два сервиса с одним адресом сходятся
  // в один узел, и инвариант «у store один писатель» ловит shared database.
  for (const [, c] of [...channels.entries()].sort(([a], [b]) => a.localeCompare(b))) {
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
    pushBlock(c.system, B)
    pushEdges(
      c.system,
      [...c.delivers]
        .sort((a, b) => a.to.localeCompare(b.to))
        .map((dlv) => `  ${c.system}.${c.id} -[deliver]-> ${dlv.to}${dlv.group ? ` 'group: ${esc(dlv.group)}'` : ''}`),
    )
  }
  for (const [, s] of [...stores.entries()].sort(([a], [b]) => a.localeCompare(b))) {
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
    pushBlock(s.system, B)
  }

  // --- РЕНДЕР: файл на систему — модель и виды ---------------------------
  const systemsDir = join(root, 'model/systems')
  if (systems.length) mkdirSync(systemsDir, { recursive: true })
  for (const s of [...systems].sort((a, b) => a.id.localeCompare(b.id))) {
    const L = [
      `// Файл ведёт генератор (registry/systems.yml + tools/api-source/*.json).`,
      `// Править руками МОЖНО, но регенерация перезапишет: закоммиченные правки`,
      `// вытаскивай обратно через git diff, незакоммиченные — из workspace/_backup/.`,
      `// Вся кухня системы в одном файле: контейнеры, сторы, каналы, связи, виды.`,
      ``,
    ]
    L.push(`model {`)
    L.push(``)
    L.push(`  ${s.id} = ${s.kind} '${esc(s.title)}' {`)
    if (s.description) L.push(`    description '${esc(s.description)}'`)
    for (const block of containerBlocks.get(s.id) ?? []) {
      L.push(``)
      L.push(...block)
    }
    L.push(`  }`)
    const edges = [...(systemEdges.get(s.id) ?? [])].sort()
    if (edges.length) {
      L.push(``)
      L.push(...edges)
    }
    L.push(`}`)

    L.push(``)
    L.push(`views {`)
    L.push(``)
    L.push(`  view ${s.id}_containers of ${s.id} {`)
    L.push(`    title '${esc(s.title)}: контейнеры'`)
    L.push(`    description 'Кто с кем связан. Отсюда видно радиус изменения'`)
    L.push(`    include *`)
    L.push(`    global predicate noContracts`)
    L.push(`    global style base`)
    L.push(`    autoLayout TopBottom`)
    L.push(`  }`)
    for (const v of [...(apiViews.get(s.id) ?? [])].sort((a, b) => a.name.localeCompare(b.name))) {
      const cid = `${s.id}.${v.name}`
      L.push(``)
      L.push(`  view ${s.id}_${v.name}_api of ${cid} {`)
      L.push(`    title '${esc(v.title)}: контракт и потребители'`)
      L.push(`    include *`)
      L.push(`    include ${cid}.api`)
      L.push(`    include ${cid}.api.*`)
      L.push(`    include * -> ${cid}.api.*`)
      L.push(`    global style base`)
      L.push(`    autoLayout LeftRight`)
      L.push(`  }`)
    }
    L.push(`}`)
    L.push(``)
    mkdirSync(join(systemsDir, s.id), { recursive: true })
    const content = L.join('\n')
    backupHandEdits(root, `model/systems/${s.id}/${s.id}.c4`, content)
    writeIfChanged(join(systemsDir, s.id, `${s.id}.c4`), content)
  }
  // Прунинг: системы, исчезнувшие из systems.yml, не оставляют файлов.
  if (existsSync(systemsDir)) {
    for (const dir of readdirSync(systemsDir)) {
      if (knownSystems.has(dir)) continue
      const f = join(systemsDir, dir, `${dir}.c4`)
      if (existsSync(f)) unlinkSync(f)
      try { rmdirSync(join(systemsDir, dir)) } catch { /* каталог не пуст — оставляем человеку */ }
    }
  }

  // --- РЕНДЕР: stub'ы в unknown ----------------------------------------
  const expectedUnknownFiles = new Set()
  if (liveStubs.length || externals.size) mkdirSync(unknownDir, { recursive: true })

  for (const g of liveStubs) {
    const file = `${g.slug}.gen.c4`
    expectedUnknownFiles.add(file)
    const L = header('registry/unresolved.json (stub нераспознанной цели)')
    L.push(`model {`)
    L.push(`  extend unknown {`)
    L.push(``)
    L.push(`    ${g.slug} = service '${esc(g.key)}' {`)
    L.push(`      #stub #inferred`)
    L.push(`      technology 'неизвестно'`)
    L.push(`      metadata {`)
    if (g.hosts.size) L.push(`        hosts '${esc([...g.hosts].sort().join(', '))}'`)
    if (g.feignNames.size) L.push(`        feign-names '${esc([...g.feignNames].sort().join(', '))}'`)
    if (g.urlTemplates.size) L.push(`        url-templates '${esc([...g.urlTemplates].sort().join(', '))}'`)
    L.push(`      }`)
    L.push(``)
    L.push(`      api = api '${esc(g.key)} API' {`)
    L.push(`        #stub #inferred`)
    L.push(`        technology 'HTTP'`)
    for (const e of [...g.endpoints.values()].sort((a, b) => `${a.method} ${a.path}`.localeCompare(`${b.method} ${b.path}`))) {
      L.push(``)
      L.push(`        ${opId(e.method, e.path)} = operation '${esc(e.method)} ${esc(e.path)}' {`)
      L.push(`          #stub #inferred`)
      L.push(`          metadata {`)
      L.push(`            method '${esc(e.method)}'`)
      L.push(`            path '${esc(e.path)}'`)
      L.push(`          }`)
      L.push(`        }`)
    }
    L.push(`      }`)
    L.push(`    }`)
    L.push(`  }`)
    L.push(`}`)
    L.push(``)
    writeIfChanged(join(unknownDir, file), L.join('\n'))
  }

  if (externals.size) {
    const file = `_externals.gen.c4`
    expectedUnknownFiles.add(file)
    const L = header('registry/resolutions.yml (решение: вне компании)')
    L.push(`model {`)
    for (const [id, e] of [...externals.entries()].sort(([a], [b]) => a.localeCompare(b))) {
      L.push(`  ${id} = externalSystem '${esc(e.title)}' {`)
      L.push(`    #stub`)
      if (e.contract) {
        L.push(`    metadata {`)
        L.push(`      contract '${esc(e.contract)}'`)
        L.push(`    }`)
      }
      L.push(`  }`)
    }
    L.push(`}`)
    L.push(``)
    writeIfChanged(join(unknownDir, file), L.join('\n'))
  }

  // Устаревшие stub-файлы удаляются: решённые цели не оставляют мусора.
  if (existsSync(unknownDir)) {
    for (const f of readdirSync(unknownDir).filter((f) => f.endsWith('.gen.c4'))) {
      if (!expectedUnknownFiles.has(f)) unlinkSync(join(unknownDir, f))
    }
  }

  // --- РЕНДЕР: наблюдаемые контейнеры (assign) --------------------------
  const observedDir = join(genDir, 'observed')
  const expectedObservedFiles = new Set()
  if (observedContainers.size) mkdirSync(observedDir, { recursive: true })
  for (const [cid, o] of [...observedContainers.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    const file = `${cid}.gen.c4`
    expectedObservedFiles.add(file)
    const L = header('registry/resolutions.yml (assign: наблюдаемый контейнер чужой системы)')
    L.push(`model {`)
    L.push(`  extend ${o.system} {`)
    L.push(``)
    L.push(`    ${o.name} = service '${esc(o.name)}' {`)
    L.push(`      #stub #inferred`)
    L.push(`      description 'Мы этот сервис не анализируем: api собран из наблюдённых вызовов'`)
    L.push(`      technology 'неизвестно'`)
    L.push(`      metadata {`)
    if (o.hosts.size) L.push(`        hosts '${esc([...o.hosts].sort().join(', '))}'`)
    if (o.feignNames.size) L.push(`        feign-names '${esc([...o.feignNames].sort().join(', '))}'`)
    if (o.urlTemplates.size) L.push(`        url-templates '${esc([...o.urlTemplates].sort().join(', '))}'`)
    L.push(`      }`)
    L.push(``)
    L.push(`      api = api '${esc(o.name)} API' {`)
    L.push(`        #stub #inferred`)
    L.push(`        technology 'HTTP'`)
    for (const e of [...o.endpoints.values()].sort((a, b) => `${a.method} ${a.path}`.localeCompare(`${b.method} ${b.path}`))) {
      L.push(``)
      L.push(`        ${opId(e.method, e.path)} = operation '${esc(e.method)} ${esc(e.path)}' {`)
      L.push(`          #stub #inferred`)
      L.push(`          metadata {`)
      L.push(`            method '${esc(e.method)}'`)
      L.push(`            path '${esc(e.path)}'`)
      L.push(`          }`)
      L.push(`        }`)
    }
    L.push(`      }`)
    L.push(`    }`)
    L.push(`  }`)
    L.push(`}`)
    L.push(``)
    writeIfChanged(join(observedDir, file), L.join('\n'))
  }
  if (existsSync(observedDir)) {
    for (const f of readdirSync(observedDir).filter((f) => f.endsWith('.gen.c4'))) {
      if (!expectedObservedFiles.has(f)) unlinkSync(join(observedDir, f))
    }
  }

  // --- журнал нераспознанного ------------------------------------------
  if (docs.length) {
    const sorted = [...unresolvedEntries].sort((a, b) =>
      `${a.stubId ?? ''} ${a.note ?? ''} ${a.callers[0]?.source ?? ''}`.localeCompare(
        `${b.stubId ?? ''} ${b.note ?? ''} ${b.callers[0]?.source ?? ''}`,
      ),
    )
    mkdirSync(join(root, 'registry'), { recursive: true })
    writeIfChanged(join(root, 'registry/unresolved.json'), JSON.stringify({ unresolved: sorted }, null, 2) + '\n')
  }

  const open = unresolvedEntries.length
  if (open) console.log(`нераспознанных целей: ${open} (registry/unresolved.json, триаж в UI)`)
  return { docs: docs.length, stores: stores.size, channels: channels.size, unresolved: open, stubs: liveStubs.length }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const r = generate('.')
  console.log(`\nv2-доков: ${r.docs}, сторов: ${r.stores}, каналов: ${r.channels}, stub'ов: ${r.stubs}`)
}
