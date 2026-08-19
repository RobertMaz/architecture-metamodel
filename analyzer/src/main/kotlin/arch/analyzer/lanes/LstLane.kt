package arch.analyzer.lanes

import arch.analyzer.core.Fact
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Полка lst: source-полка на OpenRewrite LST (rewrite-java + rewrite-kotlin) —
 * Java и Kotlin одним визитором, со статическим резолвом URL и топиков
 * (константы, конкатенации, string templates, base у WebClient.create).
 *
 * Экстрактор — отдельный процесс (analyzer/lst-extractor, собирается один раз):
 * rewrite-kotlin сидит на kotlin-compiler-embeddable 1.9.25, анализатор — на
 * Kotlin 2.1.20, в одном classpath они не живут. Classpath жертвы для typed-резолва
 * берётся бесплатно из BOOT-INF/lib её же fat-jar; без него экстрактор деградирует
 * до уровня регулярок (untyped, confidence ниже). Regex-полка source остаётся
 * fallback'ом и вторым мнением.
 */
class LstLane(
    private val extractorDir: Path = Paths.get("analyzer/lst-extractor"),
) : Lane {

    override val name = "lst"

    private fun classesDir() = extractorDir.resolve("target/classes")
    private fun cpFile() = extractorDir.resolve("target/cp.txt")

    private fun hasSources(input: RepoInput): Boolean {
        val src = input.repoDir.resolve("src/main")
        if (!src.exists()) return false
        return Files.walk(src).use { s ->
            s.anyMatch { it.name.endsWith(".java") || it.name.endsWith(".kt") }
        }
    }

    override fun applicable(input: RepoInput): Boolean =
        classesDir().exists() && cpFile().exists() && hasSources(input)

    override fun extract(input: RepoInput): List<Fact> {
        val tmp = Files.createTempDirectory("lst-cp")
        try {
            val cpDir = input.jar?.takeIf { it.exists() }?.let { extractLibs(it, tmp) }
            val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
            val cp = classesDir().toAbsolutePath().toString() +
                java.io.File.pathSeparator + cpFile().readText().trim()
            val p = ProcessBuilder(
                javaBin, "-Xmx1024m", "-cp", cp, "arch.lst.Extractor",
                cpDir?.toAbsolutePath()?.toString() ?: "NONE",
                input.repoDir.toAbsolutePath().toString(),
            ).start()
            val lines = p.inputStream.bufferedReader().readLines()
            val err = p.errorStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                p.destroyForcibly()
                error("lst-экстрактор упал (exit=${runCatching { p.exitValue() }.getOrNull()}): ${err.takeLast(500)}")
            }
            return parseFactLines(lines, name)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** BOOT-INF/lib жертвы -> плоская папка jar'ов, classpath для typed-резолва. */
    private fun extractLibs(jar: Path, dst: Path): Path? {
        var found = false
        ZipFile(jar.toFile()).use { zip ->
            for (e in zip.entries()) {
                if (e.isDirectory || !e.name.startsWith("BOOT-INF/lib/") || !e.name.endsWith(".jar")) continue
                val target = dst.resolve(e.name.substringAfterLast('/')).normalize()
                require(target.startsWith(dst)) { "zip-slip: ${e.name}" }
                zip.getInputStream(e).use { Files.copy(it, target) }
                found = true
            }
        }
        return if (found) dst else null
    }
}
