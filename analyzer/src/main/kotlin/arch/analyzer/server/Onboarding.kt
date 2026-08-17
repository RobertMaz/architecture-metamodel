package arch.analyzer.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Онбординг из UI: единственный способ завести систему/контейнер.
 * yml переписываются целиком с фиксированной шапкой и сортировкой по id —
 * дифф в git остаётся читаемым. Владельцы — только в CODEOWNERS.
 */

data class NewSystem(
    val id: String,
    val title: String,
    val kind: String = "system",
    val description: String? = null,
    val owner: String,
)

data class NewContainer(
    val id: String,
    val repo: String,
    val path: String,
)

class Onboarding(private val archRoot: Path) {

    private val yaml = ObjectMapper(YAMLFactory())

    sealed interface Result {
        data object Created : Result
        data class Conflict(val message: String) : Result
        data class Invalid(val message: String) : Result
    }

    private val systemsHeader =
        "# Реестр систем (L0). Источник для model/gen/systems/*.gen.c4.\n" +
            "# Владельцы — только в CODEOWNERS (правило R1/R1b в check.mjs).\n"

    private val reposHeader =
        "# Инвентарь: container-id -> репозиторий. Единственное место, где человек\n" +
            "# привязывает репо к id контейнера (и системе — через префикс id).\n"

    private fun quote(v: String): String =
        if (v.matches(Regex("[\\p{L}\\p{N}_./:@-]+"))) v else "\"" + v.replace("\"", "\\\"") + "\""

    fun systemIds(): Set<String> {
        val file = archRoot.resolve("registry/systems.yml")
        if (!file.exists()) return emptySet()
        return yaml.readTree(file.toFile())?.get("systems")?.map { it["id"].asText() }?.toSet() ?: emptySet()
    }

    fun addSystem(s: NewSystem): Result {
        if (!s.id.matches(Regex("[a-z][a-z0-9_]*"))) return Result.Invalid("id системы: [a-z][a-z0-9_]*")
        if (s.kind !in setOf("system", "orgSystem", "externalSystem")) return Result.Invalid("kind: system|orgSystem|externalSystem")
        if (!s.owner.startsWith("@")) return Result.Invalid("owner — команда вида @org/team")
        if (s.id in systemIds()) return Result.Conflict("система «${s.id}» уже есть")

        val file = archRoot.resolve("registry/systems.yml")
        val existing = if (file.exists()) yaml.readTree(file.toFile())?.get("systems") ?: null else null
        val entries = sortedMapOf<String, List<Pair<String, String>>>()
        existing?.forEach { n ->
            entries[n["id"].asText()] = buildList {
                add("kind" to n["kind"].asText())
                add("title" to n["title"].asText())
                n["description"]?.takeIf { !it.isNull }?.let { add("description" to it.asText()) }
            }
        }
        entries[s.id] = buildList {
            add("kind" to s.kind)
            add("title" to s.title)
            s.description?.let { add("description" to it) }
        }

        val out = StringBuilder(systemsHeader).append("systems:\n")
        for ((id, fields) in entries) {
            out.append("  - id: $id\n")
            for ((k, v) in fields) out.append("    $k: ${quote(v)}\n")
        }
        file.writeText(out.toString())

        // Владение живёт там, где у него зубы.
        val owners = archRoot.resolve("CODEOWNERS")
        val line = "/model/gen/systems/${s.id}.gen.c4  ${s.owner}\n"
        owners.writeText((if (owners.exists()) owners.readText().trimEnd('\n') + "\n" else "") + line)
        return Result.Created
    }

    fun addContainer(c: NewContainer): Result {
        val system = c.id.substringBefore('.', "")
        if (!c.id.matches(Regex("[a-z][a-z0-9_]*\\.[a-zA-Z][a-zA-Z0-9_]*"))) {
            return Result.Invalid("id контейнера: <система>.<имя>")
        }
        if (system !in systemIds()) return Result.Invalid("система «$system» не заведена — сначала POST /api/systems")
        if (!Files.isDirectory(Paths.get(c.path))) return Result.Invalid("нет директории: ${c.path}")

        val file = archRoot.resolve("registry/repos.yml")
        val existing = if (file.exists()) yaml.readTree(file.toFile())?.get("repos") else null
        val entries = sortedMapOf<String, Pair<String, String>>()
        existing?.fields()?.forEach { (id, n) ->
            entries[id] = (n["repo"]?.asText() ?: "") to (n["path"]?.asText() ?: "")
        }
        if (c.id in entries) return Result.Conflict("контейнер «${c.id}» уже есть")
        entries[c.id] = c.repo to c.path

        val out = StringBuilder(reposHeader).append("repos:\n")
        for ((id, rp) in entries) {
            out.append("  $id:\n")
            out.append("    repo: ${quote(rp.first)}\n")
            out.append("    path: ${quote(rp.second)}\n")
        }
        file.writeText(out.toString())
        return Result.Created
    }
}
