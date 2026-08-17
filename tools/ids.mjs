/**
 * Идентификаторы. Схема id операции — КОНТРАКТ: на сгенерированные id
 * ссылаются рукописные связи. Kotlin-порт (analyzer .../core/Ids.kt) обязан
 * давать те же id; менять только синхронно с ним и с golden-тестами.
 */

/** {параметр} -> _p_, не-алфавитно-цифровое -> _, схлоп _+, срез хвоста, обрезка 80. */
export const opId = (method, path) =>
  (
    method.toLowerCase() +
    path
      .replace(/\{[^}]*\}/g, '_p_')
      .replace(/[^a-zA-Z0-9]+/g, '_')
      .replace(/_+/g, '_')
      .replace(/_$/, '')
  ).slice(0, 80)

/** Слаг для id сторов/каналов: lowercase, [^a-z0-9]+ -> _, без краевых _. */
export const slug = (s) =>
  String(s)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')

export const esc = (s) => String(s).replace(/'/g, "\\'")
