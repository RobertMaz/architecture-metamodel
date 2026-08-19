package arch.analyzer.server

import arch.analyzer.core.Analyze
import arch.analyzer.core.Json
import arch.analyzer.core.Registry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Очередь прогонов: один воркер разбирает контейнеры по одному
 * (параллельные прогоны гоняли бы aliases.yml и npm run gen наперегонки).
 * Кнопка «Анализ» лишь ставит строчку в очередь: queued -> running -> done|failed,
 * статус в workspace/<id>/status.json — переживает рестарт сервера и обновление
 * страницы; зависшие после рестарта статусы помечаются failed при старте.
 * После успешного анализа выполняется npm run gen — модель дорастает сама.
 */
class Runs(private val archRoot: Path) {

    data class Status(
        val state: String,
        val lanes: List<String> = emptyList(),
        val failedLanes: List<String> = emptyList(),
        val factCount: Int = 0,
        val error: String? = null,
        val finishedAt: String? = null,
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "analyze-worker").apply { isDaemon = true }
    }

    /** Контейнеры в очереди или в работе. */
    private val active = ConcurrentHashMap.newKeySet<String>()

    init {
        sweepStale()
    }

    fun isKnown(containerId: String): Boolean =
        Registry(archRoot).repos().containsKey(containerId)

    fun isRunning(containerId: String): Boolean = containerId in active

    /** true — поставлен в очередь; false — уже в очереди или в работе. */
    fun start(containerId: String): Boolean {
        if (!active.add(containerId)) return false
        writeStatus(containerId, Status("queued"))
        executor.submit {
            writeStatus(containerId, Status("running"))
            try {
                val r = Analyze.run(archRoot, containerId, date = LocalDate.now().toString())
                generateModel()
                writeStatus(
                    containerId,
                    Status("done", r.lanesRun, r.failedLanes, r.factCount, finishedAt = Instant.now().toString()),
                )
            } catch (e: Exception) {
                writeStatus(
                    containerId,
                    Status("failed", error = e.message ?: e.javaClass.simpleName, finishedAt = Instant.now().toString()),
                )
            } finally {
                active.remove(containerId)
            }
        }
        return true
    }

    /** Рестарт сервера: «queued»/«running» без живого воркера — это обман, честно валим. */
    private fun sweepStale() {
        val ws = archRoot.resolve("workspace")
        if (!ws.exists()) return
        Files.list(ws).use { dirs ->
            dirs.filter { Files.isDirectory(it) }.sorted().forEach { dir ->
                val file = dir.resolve("status.json")
                if (!file.exists()) return@forEach
                val state = runCatching { Json.read(file.readText(), Status::class.java).state }.getOrNull()
                if (state == "queued" || state == "running") {
                    writeStatus(
                        dir.fileName.toString(),
                        Status("failed", error = "прогон прерван перезапуском сервера — запусти анализ заново"),
                    )
                }
            }
        }
    }

    private fun writeStatus(containerId: String, s: Status) {
        val dir = archRoot.resolve("workspace/$containerId").createDirectories()
        dir.resolve("status.json").writeText(Json.write(s))
    }

    /** Регенерация модели после ручного решения в триаже. */
    fun regenerate() = generateModel()

    /**
     * npm run gen: в корне данных, если у них свой package.json (отдельный приватный
     * репо, п. 10), иначе — в движке с ARCH_DATA_ROOT на данные. Вне полного
     * репо (тесты) — тихий скип.
     */
    private fun generateModel() {
        val dir = if (archRoot.resolve("package.json").exists()) archRoot else arch.analyzer.core.Analyze.engineRoot()
        if (!dir.resolve("package.json").exists()) return
        val p = ProcessBuilder("npm", "run", "gen")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .apply { environment()["ARCH_DATA_ROOT"] = archRoot.toString() }
            .start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) error("npm run gen упал:\n${out.takeLast(2000)}")
    }
}

/** Дифф модели/реестров: то, что изменил последний прогон, глазами git. */
class ModelDiff(private val archRoot: Path) {

    private val watched = listOf("tools/api-source", "model/gen", "model/systems", "registry")

    data class FileChange(val path: String, val status: String)
    data class Diff(val files: List<FileChange>, val patch: String)

    private fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-C", archRoot.toString()) + args)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    fun diff(): Diff {
        val status = git("status", "--porcelain", "--", *watched.toTypedArray())
        val files = status.lines().filter { it.isNotBlank() }.map { line ->
            val flag = line.take(2).trim()
            val path = line.drop(3).trim()
            FileChange(path, if (flag == "??" || flag == "A") "new" else if (flag.contains("D")) "deleted" else "modified")
        }.sortedBy { it.path }
        val patch = git("diff", "HEAD", "--", *watched.toTypedArray())
        return Diff(files, patch)
    }
}
