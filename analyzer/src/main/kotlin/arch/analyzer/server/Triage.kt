package arch.analyzer.server

import arch.analyzer.core.Registry
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Триаж нераспознанных целей: чтение журнала и запись ручных решений
 * в registry/resolutions.yml (по духу append-only: решённый stub не перерешивается,
 * правка — руками в yml). После записи вызывается регенерация модели.
 */

data class ResolveRequest(
    val container: String? = null,
    val external: ExternalTarget? = null,
    /** Перенос stub'а контейнером в явную систему (обычно orgSystem): auth.sso. */
    val assign: AssignTarget? = null,
)

data class AssignTarget(
    val container: String,
)

data class ExternalTarget(
    val id: String,
    val title: String? = null,
    val contract: String? = null,
)

class Triage(private val archRoot: Path) {

    private val yaml = ObjectMapper(YAMLFactory())

    private val header =
        "# Ручные решения по нераспознанным целям (stub -> контейнер | external).\n" +
            "# По духу append-only: решённое не перерешивается через API, только руками.\n"

    private fun file(): Path = archRoot.resolve("registry/resolutions.yml")

    fun unresolvedJson(): String {
        val f = archRoot.resolve("registry/unresolved.json")
        return if (f.exists()) f.readText() else """{"unresolved":[]}"""
    }

    fun knownStubIds(): Set<String> {
        val f = archRoot.resolve("registry/unresolved.json")
        if (!f.exists()) return emptySet()
        val root = ObjectMapper().readTree(f.readText())?.get("unresolved") ?: return emptySet()
        return root.mapNotNull { it.get("stubId")?.takeIf { n -> !n.isNull }?.asText() }.toSet()
    }

    private fun systemIds(): Set<String> {
        val f = archRoot.resolve("registry/systems.yml")
        if (!f.exists()) return emptySet()
        return yaml.readTree(f.toFile())?.get("systems")?.mapNotNull { it.get("id")?.asText() }?.toSet() ?: emptySet()
    }

    fun resolved(): Map<String, ResolveRequest> {
        if (!file().exists()) return emptyMap()
        val root = yaml.readTree(file().toFile())?.get("resolutions") ?: return emptyMap()
        val out = sortedMapOf<String, ResolveRequest>()
        root.fields().forEach { (stubId, n) ->
            out[stubId] = ResolveRequest(
                container = n.get("container")?.asText(),
                external = n.get("external")?.let {
                    ExternalTarget(
                        id = it.get("id").asText(),
                        title = it.get("title")?.asText(),
                        contract = it.get("contract")?.asText(),
                    )
                },
                assign = n.get("assign")?.get("container")?.asText()?.let { AssignTarget(it) },
            )
        }
        return out
    }

    sealed interface Result {
        data object Ok : Result
        data class NotFound(val message: String) : Result
        data class Conflict(val message: String) : Result
        data class Invalid(val message: String) : Result
    }

    fun resolve(stubId: String, rq: ResolveRequest): Result {
        if (stubId !in knownStubIds()) return Result.NotFound("stub «$stubId» не найден в registry/unresolved.json")
        if (stubId in resolved()) return Result.Conflict("stub «$stubId» уже решён — правка только руками в resolutions.yml")

        when {
            rq.container != null -> {
                if (!Registry(archRoot).repos().containsKey(rq.container)) {
                    return Result.Invalid("контейнер «${rq.container}» не найден в registry/repos.yml")
                }
            }
            rq.external != null -> {
                if (!rq.external.id.matches(Regex("[a-z][a-z0-9_]*"))) {
                    return Result.Invalid("id внешней системы: [a-z][a-z0-9_]*")
                }
            }
            rq.assign != null -> {
                val cid = rq.assign.container
                if (!cid.matches(Regex("[a-z][a-z0-9_]*\\.[a-zA-Z][a-zA-Z0-9_]*"))) {
                    return Result.Invalid("assign.container: <система>.<имя>")
                }
                val system = cid.substringBefore('.')
                if (system !in systemIds()) {
                    return Result.Invalid("система «$system» не заведена — сначала создай её (kind orgSystem для чужих)")
                }
                if (Registry(archRoot).repos().containsKey(cid)) {
                    return Result.Invalid("«$cid» анализируется нами — используй обычную склейку (container)")
                }
            }
            else -> return Result.Invalid("нужно container, external или assign")
        }

        val all = resolved().toSortedMap()
        all[stubId] = rq
        val out = StringBuilder(header).append("resolutions:\n")
        for ((id, r) in all) {
            out.append("  $id:\n")
            if (r.container != null) out.append("    container: ${r.container}\n")
            r.assign?.let { out.append("    assign:\n      container: ${it.container}\n") }
            r.external?.let { e ->
                out.append("    external:\n")
                out.append("      id: ${e.id}\n")
                e.title?.let { out.append("      title: $it\n") }
                e.contract?.let { out.append("      contract: $it\n") }
            }
        }
        file().writeText(out.toString())
        return Result.Ok
    }
}
