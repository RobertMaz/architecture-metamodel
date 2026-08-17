package arch.analyzer.core

import java.util.SortedMap
import java.util.TreeMap

/**
 * Факт — атомарное наблюдение одной полки. Семь типов — ровно под метамодель.
 * Атрибуты плоские (строка -> строка): это упрощает ключи идентичности,
 * слияние и канонический JSON. source и confidence обязательны у каждого факта.
 */
enum class FactType {
    ENDPOINT,
    OUTGOING_CALL,
    PUBLISH,
    SUBSCRIBE,
    STORE_ACCESS,
    CONTAINER_HINT,
    MESSAGE_SCHEMA,
}

data class Fact(
    val type: FactType,
    val attrs: SortedMap<String, String>,
    val source: String,
    val confidence: Double,
)

fun fact(type: FactType, source: String, confidence: Double, vararg attrs: Pair<String, String>): Fact =
    Fact(type, TreeMap(attrs.toMap()), source, confidence)

data class InputRef(
    val kind: String,
    val path: String,
    val commit: String? = null,
)

data class Evidence(
    val lane: String,
    val input: InputRef,
    val facts: List<Fact>,
) {
    /** Канонический порядок фактов — детерминизм не зависит от порядка обхода файлов. */
    fun canonical(): Evidence =
        copy(facts = facts.sortedWith(compareBy({ it.type.name }, { it.attrs.toString() }, { it.source })))
}
