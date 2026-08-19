package arch.analyzer.llm

import arch.analyzer.core.Evidence
import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.Json
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import arch.analyzer.core.fact
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Сигналы коммуникации, ради которых зовём LLM, если другие полки спасовали. */
private val SUSPICIOUS = Regex("RestTemplate|WebClient|RestClient|OkHttpClient|KafkaTemplate|RabbitTemplate")

private const val SYSTEM_PROMPT =
    "Ты — статический анализатор Java. Извлеки из файла ИСХОДЯЩИЕ вызовы (HTTP) и публикации " +
        "в топики. Отвечай ТОЛЬКО валидным JSON без markdown: " +
        """{"calls":[{"method":"GET","path":"/x","host":"a","urlTemplate":"http://a/x","line":1}],""" +
        """"publishes":[{"channel":"t","schema":"Dto","line":1}]}. """ +
        "Поля host/urlTemplate/path — только если реально видны в коде; ничего не выдумывай, " +
        "не уверен — не включай."

/**
 * Полка llm, режим fallback: только точки внимания — файлы, где видны http/kafka-клиенты,
 * но другие полки не извлекли ни одного исходящего факта. Всё с confidence <= 0.7.
 * Невалидный JSON: одна повторная попытка, затем скип — фактов не выдумываем.
 */
class LlmLane(
    private val archRoot: Path,
    private val client: LlmClient?,
    private val enrich: Boolean = false,
) : Lane {

    override val name = "llm"

    val failures = mutableListOf<String>()

    private val json = ObjectMapper()

    override fun applicable(input: RepoInput): Boolean =
        client != null && input.repoDir.resolve("src/main/java").exists()

    override fun extract(input: RepoInput): List<Fact> {
        val llm = client ?: return emptyList()
        val covered = coveredFiles(input.containerId)
        val facts = mutableListOf<Fact>()

        for (file in attentionFiles(input, covered)) {
            val rel = input.repoDir.relativize(file).toString().replace('\\', '/')
            val content = file.readText().take(12_000)
            val node = strictJson(llm, "Файл $rel:\n\n$content") ?: run {
                failures += "$rel: LLM не вернул валидный JSON"
                return@run null
            } ?: continue

            for (c in node.path("calls")) {
                val attrs = mutableListOf<Pair<String, String>>()
                c.get("method")?.asText()?.takeIf { it.isNotEmpty() }?.let { attrs += "method" to it }
                c.get("path")?.asText()?.takeIf { it.isNotEmpty() }?.let { attrs += "path" to it }
                c.get("host")?.asText()?.takeIf { it.isNotEmpty() }?.let { attrs += "host" to it }
                c.get("urlTemplate")?.asText()?.takeIf { it.isNotEmpty() }?.let { attrs += "urlTemplate" to it }
                if (attrs.isEmpty()) continue
                facts += fact(FactType.OUTGOING_CALL, "$rel#L${c.path("line").asInt(0)}", 0.65, *attrs.toTypedArray())
            }
            for (p in node.path("publishes")) {
                val channel = p.get("channel")?.asText()?.takeIf { it.isNotEmpty() } ?: continue
                val attrs = mutableListOf("channel" to channel)
                p.get("schema")?.asText()?.takeIf { it.isNotEmpty() }?.let { attrs += "schema" to it }
                facts += fact(FactType.PUBLISH, "$rel#L${p.path("line").asInt(0)}", 0.65, *attrs.toTypedArray())
            }
        }

        if (enrich) facts += enrichFacts(llm, input)
        return facts
    }

    /** Файлы с относительными путями, откуда другие полки уже извлекли исходящее. */
    private fun coveredFiles(containerId: String): Set<String> {
        val ws = archRoot.resolve("workspace/$containerId")
        if (!ws.exists()) return emptySet()
        val covered = mutableSetOf<String>()
        Files.list(ws).use { s ->
            s.filter { it.fileName.toString().matches(Regex("evidence\\.(source|lst|bytecode|config)\\.json")) }
                .sorted()
                .forEach { f ->
                    val ev = Json.read(f.readText(), Evidence::class.java)
                    for (fa in ev.facts) {
                        if (fa.type == FactType.OUTGOING_CALL || fa.type == FactType.PUBLISH) {
                            covered += fa.source.substringBefore('#')
                        }
                    }
                }
        }
        return covered
    }

    private fun attentionFiles(input: RepoInput, covered: Set<String>): List<Path> {
        val src = input.repoDir.resolve("src/main/java")
        if (!src.exists()) return emptyList()
        return Files.walk(src).use { s ->
            s.filter { it.isRegularFile() && it.extension == "java" }.sorted().toList()
        }.filter { file ->
            val rel = input.repoDir.relativize(file).toString().replace('\\', '/')
            rel !in covered && SUSPICIOUS.containsMatchIn(file.readText())
        }
    }

    /** Строгий JSON: одна повторная попытка, затем null. */
    fun strictJson(llm: LlmClient, user: String): JsonNode? {
        repeat(2) { attempt ->
            val raw = llm.complete(SYSTEM_PROMPT, if (attempt == 0) user else "$user\n\nВерни ТОЛЬКО валидный JSON.")
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            runCatching { return json.readTree(cleaned).takeIf { it.isObject } }
        }
        return null
    }

    /** Осмысление: summary эндпоинтов и title контейнера — тоже факты. */
    private fun enrichFacts(llm: LlmClient, input: RepoInput): List<Fact> {
        val ws = archRoot.resolve("workspace/${input.containerId}/evidence.source.json")
        if (!ws.exists()) return emptyList()
        val endpoints = Json.read(ws.readText(), Evidence::class.java).facts
            .filter { it.type == FactType.ENDPOINT }
            .map { "${it.attrs["method"]} ${it.attrs["path"]}" }
            .sorted()
        if (endpoints.isEmpty()) return emptyList()

        val node = strictJsonEnrich(llm, endpoints, input.containerId) ?: return emptyList()
        val facts = mutableListOf<Fact>()
        for (s in node.path("summaries")) {
            val method = s.get("method")?.asText() ?: continue
            val path = s.get("path")?.asText() ?: continue
            val summary = s.get("summary")?.asText()?.takeIf { it.isNotEmpty() } ?: continue
            facts += fact(
                FactType.ENDPOINT, "llm:enrich", 0.6,
                "method" to method, "path" to path, "summary" to summary,
            )
        }
        node.get("description")?.asText()?.takeIf { it.isNotEmpty() }?.let {
            facts += fact(FactType.CONTAINER_HINT, "llm:enrich", 0.6, "description" to it)
        }
        return facts
    }

    private fun strictJsonEnrich(llm: LlmClient, endpoints: List<String>, containerId: String): JsonNode? {
        val system =
            "Ты пишешь краткие русские описания API. Отвечай ТОЛЬКО валидным JSON: " +
                """{"summaries":[{"method":"GET","path":"/x","summary":"..."}],"description":"..."}"""
        repeat(2) { attempt ->
            val user = "Сервис $containerId, эндпоинты:\n${endpoints.joinToString("\n")}" +
                if (attempt == 0) "" else "\n\nВерни ТОЛЬКО валидный JSON."
            val raw = llm.complete(system, user)
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            runCatching { return json.readTree(cleaned).takeIf { it.isObject } }
        }
        failures += "$containerId: enrich — LLM не вернул валидный JSON"
        return null
    }
}
