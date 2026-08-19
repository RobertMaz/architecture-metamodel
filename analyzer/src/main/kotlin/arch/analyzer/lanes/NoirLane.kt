package arch.analyzer.lanes

import arch.analyzer.core.Fact
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

/**
 * Микрополка noir — второе мнение для REST-in (OWASP Noir, один бинарь ~50 МБ).
 * Пилот: 15/15 эндпоинтов petclinic посимвольно, Kotlin Spring ок, ~0.07 с/сервис.
 * Совпадение с source/lst-полкой поднимает confidence эндпоинта в реконсиляторе.
 * Framework-эндпоинты (Eureka, actuator) не видит — они остаются за runtime-полкой.
 *
 * Опциональная: нужен бинарь noir — $NOIR_BIN, analyzer/noir/noir (gitignore)
 * или noir из PATH. Адаптер печатает строки контракта jqassistant.
 */
class NoirLane(
    private val adapter: Path = Paths.get("analyzer/noir/noir-adapter.sh"),
    private val binary: Path? = null,
) : Lane {

    override val name = "noir"

    private fun noirBin(): Path? {
        binary?.takeIf { it.exists() }?.let { return it }
        System.getenv("NOIR_BIN")?.let { Paths.get(it).takeIf(Path::exists)?.let { p -> return p } }
        adapter.parent?.resolve("noir")?.takeIf { it.isExecutable() }?.let { return it }
        return System.getenv("PATH")?.split(java.io.File.pathSeparator)
            ?.map { Paths.get(it).resolve("noir") }
            ?.firstOrNull { it.isExecutable() }
    }

    override fun applicable(input: RepoInput): Boolean =
        adapter.exists() && Files.isDirectory(input.repoDir) && noirBin() != null

    override fun extract(input: RepoInput): List<Fact> {
        val pb = ProcessBuilder(
            adapter.toAbsolutePath().toString(),
            input.repoDir.toAbsolutePath().toString(),
        )
        noirBin()?.let { pb.environment()["NOIR_BIN"] = it.toAbsolutePath().toString() }
        val p = pb.start()
        val lines = p.inputStream.bufferedReader().readLines()
        val err = p.errorStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
            p.destroyForcibly()
            error("адаптер noir завершился с ошибкой (exit=${runCatching { p.exitValue() }.getOrNull()}): ${errorDigest(err)}")
        }
        return withContextPrefix(parseFactLines(lines, name))
    }

    /**
     * Noir отдаёт пути с servlet context-path (например /petclinic/api/...), а якорные
     * полки — servlet-относительные. Общий ведущий сегмент всех путей — кандидат
     * на срез: реконсилятор срежет его, только если это даст больше совпадений
     * с эндпоинтами других полок (attr contextPrefix, см. alignContextPrefixes).
     */
    private fun withContextPrefix(facts: List<Fact>): List<Fact> {
        val endpoints = facts.filter { it.type == arch.analyzer.core.FactType.ENDPOINT }
        if (endpoints.isEmpty()) return facts
        val heads = endpoints.map { it.attrs["path"]?.split('/')?.firstOrNull { s -> s.isNotEmpty() } }
        val head = heads.first()
        if (head == null || head.startsWith("{") || heads.any { it != head }) return facts
        return facts.map {
            if (it.type != arch.analyzer.core.FactType.ENDPOINT) it
            else it.copy(attrs = java.util.TreeMap(it.attrs + ("contextPrefix" to "/$head")))
        }
    }
}
