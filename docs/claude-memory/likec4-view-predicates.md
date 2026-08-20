---
name: likec4-view-predicates
description: "Семантика include в LikeC4-видах — элементный include не тащит связи потомков, нужен реляционный предикат"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 196faca2-ed5e-40fa-b6fc-c483fcf1aaee
  modified: 2026-08-19T15:57:25.904Z
---

Доки: https://likec4.dev/dsl/views/predicates/

Грабли, проверенные на практике (2026-08-19, соседи на container view в [[arch-analyzer-project]]):

- `include <элемент>` тащит только связи **самого** элемента с уже видимыми; связи его потомков (наши рёбра ведут в `sys.container.api.op`) — нет. Узел появляется голым.
- Рёбра к вложенным целям втягивает только реляционный предикат: `include <sys> <-> <сосед>` (включает оба конца как элементы, свёрнутость не ломает; склейка нескольких рёбер на видимых предках — автоматика).
- Порядок предикатов значим: сначала элементы, потом реляционные, exclude/global — после.
- В scoped view (`view of X`) `*` = X + прямые дети; `.**` = потомки со связями с видимыми; `._` = дети-только-со-связями.
- Папки навигации: параметр блока `views 'Имя' {` + слэши в title.

Реализация: `tools/gen-model.mjs`, `neighborsOf` + генерация `include`-пар в виде `<sys>_containers`.
