package arch.analyzer.core

import arch.analyzer.lanes.ConfigLane
import arch.analyzer.lanes.SourceLane
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Один прогон анализа контейнера: применимые полки -> evidence-файлы ->
 * реконсиляция -> tools/api-source/<id>.json. Файлы перезаписываются только
 * при изменении содержимого — чистый git-diff = чистый прогон.
 */
object Analyze {

    data class Result(
        val containerId: String,
        val lanesRun: List<String>,
        val factCount: Int,
        val report: ReconcileReport,
    )

    fun defaultLanes(): List<Lane> = listOf(SourceLane(), ConfigLane())

    fun run(archRoot: Path, containerId: String, date: String, lanes: List<Lane> = defaultLanes()): Result {
        val entry = Registry(archRoot).entry(containerId)
        val repoDir = Paths.get(entry.path)
        require(Files.isDirectory(repoDir)) { "нет директории репозитория: $repoDir" }

        val input = RepoInput(containerId, repoDir)
        val workspace = archRoot.resolve("workspace/$containerId").createDirectories()

        val evidences = mutableListOf<Evidence>()
        val lanesRun = mutableListOf<String>()
        for (lane in lanes) {
            if (!lane.applicable(input)) continue
            val facts = lane.extract(input)
            val evidence = Evidence(
                lane = lane.name,
                input = InputRef(kind = "git", path = entry.path, commit = gitCommit(repoDir)),
                facts = facts,
            ).canonical()
            writeIfChanged(workspace.resolve("evidence.${lane.name}.json"), Json.write(evidence))
            evidences += evidence
            lanesRun += lane.name
        }

        // Дообогащение: полки, чей вход сейчас недоступен, не теряются —
        // их прошлые evidence-файлы подхватываются с диска.
        for (file in listPersisted(workspace)) {
            val lane = file.fileName.toString().removePrefix("evidence.").removeSuffix(".json")
            if (lane in lanesRun) continue
            evidences += Json.read(file.readText(), Evidence::class.java)
        }

        val meta = SourceMeta(repo = entry.repo, commit = gitCommit(repoDir) ?: "local", extractedAt = date)
        val (doc, baseReport) = Reconciler().reconcile(containerId, evidences, meta)

        // Сервис декларирует свои имена — реестр алиасов пополняется сам.
        val aliasEntries = buildMap {
            doc.containerInfo.appName?.let { put(it, containerId) }
            put(containerId.substringAfterLast('.'), containerId)
        }
        val aliasConflicts = Aliases(archRoot).upsert(aliasEntries)
        val report = baseReport.copy(conflicts = (baseReport.conflicts + aliasConflicts).sorted())

        writeIfChanged(workspace.resolve("reconcile-report.json"), Json.write(report))
        val out = archRoot.resolve("tools/api-source").createDirectories()
        writeIfChanged(out.resolve("$containerId.json"), Json.write(doc))

        return Result(containerId, lanesRun.sorted(), evidences.sumOf { it.facts.size }, report)
    }

    private fun listPersisted(workspace: Path): List<Path> =
        Files.list(workspace).use { s ->
            s.filter { it.fileName.toString().matches(Regex("evidence\\.[a-z-]+\\.json")) }
                .sorted()
                .toList()
        }

    private fun writeIfChanged(path: Path, content: String) {
        if (Files.exists(path) && path.readText() == content) return
        path.writeText(content)
    }

    private fun gitCommit(repoDir: Path): String? = runCatching {
        val p = ProcessBuilder("git", "-C", repoDir.toString(), "rev-parse", "--short", "HEAD")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.matches(Regex("[0-9a-f]+"))) out else null
    }.getOrNull()
}
