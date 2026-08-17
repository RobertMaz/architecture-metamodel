package arch.analyzer.server

import arch.analyzer.core.Analyze
import arch.analyzer.core.Json
import arch.analyzer.core.Registry
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Асинхронные прогоны анализа: поток на контейнер, статус — в
 * workspace/<id>/status.json (переживает рестарт сервера; UI просто поллит).
 * После успешного анализа выполняется npm run gen — модель дорастает сама.
 * status.json эфемерен (gitignore), таймстемпы здесь допустимы.
 */
class Runs(private val archRoot: Path) {

    data class Status(
        val state: String,
        val lanes: List<String> = emptyList(),
        val factCount: Int = 0,
        val error: String? = null,
        val finishedAt: String? = null,
    )

    private val active = ConcurrentHashMap<String, Thread>()

    fun isKnown(containerId: String): Boolean =
        Registry(archRoot).repos().containsKey(containerId)

    fun isRunning(containerId: String): Boolean =
        active[containerId]?.isAlive == true

    /** true — прогон запущен; false — уже идёт. */
    fun start(containerId: String): Boolean {
        if (isRunning(containerId)) return false
        val t = Thread {
            try {
                val r = Analyze.run(archRoot, containerId, date = LocalDate.now().toString())
                generateModel()
                writeStatus(containerId, Status("done", r.lanesRun, r.factCount, finishedAt = Instant.now().toString()))
            } catch (e: Exception) {
                writeStatus(containerId, Status("failed", error = e.message ?: e.javaClass.simpleName, finishedAt = Instant.now().toString()))
            } finally {
                active.remove(containerId)
            }
        }
        active[containerId] = t
        writeStatus(containerId, Status("running"))
        t.start()
        return true
    }

    private fun writeStatus(containerId: String, s: Status) {
        val dir = archRoot.resolve("workspace/$containerId").createDirectories()
        dir.resolve("status.json").writeText(Json.write(s))
    }

    /** Регенерация модели после ручного решения в триаже. */
    fun regenerate() = generateModel()

    /** npm run gen в корне архрепо; вне полного репо (тесты) — тихий скип. */
    private fun generateModel() {
        if (!archRoot.resolve("package.json").exists()) return
        val p = ProcessBuilder("npm", "run", "gen")
            .directory(archRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) error("npm run gen упал:\n${out.takeLast(2000)}")
    }
}

/** Дифф модели/реестров: то, что изменил последний прогон, глазами git. */
class ModelDiff(private val archRoot: Path) {

    private val watched = listOf("tools/api-source", "model/gen", "registry")

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
