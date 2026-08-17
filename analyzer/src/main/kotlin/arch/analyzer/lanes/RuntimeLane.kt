package arch.analyzer.lanes

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import arch.analyzer.core.fact
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Полка runtime — наивысший приоритет достоверности: живое приложение не врёт.
 * Две под-части, каждая работает при наличии своего входа:
 *  - Actuator: /actuator/mappings (реальные роуты), /actuator/env (datasource, имя);
 *  - OTel: файл спанов (OTLP-JSON или JSON-lines) — реальные исходящие вызовы
 *    с уже отрезолвленными адресами, Kafka и БД.
 * Недоступный рантайм — не ошибка: полка просто не применима.
 */
class RuntimeLane : Lane {

    override val name = "runtime"

    private val json = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    override fun applicable(input: RepoInput): Boolean =
        (input.traces?.exists() == true) || (input.runtimeUrl != null && alive(input.runtimeUrl))

    override fun extract(input: RepoInput): List<Fact> {
        val facts = mutableListOf<Fact>()
        input.runtimeUrl?.takeIf { alive(it) }?.let { facts += actuatorFacts(it) }
        input.traces?.takeIf { it.exists() }?.let { facts += otelFacts(it) }
        return facts
    }

    // --- Actuator ---------------------------------------------------------

    private fun alive(baseUrl: String): Boolean = get(baseUrl, "/actuator/health") != null

    private fun get(baseUrl: String, path: String): JsonNode? = runCatching {
        val rq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.removeSuffix("/") + path))
            .timeout(Duration.ofSeconds(5))
            .GET().build()
        val rs = http.send(rq, HttpResponse.BodyHandlers.ofString())
        if (rs.statusCode() in 200..299) json.readTree(rs.body()) else null
    }.getOrNull()

    private fun actuatorFacts(baseUrl: String): List<Fact> {
        val facts = mutableListOf<Fact>()

        get(baseUrl, "/actuator/mappings")?.let { root ->
            for (ctx in root.path("contexts")) {
                val servlets = ctx.path("mappings").path("dispatcherServlets")
                for (servlet in servlets) {
                    for (m in servlet) {
                        val cond = m.path("details").path("requestMappingConditions")
                        if (cond.isMissingNode || cond.isNull) continue
                        val methods = cond.path("methods").mapNotNull { it.asText().takeIf(String::isNotEmpty) }
                        val patterns = cond.path("patterns").mapNotNull { it.asText().takeIf(String::isNotEmpty) }
                        for (method in methods.ifEmpty { listOf("GET") }) {
                            for (p in patterns) {
                                if (p.startsWith("/actuator") || p == "/error" || p.startsWith("/error/")) continue
                                facts += fact(
                                    FactType.ENDPOINT, "actuator:/mappings", 0.97,
                                    "method" to method, "path" to p,
                                )
                            }
                        }
                    }
                }
            }
        }

        get(baseUrl, "/actuator/env")?.let { env ->
            fun prop(name: String): String? {
                for (ps in env.path("propertySources")) {
                    val v = ps.path("properties").path(name).path("value")
                    if (!v.isMissingNode && !v.isNull) return v.asText()
                }
                return null
            }
            prop("spring.datasource.url")?.let {
                facts += fact(
                    FactType.STORE_ACCESS, "actuator:/env", 0.97,
                    "kind" to "jdbc", "address" to it,
                )
            }
            prop("spring.application.name")?.let {
                facts += fact(FactType.CONTAINER_HINT, "actuator:/env", 0.97, "appName" to it)
            }
        }
        return facts
    }

    // --- OTel -------------------------------------------------------------

    /** Spans: OTLP-JSON (resourceSpans[].scopeSpans[].spans[]) или JSON-lines со спанами. */
    private fun otelFacts(file: Path): List<Fact> {
        val spans = mutableListOf<JsonNode>()
        val text = file.readText().trim()
        if (text.startsWith("{") && text.contains("resourceSpans")) {
            val root = json.readTree(text)
            for (rs in root.path("resourceSpans"))
                for (ss in rs.path("scopeSpans"))
                    for (s in ss.path("spans")) spans.add(s)
        } else {
            for (line in text.lines().filter { it.isNotBlank() }) {
                runCatching { json.readTree(line) }.getOrNull()?.let { spans.add(it) }
            }
        }

        // Дедуп: одинаковые факты из тысяч спанов схлопываются (первый traceId — источник).
        val out = linkedMapOf<String, Fact>()
        for (s in spans.sortedBy { it.path("traceId").asText() }) {
            val attrs = s.path("attributes").associate {
                it.path("key").asText() to it.path("value").path("stringValue").asText()
            }
            val kind = s.path("kind").asInt(0) // 3=CLIENT, 4=PRODUCER, 5=CONSUMER
            val src = "otel:${s.path("traceId").asText()}"
            val f: Fact? = when {
                kind == 3 && attrs["db.system"] != null -> fact(
                    FactType.STORE_ACCESS, src, 0.97,
                    "kind" to if (attrs["db.system"] == "redis") "redis" else "jdbc",
                    "address" to (attrs["db.name"] ?: ""),
                    "technology" to (attrs["db.system"] ?: ""),
                )
                kind == 3 -> {
                    val method = attrs["http.request.method"] ?: attrs["http.method"]
                    val url = attrs["url.full"] ?: attrs["http.url"]
                    if (method == null || url == null) null
                    else {
                        val uri = runCatching { URI(url) }.getOrNull()
                        val pairs = mutableListOf("method" to method)
                        uri?.host?.let { pairs += "host" to it }
                        uri?.path?.takeIf { it.isNotEmpty() }?.let { pairs += "path" to it }
                        fact(FactType.OUTGOING_CALL, src, 0.97, *pairs.toTypedArray())
                    }
                }
                kind == 4 && attrs["messaging.system"] == "kafka" ->
                    attrs["messaging.destination.name"]?.let {
                        fact(FactType.PUBLISH, src, 0.97, "channel" to it)
                    }
                kind == 5 && attrs["messaging.system"] == "kafka" ->
                    attrs["messaging.destination.name"]?.let { topic ->
                        val pairs = mutableListOf("channel" to topic)
                        attrs["messaging.kafka.consumer.group"]?.let { pairs += "group" to it }
                        fact(FactType.SUBSCRIBE, src, 0.97, *pairs.toTypedArray())
                    }
                else -> null
            }
            f?.let { out.putIfAbsent("${it.type} ${it.attrs}", it) }
        }
        return out.values.toList()
    }
}
