package arch.analyzer.server

import arch.analyzer.core.Json
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * REST поверх движка. Сервер ничего не знает про LikeC4 —
 * он оперирует реестрами, workspace и api-source-доками.
 */

data class SystemDto(
    val id: String,
    val title: String,
    val kind: String,
    val description: String? = null,
)

data class ContainerDto(
    val id: String,
    val system: String,
    val repo: String,
    val path: String,
    val analyzed: Boolean,
    val state: String,
    val lanes: List<String>,
    val operations: Int,
    val calls: Int,
    val stores: Int,
    val subscribes: Int,
    val publishes: Int,
    val unresolvedCalls: Int,
)

class Inventory(private val archRoot: Path) {

    private val yaml = ObjectMapper(YAMLFactory())
    private val json = ObjectMapper()

    fun systems(): List<SystemDto> {
        val file = archRoot.resolve("registry/systems.yml")
        if (!file.exists()) return emptyList()
        val root = yaml.readTree(file.toFile())?.get("systems") ?: return emptyList()
        return root.map {
            SystemDto(
                id = it["id"].asText(),
                title = it["title"]?.asText() ?: it["id"].asText(),
                kind = it["kind"]?.asText() ?: "system",
                description = it["description"]?.asText(),
            )
        }.sortedBy { it.id }
    }

    fun containers(): List<ContainerDto> {
        val file = archRoot.resolve("registry/repos.yml")
        if (!file.exists()) return emptyList()
        val repos = yaml.readTree(file.toFile())?.get("repos") ?: return emptyList()
        val out = mutableListOf<ContainerDto>()
        repos.fields().forEach { (id, node) ->
            val doc = readJson(archRoot.resolve("tools/api-source/$id.json"))
            val report = readJson(archRoot.resolve("workspace/$id/reconcile-report.json"))
            val status = readJson(archRoot.resolve("workspace/$id/status.json"))
            out += ContainerDto(
                id = id,
                system = id.substringBefore('.'),
                repo = node["repo"]?.asText() ?: "",
                path = node["path"]?.asText() ?: "",
                analyzed = doc != null,
                state = status?.get("state")?.asText() ?: "idle",
                lanes = status?.get("lanes")?.map { it.asText() } ?: emptyList(),
                operations = doc?.get("operations")?.size() ?: 0,
                calls = doc?.get("calls")?.size() ?: 0,
                stores = doc?.get("stores")?.size() ?: 0,
                subscribes = doc?.get("subscribes")?.size() ?: 0,
                publishes = doc?.get("publishes")?.size() ?: 0,
                unresolvedCalls = report?.get("unresolvedCalls")?.asInt() ?: 0,
            )
        }
        return out.sortedBy { it.id }
    }

    fun readJson(path: Path): JsonNode? =
        if (path.exists()) json.readTree(path.readText()) else null
}

fun buildApp(archRoot: Path): Application.() -> Unit = {
    val inventory = Inventory(archRoot)

    install(ContentNegotiation) { jackson() }
    install(CORS) {
        allowHost("localhost:5173")
        allowHost("localhost:5174")
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Post)
    }

    routing {
        get("/api/health") { call.respond(mapOf("status" to "ok")) }
        get("/api/systems") { call.respond(inventory.systems()) }
        get("/api/containers") { call.respond(inventory.containers()) }
    }
}
