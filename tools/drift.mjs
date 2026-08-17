#!/usr/bin/env node
/**
 * Дрейф-детектор: единственная защита от «модель врёт».
 *
 *   node tools/drift.mjs build/model.json tools/live-services.txt
 *
 * Второй аргумент — список того, что реально крутится. Откуда брать:
 *   kubectl get deploy -A -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
 *   argocd app list -o name
 *   kafka-topics --list
 *   aws s3 ls
 *
 * Сопоставление по metadata.deployment, иначе по id, иначе по title.
 * Начинать стоит в режиме предупреждений: сначала модель догоняет
 * реальность, и только потом проверка становится блокирующей.
 */

import { readFileSync } from 'node:fs'

const [modelFile, liveFile] = process.argv.slice(2)
const m = JSON.parse(readFileSync(modelFile, 'utf8'))

const live = new Set(
  readFileSync(liveFile, 'utf8')
    .split('\n')
    .map((s) => s.trim())
    .filter((s) => s && !s.startsWith('#')),
)

const RUNTIME = new Set(['service', 'worker', 'store', 'channel'])
const modelled = new Map()

for (const e of Object.values(m.elements ?? {})) {
  if (!RUNTIME.has(e.kind)) continue
  const name = e.metadata?.deployment ?? e.id.split('.').pop() ?? e.id
  modelled.set(name, e)
  if (e.title !== name) modelled.set(e.title, e)
}

const missing = [...live].filter((n) => !modelled.has(n))
const stale = [...new Set(modelled.values())].filter((e) => {
  const name = e.metadata?.deployment ?? e.id.split('.').pop()
  return !live.has(name) && !live.has(e.title)
})

for (const n of missing) {
  console.log(`DRIFT  живёт, но не описано:   ${n}`)
}
for (const e of stale) {
  console.log(`DRIFT  описано, но не живёт:   ${e.id}  (${e.title})`)
}

console.log(
  `\n${live.size} живых, ${new Set(modelled.values()).size} в модели, ` +
    `${missing.length} не описано, ${stale.length} лишних`,
)

process.exit(missing.length + stale.length > 0 ? 1 : 0)
