package arch.analyzer.server

import arch.analyzer.core.Registry
import arch.analyzer.llm.LlmClient
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Гипотезы имён для stub'ов: LLM смотрит на сигнатуру цели, наблюдённые эндпоинты
 * и список известных контейнеров. Это ПОДСКАЗКА для триажа — решение остаётся за человеком.
 */

data class Hypothesis(
    val name: String,
    val container: String? = null,
    val confidence: Double,
)

class Hypotheses(private val archRoot: Path, private val llm: LlmClient?) {

    private val json = ObjectMapper()

    private val system =
        "Ты помогаешь опознать сервис по следам его вызовов. Дана сигнатура неизвестной цели " +
            "(hosts/feign/url), наблюдённые эндпоинты и список известных контейнеров. Предложи " +
            "до 3 гипотез. Отвечай ТОЛЬКО валидным JSON: " +
            "{\"hypotheses\":[{\"name\":\"человеческое имя\",\"container\":\"id из списка или null\",\"confidence\":0.5}]}. " +
            "container указывай ТОЛЬКО если он есть в списке известных."

    fun configured(): Boolean = llm != null

    fun forStub(stubId: String): List<Hypothesis>? {
        val client = llm ?: return null
        val file = archRoot.resolve("registry/unresolved.json")
        if (!file.exists()) return null
        val entry = json.readTree(file.readText()).path("unresolved")
            .firstOrNull { it.path("stubId").asText() == stubId } ?: return null

        val known = Registry(archRoot).repos().keys.sorted()
        val user = "Цель: ${entry.toPrettyString()}\nИзвестные контейнеры:\n${known.joinToString("\n")}"

        repeat(2) { attempt ->
            val raw = client.complete(system, if (attempt == 0) user else "$user\n\nВерни ТОЛЬКО валидный JSON.")
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            runCatching {
                val node = json.readTree(cleaned)
                return node.path("hypotheses").mapNotNull { h ->
                    val name = h.get("name")?.asText()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    val container = h.get("container")?.asText()?.takeIf { it in known }
                    Hypothesis(name, container, minOf(h.path("confidence").asDouble(0.5), 0.7))
                }
            }
        }
        return emptyList()
    }
}
