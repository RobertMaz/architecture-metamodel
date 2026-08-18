package arch.analyzer.server

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueueTest {

    private val json = ObjectMapper()

    private fun archRoot(): Path {
        val root = Files.createTempDirectory("queue-test")
        val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()
        root.resolve("registry").createDirectories()
        root.resolve("registry/repos.yml").writeText(
            "repos:\n  test.app:\n    repo: r\n    path: $fixture\n  test.two:\n    repo: r\n    path: $fixture\n",
        )
        return root
    }

    @Test
    fun `повторный start того же контейнера отбивается, очередь доводит оба до done`() {
        val root = archRoot()
        val runs = Runs(root)

        assertTrue(runs.start("test.app"))
        assertTrue(!runs.start("test.app"), "второй клик по тому же контейнеру — отказ")
        assertTrue(runs.start("test.two"), "другой контейнер встаёт в очередь")

        for (i in 1..100) {
            if (!runs.isRunning("test.app") && !runs.isRunning("test.two")) break
            Thread.sleep(100)
        }
        for (id in listOf("test.app", "test.two")) {
            val status = json.readTree(root.resolve("workspace/$id/status.json").readText())
            assertEquals("done", status["state"].asText(), "статус $id")
        }
    }

    @Test
    fun `зависшие статусы помечаются failed при старте сервера`() {
        val root = archRoot()
        root.resolve("workspace/test.app").createDirectories()
        root.resolve("workspace/test.app/status.json").writeText("""{"state":"running","lanes":[]}""")
        root.resolve("workspace/test.two").createDirectories()
        root.resolve("workspace/test.two/status.json").writeText("""{"state":"queued","lanes":[]}""")

        Runs(root) // конструктор делает sweep

        for (id in listOf("test.app", "test.two")) {
            val status = json.readTree(root.resolve("workspace/$id/status.json").readText())
            assertEquals("failed", status["state"].asText())
            assertTrue(status["error"].asText().contains("перезапуск"), status.toString())
        }
    }
}
