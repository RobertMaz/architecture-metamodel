package arch.analyzer

import arch.analyzer.core.Analyze
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzeE2eTest {

    private fun archRoot(): Path {
        val root = Files.createTempDirectory("arch-e2e")
        val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()
        root.resolve("registry").createDirectories()
        root.resolve("registry/repos.yml").writeText(
            """
            repos:
              test.app:
                repo: https://github.com/acme/test-app
                path: $fixture
            """.trimIndent() + "\n",
        )
        return root
    }

    @Test
    fun `полный цикл - evidence, отчёт и api-source на месте`() {
        val root = archRoot()
        val result = Analyze.run(root, "test.app", date = "2026-08-17")

        assertTrue(Files.exists(root.resolve("workspace/test.app/evidence.source.json")))
        assertTrue(Files.exists(root.resolve("workspace/test.app/reconcile-report.json")))
        val doc = root.resolve("tools/api-source/test.app.json").readText()
        assertTrue(doc.contains("\"/owners/{ownerId}\""), doc)
        assertTrue(doc.contains("\"containerInfo\""), doc)
        assertEquals(listOf("source"), result.lanesRun, "config-полки в фикстуре нет")
    }

    @Test
    fun `детерминизм - повторный прогон не меняет байты`() {
        val root = archRoot()
        Analyze.run(root, "test.app", date = "2026-08-17")
        val first = root.resolve("tools/api-source/test.app.json").readText()
        Analyze.run(root, "test.app", date = "2026-08-17")
        val second = root.resolve("tools/api-source/test.app.json").readText()
        assertEquals(first, second)
    }
}
