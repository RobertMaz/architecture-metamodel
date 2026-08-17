#!/usr/bin/env node
/**
 * Владелец системы берётся из CODEOWNERS, а не из модели.
 *
 * Почему: поле owner в модели протухает молча — никто не замечает,
 * что команды уже нет. CODEOWNERS протухнуть не может: он назначает
 * ревьюеров, и как только он врёт, PR уходит не тем людям и это чинят
 * в тот же день. Владение живёт там, где у него есть зубы.
 *
 *   node tools/owners.mjs            # показать карту
 *   import { ownerOf } from './owners.mjs'
 */

import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join } from 'node:path'

const CODEOWNERS = ['CODEOWNERS', '.github/CODEOWNERS', 'docs/CODEOWNERS'].find(existsSync)

/** Правила CODEOWNERS: побеждает последнее совпавшее (семантика git). */
function rules() {
  if (!CODEOWNERS) return []
  return readFileSync(CODEOWNERS, 'utf8')
    .split('\n')
    .map((l) => l.replace(/#.*/, '').trim())
    .filter(Boolean)
    .map((l) => {
      const [pattern, ...owners] = l.split(/\s+/)
      return { pattern, owners }
    })
}

function matches(pattern, path) {
  const p = pattern.replace(/^\//, '')
  const rx = new RegExp(
    '^' +
      p
        .replace(/[.+^${}()|[\]\\]/g, '\\$&')
        .replace(/\*\*/g, '\u0000')
        .replace(/\*/g, '[^/]*')
        .replace(/\u0000/g, '.*') +
      (p.endsWith('/') ? '.*' : '(/.*)?') +
      '$',
  )
  return rx.test(path)
}

/** id системы -> файл, в котором она объявлена */
export function systemFiles(dir = 'model') {
  const map = new Map()
  const walk = (d) => {
    for (const f of readdirSync(d, { withFileTypes: true })) {
      const path = join(d, f.name)
      if (f.isDirectory()) walk(path)
      else if (f.name.endsWith('.c4')) {
        const src = readFileSync(path, 'utf8')
        for (const m of src.matchAll(
          /^\s*(\w+)\s*=\s*(system|orgSystem|externalSystem)\b/gm,
        )) {
          map.set(m[1], path)
        }
      }
    }
  }
  walk(dir)
  return map
}

const files = systemFiles()
const ruleset = rules()

export function ownerOfFile(path) {
  let owners = null
  for (const r of ruleset) if (matches(r.pattern, path)) owners = r.owners
  return owners ? owners.join(', ') : null
}

/** Владелец любого элемента = владелец файла, где объявлена его система */
export function ownerOf(id) {
  const root = String(id).split('.')[0]
  const file = files.get(root)
  return file ? ownerOfFile(file) : null
}

if (import.meta.url === `file://${process.argv[1]}`) {
  if (!CODEOWNERS) {
    console.error('CODEOWNERS не найден')
    process.exit(1)
  }
  console.log(`источник: ${CODEOWNERS}\n`)
  for (const [id, file] of files) {
    console.log(`${id.padEnd(14)} ${file.padEnd(24)} ${ownerOfFile(file) ?? 'НЕТ ВЛАДЕЛЬЦА'}`)
  }
}
