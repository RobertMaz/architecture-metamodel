package arch.analyzer.llm

import arch.analyzer.core.ApiSourceDoc
import arch.analyzer.core.Json
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Ревьюер: сверяет итоговый док с деревом файлов и называет подозрения на пропуски.
 * Выход идёт ТОЛЬКО в отчёт (reconcile-report.llmReview) — факты он не правит.
 */
class LlmReviewer(private val client: LlmClient) {

    private val json = ObjectMapper()

    private val system =
        "Ты — ревьюер результатов статического анализа Java-сервиса. Тебе дают итоговую " +
            "модель (эндпоинты, вызовы, топики, сторы) и список файлов репозитория. Назови " +
            "ПОДОЗРЕНИЯ на пропуски: файлы, чьи имена намекают на коммуникацию, не отражённую " +
            "в модели. Отвечай ТОЛЬКО валидным JSON: {\"suspicions\":[\"...\"]}. Нет подозрений — пустой список."

    fun review(doc: ApiSourceDoc, files: List<String>): List<String> {
        val user = "Модель:\n${Json.write(doc)}\nФайлы:\n${files.sorted().joinToString("\n")}"
        repeat(2) { attempt ->
            val raw = client.complete(system, if (attempt == 0) user else "$user\n\nВерни ТОЛЬКО валидный JSON.")
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            runCatching {
                val node = json.readTree(cleaned)
                return node.path("suspicions").mapNotNull { it.asText().takeIf(String::isNotEmpty) }.sorted()
            }
        }
        return listOf("LLM-ревью не удалось: невалидный JSON")
    }
}
