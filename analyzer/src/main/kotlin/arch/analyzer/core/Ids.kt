package arch.analyzer.core

/**
 * Идентификаторы. Схема id операции — КОНТРАКТ (см. CONTRACTS.md и tools/gen-api.mjs):
 * на сгенерированные id ссылаются рукописные связи. Порт обязан быть побайтово
 * совместим с JS-реализацией; менять только синхронно с ней и с golden-тестами.
 */

/** {параметр} -> _p_, не-алфавитно-цифровое -> _, схлоп _+, срез хвостового _, обрезка 80. */
fun opId(method: String, path: String): String =
    (method.lowercase() + path
        .replace(Regex("\\{[^}]*}"), "_p_")
        .replace(Regex("[^a-zA-Z0-9]+"), "_")
        .replace(Regex("_+"), "_")
        .replace(Regex("_$"), ""))
        .take(80)

/** Ключ идентичности пути: имя параметра не различает эндпоинты. */
fun normPath(path: String): String = path.replace(Regex("\\{[^}]*}"), "{_p_}")

/** Слаг для id сторов/каналов: lowercase, [^a-z0-9]+ -> _, без краевых _. */
fun slug(s: String): String =
    s.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
