/**
 * П. 10 плана: корень ДАННЫХ (model/, registry/, tools/api-source/, workspace/,
 * CODEOWNERS) — параметр. По умолчанию текущий репозиторий — обратная
 * совместимость; приватный ландшафт задаёт ARCH_DATA_ROOT (см. docs/data-root.md).
 */
import { join } from 'node:path'

export const dataRoot = process.env.ARCH_DATA_ROOT ?? '.'
export const dataPath = (...segments) => join(dataRoot, ...segments)
