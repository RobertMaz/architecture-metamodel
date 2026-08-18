package arch.analyzer.lanes

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import arch.analyzer.core.fact
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options")

/** Имена файлов, по которым спека находится без явного указания. */
private val WELL_KNOWN = listOf("openapi.yml", "openapi.yaml", "openapi.json", "api-docs.json", "swagger.yml", "swagger.yaml", "swagger.json")

/**
 * Полка openapi: готовая спека — источник контрактов с высоким доверием.
 * Вход: `openapi:` в repos.yml или автодетект в корне/src/main/resources.
 * Снятую с рантайма /v3/api-docs спеку кладут файлом — вход тот же.
 */
class OpenApiLane : Lane {

    override val name = "openapi"

    private val yaml = ObjectMapper(YAMLFactory())
    private val json = ObjectMapper()

    private fun specFile(input: RepoInput): Path? {
        input.openapi?.takeIf { it.exists() }?.let { return it }
        for (dir in listOf(input.repoDir, input.repoDir.resolve("src/main/resources"))) {
            for (name in WELL_KNOWN) {
                dir.resolve(name).takeIf { it.exists() }?.let { return it }
            }
        }
        return null
    }

    override fun applicable(input: RepoInput): Boolean = specFile(input) != null

    override fun extract(input: RepoInput): List<Fact> {
        val file = specFile(input) ?: return emptyList()
        val mapper = if (file.name.endsWith(".json")) json else yaml
        val root = mapper.readTree(file.toFile()) ?: return emptyList()
        val source = input.repoDir.relativize(file).toString().replace('\\', '/')
            .ifEmpty { file.name }

        // path-часть servers.url — кандидат в префикс путей; согласует реконсилятор
        val serverPath = root.path("servers").firstOrNull()?.path("url")?.asText("")
            ?.let { runCatching { java.net.URI(it).path }.getOrNull() ?: it.takeIf { u -> u.startsWith("/") } }
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }

        val facts = mutableListOf<Fact>()
        root.path("paths").fields().forEach { (path, ops) ->
            ops.fields().forEach { (method, op) ->
                if (method.lowercase() !in HTTP_METHODS) return@forEach
                val attrs = mutableListOf(
                    "method" to method.uppercase(),
                    "path" to path,
                )
                serverPath?.let { attrs += "specServerPath" to it }
                op.path("summary").asText("").takeIf { it.isNotEmpty() }?.let { attrs += "summary" to it }
                params(op)?.let { attrs += "params" to it }
                refName(op.path("requestBody"))?.let { attrs += "request" to it }
                response2xx(op)?.let { attrs += "response" to it }
                if (op.path("deprecated").asBoolean(false)) attrs += "deprecated" to "true"
                facts += fact(FactType.ENDPOINT, source, 0.95, *attrs.toTypedArray())
            }
        }
        return facts
    }

    /** name:in:type[?] в порядке объявления — формат репо. */
    private fun params(op: JsonNode): String? {
        val parts = op.path("parameters").mapNotNull { p ->
            val name = p.path("name").asText("").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val where = p.path("in").asText("query")
            val type = p.path("schema").path("type").asText("string")
            val optional = if (p.path("required").asBoolean(false)) "" else "?"
            "$name:$where:$type$optional"
        }
        return parts.joinToString(", ").ifEmpty { null }
    }

    /** Имя типа из первого $ref в content любого медиа-типа. */
    private fun refName(node: JsonNode): String? {
        node.path("content").fields().forEach { (_, media) ->
            val ref = media.path("schema").path("\$ref").asText("")
            if (ref.isNotEmpty()) return ref.substringAfterLast('/')
        }
        return null
    }

    private fun response2xx(op: JsonNode): String? {
        op.path("responses").fields().forEach { (status, rs) ->
            if (status.startsWith("2")) refName(rs)?.let { return it }
        }
        return null
    }
}
