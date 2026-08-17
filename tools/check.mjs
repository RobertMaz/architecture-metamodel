#!/usr/bin/env node
/**
 * Инварианты метамодели. Правила живут здесь, а не в README,
 * потому что README никто не соблюдает, а красный CI — соблюдают.
 *
 *   node tools/check.mjs model.json
 *
 * Выход 1, если есть ошибки. Предупреждения сборку не роняют.
 */

import { readFileSync } from 'node:fs'
import { surfaceHash, loadJournal, latest, TTL_DAYS } from './verify.mjs'
import { ownerOf } from './owners.mjs'

const file = process.argv[2] ?? 'model.json'
const m = JSON.parse(readFileSync(file, 'utf8'))

const elements = Object.values(m.elements ?? {})
const relations = Object.values(m.relations ?? {})
const byId = new Map(elements.map((e) => [e.id, e]))

const connectedIn = new Set()
const errors = []
const warnings = []
const today = new Date().toISOString().slice(0, 10)

const err = (id, msg) => errors.push({ id, msg })
const warn = (id, msg) => warnings.push({ id, msg })

const kindOf = (id) => byId.get(id)?.kind
const tagsOf = (id) => byId.get(id)?.tags ?? []
const systemOf = (id) => id.split('.')[0]
const rels = relations.map((r) => ({
  src: r.source.model ?? r.source,
  dst: r.target.model ?? r.target,
  kind: r.kind ?? 'call',
  title: r.title ?? '',
}))
const label = (r) => `${r.src} -[${r.kind}]-> ${r.dst}`
const parentOf = (id) => (id.includes('.') ? id.slice(0, id.lastIndexOf('.')) : null)
for (const r of rels) connectedIn.add(r.dst)

// Контейнеры — всё, кроме людей, систем и импортов
const CONTAINERS = new Set(['client', 'service', 'worker', 'store', 'channel'])
const SYSTEM_KINDS = new Set(['system', 'orgSystem', 'externalSystem'])
const containers = elements.filter((e) => CONTAINERS.has(e.kind))

// --- R1: у каждой системы есть владелец в CODEOWNERS --------------------
// Owner в модели больше не хранится: он дублировал бы CODEOWNERS
// и протухал бы молча. Здесь протухнуть нечему — источник один.
for (const e of elements) {
  if (!SYSTEM_KINDS.has(e.kind)) continue
  if (!ownerOf(e.id)) {
    err(e.id, 'нет записи в CODEOWNERS — непонятно, кто ревьюит и к кому идти')
  }
}

// --- R1b: владение не дублируется в модели -----------------------------
for (const e of elements) {
  if (e.metadata?.owner) {
    err(e.id, 'metadata.owner дублирует CODEOWNERS — убрать, иначе разъедется')
  }
}

// --- R2: клиент не может быть целью связи ------------------------------
for (const r of rels) {
  if (kindOf(r.dst) === 'client' && kindOf(r.src) !== 'person') {
    err(label(r), 'клиент не может быть целью: у него нет входящего интерфейса')
  }
}

// --- R3: воркер не принимает синхронные вызовы -------------------------
for (const r of rels) {
  if (r.kind === 'call' && kindOf(r.dst) === 'worker') {
    err(label(r), 'у воркера нет публичного API — это service или лишняя связь')
  }
}

// --- R4: хранилище ничего не инициирует --------------------------------
for (const r of rels) {
  if (kindOf(r.src) === 'store') {
    err(label(r), 'store не инициирует связи — переверни стрелку')
  }
  if (kindOf(r.dst) === 'store' && !['read', 'write'].includes(r.kind)) {
    err(label(r), `в store ведут только read/write, а не ${r.kind}`)
  }
}

// --- R5: сообщения ходят только через каналы ---------------------------
for (const r of rels) {
  if (r.kind === 'publish' && kindOf(r.dst) !== 'channel') {
    err(label(r), 'publish ведёт только в channel')
  }
  if (r.kind === 'deliver' && kindOf(r.src) !== 'channel') {
    err(label(r), 'deliver исходит только из channel')
  }
  if (kindOf(r.dst) === 'channel' && r.kind !== 'publish') {
    err(label(r), `в канал ведёт только publish, а не ${r.kind}`)
  }
  if (kindOf(r.src) === 'channel' && r.kind !== 'deliver') {
    err(label(r), `из канала исходит только deliver, а не ${r.kind}`)
  }
}

// --- R6: у хранилища ровно один писатель (shared database) -------------
const writers = new Map()
for (const r of rels) {
  if (r.kind !== 'write') continue
  if (!writers.has(r.dst)) writers.set(r.dst, new Set())
  writers.get(r.dst).add(r.src)
}
for (const e of containers) {
  if (e.kind !== 'store') continue
  const w = writers.get(e.id)
  if (!w || w.size === 0) {
    warn(e.id, 'в хранилище никто не пишет — оно ещё живое?')
  } else if (w.size > 1) {
    err(e.id, `пишут несколько сервисов (${[...w].join(', ')}) — shared database`)
  }
}

// --- R7: границу системы пересекают только элементы с #public ----------
for (const r of rels) {
  const a = systemOf(r.src)
  const b = systemOf(r.dst)
  if (a === b) continue
  if (kindOf(r.src) === 'person') continue
  const dstIsPublic = tagsOf(r.dst).includes('public')
  const dstSystemIsPublic = tagsOf(b).includes('public')
  if (!dstIsPublic && !dstSystemIsPublic) {
    err(label(r), `вход в чужую систему мимо #public — ${r.dst} не публичный`)
  }
}

// --- R8: реконструкции и их подтверждения -------------------------------
// #stub и #inferred — это ПРОИСХОЖДЕНИЕ: «мы это восстановили, а не узнали».
// Тег снимается, только когда меняется источник (соседи завели свою модель),
// а не когда кто-то один раз сверился. Подтверждение живёт в журнале
// и привязано к конкретному состоянию поверхности.
const journal = loadJournal()

const isReconstruction = (id) => {
  const t = byId.get(id)?.tags ?? []
  return t.includes('stub') || t.includes('inferred')
}

// Подтверждают КОРЕНЬ реконструкции, а не каждый эндпоинт по отдельности.
// Хэш поверхности считается вместе с потомками, поэтому подтверждение
// api автоматически покрывает все его операции.
const roots = elements.filter((e) => {
  if (!isReconstruction(e.id)) return false
  let p = parentOf(e.id)
  while (p) {
    if (isReconstruction(p)) return false
    p = parentOf(p)
  }
  return true
})

for (const e of roots) {
  const v = latest(journal, e.id)

  if (!v) {
    warn(e.id, 'реконструкция без подтверждения — никто не сверял с реальностью')
    continue
  }

  const expires = new Date(
    new Date(v.at).getTime() + TTL_DAYS[v.against] * 864e5,
  )
    .toISOString()
    .slice(0, 10)

  if (surfaceHash(m, e.id) !== v.hash) {
    warn(
      e.id,
      `поверхность изменилась после подтверждения (${v.at}, ${v.by}) — переподтвердить`,
    )
  } else if (expires < today) {
    warn(e.id, `подтверждение протухло ${expires} (${v.against}, ${v.by})`)
  }
}

// --- R8b: слабое подтверждение там, где цена ошибки высокая -------------
for (const e of elements) {
  if (!(e.tags ?? []).includes('stub')) continue
  const v = latest(journal, e.id)
  if (!v || v.against !== 'docs') continue
  const critical = rels.some(
    (r) => (r.src === e.id || r.dst === e.id) && tagsOf(r.src) .includes('public'),
  )
  if (critical) {
    warn(e.id, 'подтверждено только по документации — для боевого пути нужен probe')
  }
}

// --- C1: контракт лежит там, где положено -----------------------------

for (const e of elements) {
  if (e.kind === 'api' && !['service', 'worker'].includes(kindOf(parentOf(e.id)))) {
    err(e.id, 'api может висеть только на service или worker')
  }
  if (e.kind === 'operation' && kindOf(parentOf(e.id)) !== 'api') {
    err(e.id, 'operation живёт только внутри api')
  }
  if (e.kind === 'message' && kindOf(parentOf(e.id)) !== 'channel') {
    err(e.id, 'message живёт только внутри channel')
  }
}

// --- C2: если контракт описан, звонить надо в него ---------------------
// Это и делает модель полезной: «кто сломается» становится запросом к графу.
const hasApi = new Set(
  elements.filter((e) => e.kind === 'api').map((e) => parentOf(e.id)),
)
for (const r of rels) {
  if (r.kind !== 'call') continue
  if (hasApi.has(r.dst)) {
    err(label(r), 'у контейнера описан контракт — вызов должен вести в operation')
  }
}

// --- C3: у эндпоинта есть метод и путь ---------------------------------
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']
const sigs = new Map()
for (const e of elements) {
  if (e.kind !== 'operation') continue
  const { method, path } = e.metadata ?? {}
  if (!method || !path) {
    err(e.id, 'нет method или path — эндпоинт без сигнатуры бесполезен')
    continue
  }
  if (!METHODS.includes(method)) err(e.id, `неизвестный HTTP-метод: ${method}`)

  // C4: дубли method+path внутри одного api
  const key = `${parentOf(e.id)} ${method} ${path}`
  if (sigs.has(key)) err(e.id, `дубль сигнатуры с ${sigs.get(key)}`)
  else sigs.set(key, e.id)
}

// --- C5: жизненный цикл эндпоинта --------------------------------------
for (const r of rels) {
  if (kindOf(r.dst) !== 'operation') continue
  const dst = byId.get(r.dst)
  const sunset = dst.metadata?.sunset
  if (sunset && sunset < today) {
    err(label(r), `вызов эндпоинта, выключенного ${sunset}`)
  } else if ((dst.tags ?? []).includes('deprecated')) {
    warn(label(r), `вызов deprecated-эндпоинта${sunset ? `, выключаем ${sunset}` : ''}`)
  }
}

// --- C6: эндпоинты без известных потребителей --------------------------
for (const e of elements) {
  if (e.kind !== 'operation') continue
  if (connectedIn.has(e.id)) continue
  if ((tagsOf(parentOf(e.id)) ?? []).includes('public')) continue
  warn(e.id, 'нет ни одного известного потребителя — кандидат на удаление')
}

// --- C7: качество извлечения -------------------------------------------
const verifiedCovering = (id) => {
  let cur = id
  while (cur) {
    const v = latest(journal, cur)
    if (v && surfaceHash(m, cur) === v.hash) return v
    cur = parentOf(cur)
  }
  return null
}

for (const e of elements) {
  if (!(e.tags ?? []).includes('inferred')) continue
  if (verifiedCovering(e.id)) continue // человек уже смотрел этот API целиком
  const c = Number(e.metadata?.confidence ?? 1)
  if (c < 0.8) {
    warn(e.id, `анализатор не уверен (confidence ${c}) — нужен глазами`)
  }
}

// =====================================================================
// КРУГИ ВЛАДЕНИЯ. Смысл разделения не в цвете, а в РАЗНОЙ ГЛУБИНЕ:
// своё описываем до эндпоинтов, соседей — до контейнеров, которые
// реально трогаем, внешнее — одним узлом.
// =====================================================================
const systems = elements.filter((e) => SYSTEM_KINDS.has(e.kind))
const TIER = { system: 'product', orgSystem: 'ecosystem', externalSystem: 'external' }
const tierOf = (id) => TIER[byId.get(id.split('.')[0])?.kind] ?? null
const inScope = (id) => id.split('.')[0]
const ourSystems = new Set(
  systems.filter((s) => s.kind === 'system').map((s) => s.id),
)

for (const s of systems) {
  const tier = tierOf(s.id)
  const children = elements.filter((e) => e.id.startsWith(s.id + '.'))

  if (tier === 'external') {
    // --- T2: внешнее — чёрный ящик ---
    if (children.length) {
      err(s.id, `внешняя система описана изнутри (${children.length} элементов) — это чёрный ящик`)
    }
    // --- T3: у внешнего должен быть договор и владелец интеграции ---
    if (!s.metadata?.contract) {
      warn(s.id, 'нет ссылки на договор/SLA — при инциденте искать будет некогда')
    }
    // --- T4: внешнее без #stub врёт молча ---
    if (!(s.tags ?? []).includes('stub')) {
      warn(s.id, 'внешняя система без #stub — мы описываем её по чужим словам')
    }
  }

  if (tier === 'ecosystem') {
    // --- T5: у соседей описываем только то, что реально трогаем ---
    for (const c of children) {
      const used = rels.some(
        (r) =>
          (r.src === c.id && ourSystems.has(inScope(r.dst))) ||
          (r.dst === c.id && ourSystems.has(inScope(r.src))),
      )
      const hasUsedChild = children.some(
        (x) =>
          x.id.startsWith(c.id + '.') &&
          rels.some((r) => r.src === x.id || r.dst === x.id),
      )
      if (!used && !hasUsedChild) {
        err(c.id, 'чужой элемент, которым мы не пользуемся — не наша зона, удалить')
      }
    }
    // --- T6: контракт соседей лучше импортировать, чем перерисовывать ---
    if (children.some((c) => (c.tags ?? []).includes('inferred'))) {
      warn(s.id, 'контракт соседей извлечён нами — попроси у них модель и импортируй')
    }
  }
}

// --- T7: в чужие данные не лазят ---------------------------------------
for (const r of rels) {
  const from = tierOf(r.src)
  const to = tierOf(r.dst)
  if (from === 'product' && to && to !== 'product' && ['read', 'write'].includes(r.kind)) {
    err(label(r), 'прямой доступ в чужое хранилище — только через их публичный контракт')
  }
}

// --- R9: элементы без единой связи -------------------------------------
const connected = new Set(rels.flatMap((r) => [r.src, r.dst]))
for (const e of containers) {
  const hasChild = elements.some((x) => x.id.startsWith(e.id + '.'))
  if (!connected.has(e.id) && !hasChild) {
    warn(e.id, 'ни одной связи — забыли описать или элемент мёртв')
  }
}

// --- R10: ссылки на репозиторий ----------------------------------------
for (const e of containers) {
  if (e.kind === 'store' || e.kind === 'channel') continue
  if (!(e.links ?? []).some((l) => /github|gitlab/i.test(l.url))) {
    warn(e.id, 'нет ссылки на репозиторий')
  }
}

// ------------------------------------------------------------------
const pad = (s) => String(s).padEnd(34)
for (const w of warnings) console.log(`WARN  ${pad(w.id)} ${w.msg}`)
for (const e of errors) console.log(`ERROR ${pad(e.id)} ${e.msg}`)

console.log(
  `\n${elements.length} элементов, ${rels.length} связей, ` +
    `${errors.length} ошибок, ${warnings.length} предупреждений`,
)

process.exit(errors.length > 0 ? 1 : 0)
