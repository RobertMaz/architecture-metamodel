package arch.analyzer.server

import arch.analyzer.core.Aliases
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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

data class MoveRequest(
    val system: String,
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
    private val json = ObjectMapper()

    /** Порядок полей записи repos.yml — единый для всех перезаписей файла. */
    private val repoKeys = listOf("repo", "path", "jar", "runtimeUrl", "traces", "openapi", "config")

    private fun reposFile(): Path = archRoot.resolve("registry/repos.yml")

    private fun readRepos(): java.util.SortedMap<String, MutableMap<String, String>> {
        val entries = sortedMapOf<String, MutableMap<String, String>>()
        val existing = if (reposFile().exists()) yaml.readTree(reposFile().toFile())?.get("repos") else null
        existing?.fields()?.forEach { (cid, n) ->
            val row = mutableMapOf<String, String>()
            for (k in repoKeys) n[k]?.asText()?.takeIf { it.isNotEmpty() }?.let { row[k] = it }
            entries[cid] = row
        }
        return entries
    }

    private fun writeRepos(entries: Map<String, Map<String, String>>) {
        val out = StringBuilder(reposHeader).append("repos:\n")
        for ((cid, row) in entries) {
            out.append("  $cid:\n")
            for (k in repoKeys) row[k]?.let { out.append("    $k: ${quote(it)}\n") }
        }
        reposFile().writeText(out.toString())
    }

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
        val line = "/model/systems/${s.id}/  ${s.owner}\n"
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
        val entries = readRepos()
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

        writeRepos(entries)
        return Result.Created
    }

    fun addContainer(c: NewContainer): Result {
        val system = c.id.substringBefore('.', "")
        if (!c.id.matches(Regex("[a-z][a-z0-9_]*\\.[a-zA-Z][a-zA-Z0-9_]*"))) {
            return Result.Invalid("id контейнера: <система>.<имя>")
        }
        if (system !in systemIds()) return Result.Invalid("система «$system» не заведена — сначала POST /api/systems")
        if (!Files.isDirectory(Paths.get(c.path))) return Result.Invalid("нет директории: ${c.path}")

        val entries = readRepos()
        if (c.id in entries) return Result.Conflict("контейнер «${c.id}» уже есть")
        entries[c.id] = buildMap {
            put("repo", c.repo)
            put("path", c.path)
            c.jar?.takeIf { it.isNotBlank() }?.let { put("jar", it) }
            c.runtimeUrl?.takeIf { it.isNotBlank() }?.let { put("runtimeUrl", it) }
            c.traces?.takeIf { it.isNotBlank() }?.let { put("traces", it) }
            c.openapi?.takeIf { it.isNotBlank() }?.let { put("openapi", it) }
        }.toMutableMap()

        writeRepos(entries)
        return Result.Created
    }

    /**
     * Удаление контейнера: запись в repos.yml, api-source-док, workspace-улики,
     * алиасы и решения триажа на него. Регенерацию модели зовёт роут.
     */
    fun deleteContainer(id: String): Result {
        val entries = readRepos()
        if (entries.remove(id) == null) return Result.Invalid("контейнер «$id» не найден в registry/repos.yml")
        writeRepos(entries)
        Files.deleteIfExists(archRoot.resolve("tools/api-source/$id.json"))
        archRoot.resolve("workspace/$id").toFile().deleteRecursively()
        Aliases(archRoot).retarget(id, null)
        Triage(archRoot).retarget(id, null)
        return Result.Created
    }

    /**
     * Перенос контейнера в другую систему: id — это <система>.<имя>, поэтому перенос =
     * переименование id везде, где он ключ: repos.yml, api-source (файл и поле container),
     * workspace, алиасы, решения триажа. Рукописные ссылки в c4-файлах model не трогаем —
     * их подсветит npm run check.
     */
    fun moveContainer(id: String, newSystem: String): Result {
        if (newSystem !in systemIds()) return Result.Invalid("система «$newSystem» не заведена — сначала POST /api/systems")
        val newId = "$newSystem." + id.substringAfter('.')
        if (newId == id) return Result.Invalid("контейнер уже в системе «$newSystem»")

        val entries = readRepos()
        val row = entries[id] ?: return Result.Invalid("контейнер «$id» не найден в registry/repos.yml")
        if (newId in entries) return Result.Conflict("контейнер «$newId» уже есть")
        entries.remove(id)
        entries[newId] = row
        writeRepos(entries)

        val src = archRoot.resolve("tools/api-source/$id.json")
        if (src.exists()) {
            val doc = json.readTree(src.toFile())
            (doc as? ObjectNode)?.put("container", newId)
            archRoot.resolve("tools/api-source/$newId.json")
                .writeText(json.writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n")
            Files.delete(src)
        }
        val ws = archRoot.resolve("workspace/$id")
        if (Files.isDirectory(ws)) Files.move(ws, archRoot.resolve("workspace/$newId"))
        Aliases(archRoot).retarget(id, newId)
        Triage(archRoot).retarget(id, newId)
        return Result.Created
    }
}
