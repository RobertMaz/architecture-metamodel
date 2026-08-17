package arch.analyzer.core

import arch.analyzer.lanes.BytecodeLane
import arch.analyzer.lanes.ConfigLane
import arch.analyzer.lanes.JqassistantLane
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

    fun llmClient(archRoot: Path): Pair<arch.analyzer.llm.LlmClient, arch.analyzer.llm.LlmConfig>? {
        val cfg = arch.analyzer.llm.LlmConfig.load(archRoot) ?: return null
        val cached = arch.analyzer.llm.CachedLlm(
            arch.analyzer.llm.OpenAiClient(cfg),
            archRoot.resolve("workspace/_llm-cache"),
            cfg.model,
        )
        return cached to cfg
    }

    fun defaultLanes(archRoot: Path? = null): List<Lane> {
        val root = archRoot ?: Paths.get(".")
        val llm = archRoot?.let { llmClient(it) }
        return listOf(
            SourceLane(),
            ConfigLane(),
            BytecodeLane(),
            JqassistantLane(adapter = root.resolve("analyzer/jqassistant/extract.sh")),
            arch.analyzer.lanes.RuntimeLane(),
            // LLM — последней: точки внимания вычисляются по свежим evidence других полок.
            arch.analyzer.llm.LlmLane(root, llm?.first, enrich = llm?.second?.enrich ?: false),
        )
    }

    fun run(archRoot: Path, containerId: String, date: String, lanes: List<Lane>? = null): Result {
        @Suppress("NAME_SHADOWING") val lanes = lanes ?: defaultLanes(archRoot)
        val entry = Registry(archRoot).entry(containerId)
        val repoDir = Paths.get(entry.path)
        require(Files.isDirectory(repoDir)) { "нет директории репозитория: $repoDir" }

        val input = RepoInput(
            containerId, repoDir,
            jar = entry.jar?.let { Paths.get(it) },
            runtimeUrl = entry.runtimeUrl,
            traces = entry.traces?.let { Paths.get(it) },
        )
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

        // LLM-ревью: подозрения на пропуски — только в отчёт, не в факты.
        val llmReview = llmClient(archRoot)?.let { (client, _) ->
            val files = Files.walk(repoDir.resolve("src")).use { s ->
                s.filter { Files.isRegularFile(it) }
                    .map { repoDir.relativize(it).toString().replace('\\', '/') }
                    .sorted().toList()
            }
            runCatching { arch.analyzer.llm.LlmReviewer(client).review(doc, files) }
                .getOrElse { listOf("LLM-ревью упало: ${it.message}") }
        } ?: emptyList()

        val report = baseReport.copy(
            conflicts = (baseReport.conflicts + aliasConflicts).sorted(),
            llmReview = llmReview,
        )

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
