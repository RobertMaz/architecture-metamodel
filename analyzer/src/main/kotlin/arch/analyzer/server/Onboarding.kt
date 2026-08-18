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
    val jar: String? = null,
    val runtimeUrl: String? = null,
    val traces: String? = null,
    val openapi: String? = null,
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

    /** Патч источников: null — не трогать, пустая строка — удалить, значение — заменить. */
    data class SourcesPatch(
        val repo: String? = null,
        val path: String? = null,
        val jar: String? = null,
        val runtimeUrl: String? = null,
        val traces: String? = null,
        val openapi: String? = null,
    )

    fun updateSources(id: String, p: SourcesPatch): Result {
        val file = archRoot.resolve("registry/repos.yml")
        val existing = if (file.exists()) yaml.readTree(file.toFile())?.get("repos") else null
        val entries = sortedMapOf<String, MutableMap<String, String>>()
        existing?.fields()?.forEach { (cid, n) ->
            val row = mutableMapOf<String, String>()
            for (k in listOf("repo", "path", "jar", "runtimeUrl", "traces", "openapi")) {
                n[k]?.asText()?.takeIf { it.isNotEmpty() }?.let { row[k] = it }
            }
            entries[cid] = row
        }
        val row = entries[id] ?: return Result.Invalid("контейнер «$id» не найден в registry/repos.yml")

        p.path?.takeIf { it.isNotBlank() }?.let {
            if (!Files.isDirectory(Paths.get(it))) return Result.Invalid("нет директории: $it")
        }
        for ((k, v) in mapOf(
            "repo" to p.repo, "path" to p.path, "jar" to p.jar,
            "runtimeUrl" to p.runtimeUrl, "traces" to p.traces, "openapi" to p.openapi,
        )) {
            when {
                v == null -> {}
                v.isBlank() -> row.remove(k)
                else -> row[k] = v
            }
        }
        if (row["path"].isNullOrEmpty()) return Result.Invalid("path обязателен — без сорцов анализировать нечего")

        val out = StringBuilder(reposHeader).append("repos:\n")
        for ((cid, r) in entries) {
            out.append("  $cid:\n")
            for (k in listOf("repo", "path", "jar", "runtimeUrl", "traces", "openapi")) {
                r[k]?.let { out.append("    $k: ${quote(it)}\n") }
            }
        }
        file.writeText(out.toString())
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
        data class Row(val repo: String, val path: String, val jar: String?, val runtimeUrl: String?, val traces: String?, val openapi: String?)
        val entries = sortedMapOf<String, Row>()
        existing?.fields()?.forEach { (id, n) ->
            entries[id] = Row(
                n["repo"]?.asText() ?: "", n["path"]?.asText() ?: "",
                n["jar"]?.asText(), n["runtimeUrl"]?.asText(), n["traces"]?.asText(), n["openapi"]?.asText(),
            )
        }
        if (c.id in entries) return Result.Conflict("контейнер «${c.id}» уже есть")
        entries[c.id] = Row(
            c.repo, c.path,
            c.jar?.takeIf { it.isNotBlank() },
            c.runtimeUrl?.takeIf { it.isNotBlank() },
            c.traces?.takeIf { it.isNotBlank() },
            c.openapi?.takeIf { it.isNotBlank() },
        )

        val out = StringBuilder(reposHeader).append("repos:\n")
        for ((id, row) in entries) {
            out.append("  $id:\n")
            out.append("    repo: ${quote(row.repo)}\n")
            out.append("    path: ${quote(row.path)}\n")
            row.jar?.let { out.append("    jar: ${quote(it)}\n") }
            row.runtimeUrl?.let { out.append("    runtimeUrl: ${quote(it)}\n") }
            row.traces?.let { out.append("    traces: ${quote(it)}\n") }
            row.openapi?.let { out.append("    openapi: ${quote(it)}\n") }
        }
        file.writeText(out.toString())
        return Result.Created
    }
}
