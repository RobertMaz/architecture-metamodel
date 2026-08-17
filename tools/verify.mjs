#!/usr/bin/env node
/**
 * Журнал подтверждений.
 *
 *   node tools/verify.mjs build/model.json <id> --by=имя --against=docs|owner|probe [--note='...']
 *   node tools/verify.mjs build/model.json --list
 *
 * Зачем отдельный журнал, а не поле в .c4:
 *   - подтверждение — это СОБЫТИЕ, а не свойство элемента: важно кто, когда и по чему;
 *   - .gen.c4 перезаписывается генератором и любую ручную правку затрёт;
 *   - журнал append-only, видна история пересмотров.
 *
 * Ключевая механика — hash наблюдаемой поверхности. Подтверждение
 * привязано не к элементу, а к его КОНКРЕТНОМУ состоянию: как только
 * поверхность изменилась, подтверждение автоматически перестаёт действовать.
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { createHash } from 'node:crypto'

const JOURNAL = 'model/verified.json'

// Срок годности зависит от того, ЧЕМ подтверждали.
export const TTL_DAYS = {
  docs: 90, // прочитали чужую документацию — слабое основание
  owner: 180, // владелец системы подтвердил письменно
  probe: 365, // сверено с реальностью: трафик, OpenAPI с прода, контрактный тест
}

/** Поверхность = всё, что мы про элемент УТВЕРЖДАЕМ. Меняется — подтверждение сгорает. */
export function surfaceHash(model, id) {
  const e = model.elements[id]
  if (!e) return null

  const scope = Object.keys(model.elements).filter(
    (x) => x === id || x.startsWith(id + '.'),
  )
  const scopeSet = new Set(scope)

  const skip = new Set(['verified-by', 'verified-at', 'verified-against'])
  const facts = []

  for (const x of scope.sort()) {
    const el = model.elements[x]
    const meta = Object.entries(el.metadata ?? {})
      .filter(([k]) => !skip.has(k))
      .sort()
      .map(([k, v]) => `${k}=${v}`)
      .join(';')
    const tags = (el.tags ?? []).filter((t) => t !== 'inferred' && t !== 'stub').sort()
    facts.push(`E|${x}|${el.kind}|${tags}|${meta}`)
  }

  for (const r of Object.values(model.relations ?? {})) {
    const s = r.source.model ?? r.source
    const d = r.target.model ?? r.target
    if (!scopeSet.has(s) && !scopeSet.has(d)) continue
    facts.push(`R|${s}|${r.kind ?? 'call'}|${d}|${r.title ?? ''}`)
  }

  return createHash('sha256').update(facts.sort().join('\n')).digest('hex').slice(0, 12)
}

export function loadJournal() {
  return existsSync(JOURNAL) ? JSON.parse(readFileSync(JOURNAL, 'utf8')) : { entries: [] }
}

/** Последняя запись по элементу */
export function latest(journal, id) {
  return journal.entries.filter((e) => e.id === id).at(-1) ?? null
}

// --- CLI ---------------------------------------------------------------
if (import.meta.url === `file://${process.argv[1]}`) {
  const [modelFile, ...rest] = process.argv.slice(2)
  const model = JSON.parse(readFileSync(modelFile, 'utf8'))
  const journal = loadJournal()

  if (rest.includes('--list')) {
    for (const e of journal.entries) {
      const now = surfaceHash(model, e.id)
      const state = now !== e.hash ? 'ПОВЕРХНОСТЬ ИЗМЕНИЛАСЬ' : 'актуально'
      console.log(
        `${e.at}  ${e.id.padEnd(34)} ${e.against.padEnd(6)} ${e.by.padEnd(16)} ${state}`,
      )
    }
    process.exit(0)
  }

  const id = rest.find((a) => !a.startsWith('--'))
  const arg = (n) => rest.find((a) => a.startsWith(`--${n}=`))?.split('=').slice(1).join('=')
  const by = arg('by')
  const against = arg('against')

  if (!id || !by || !against || !TTL_DAYS[against]) {
    console.error(
      'использование: verify.mjs <model.json> <id> --by=имя --against=docs|owner|probe [--note=...]',
    )
    process.exit(2)
  }
  const hash = surfaceHash(model, id)
  if (!hash) {
    console.error(`нет такого элемента: ${id}`)
    process.exit(2)
  }

  const entry = {
    id,
    at: new Date().toISOString().slice(0, 10),
    by,
    against,
    hash,
    note: arg('note') ?? '',
  }
  journal.entries.push(entry)
  writeFileSync(JOURNAL, JSON.stringify(journal, null, 2) + '\n')

  const until = new Date(Date.now() + TTL_DAYS[against] * 864e5).toISOString().slice(0, 10)
  console.log(`подтверждено: ${id}`)
  console.log(`  чем:       ${against} (действует до ${until})`)
  console.log(`  поверхность: ${hash}`)
}
