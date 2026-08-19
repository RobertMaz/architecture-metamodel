package arch.analyzer.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunsTest {

    private val json = ObjectMapper()

    private fun archRoot(): Path {
        val root = Files.createTempDirectory("runs-test")
        val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()
        root.resolve("registry").createDirectories()
        root.resolve("registry/systems.yml").writeText("systems:\n  - id: test\n    kind: system\n    title: T\n")
        root.resolve("registry/repos.yml").writeText(
            "repos:\n  test.app:\n    repo: https://github.com/acme/app\n    path: $fixture\n",
        )
        return root
    }

    @Test
    fun `analyze - цикл до done и отчёт`() = testApplication {
        val root = archRoot()
        application(buildApp(root))

        val rs = client.post("/api/containers/test.app/analyze")
        assertEquals(HttpStatusCode.Accepted, rs.status)

        var state = "running"
        for (i in 1..100) {
            Thread.sleep(100)
            val body = json.readTree(client.get("/api/containers").bodyAsText())
            state = body[0]["state"].asText()
            if (state == "done" || state == "failed") break
        }
        assertEquals("done", state)

        val report = json.readTree(client.get("/api/containers/test.app/report").bodyAsText())
        assertEquals(3, report["doc"]["operations"].size())
        assertTrue(report["report"].has("unresolvedCalls"))
    }

    @Test
    fun `analyze неизвестного контейнера - 404`() = testApplication {
        application(buildApp(archRoot()))
        assertEquals(HttpStatusCode.NotFound, client.post("/api/containers/nope/analyze").status)
    }

    @Test
    fun `diff - изменённые и новые файлы модели`() = testApplication {
        val root = archRoot()
        // git-репо: закоммиченный док + новый файл после «прогона»
        fun git(vararg a: String) {
            ProcessBuilder(listOf("git", "-C", root.toString()) + a)
                .redirectErrorStream(true).start().also { it.waitFor() }
        }
        git("init", "-q")
        git("config", "user.email", "t@t")
        git("config", "user.name", "t")
        root.resolve("tools/api-source").createDirectories()
        root.resolve("tools/api-source/a.json").writeText("{}\n")
        git("add", "-A")
        git("commit", "-qm", "init")
        root.resolve("tools/api-source/a.json").writeText("{\"x\":1}\n")
        root.resolve("tools/api-source/b.json").writeText("{}\n")

        application(buildApp(root))
        val diff = json.readTree(client.get("/api/diff").bodyAsText())
        val files = diff["files"].map { it["path"].asText() to it["status"].asText() }
        assertTrue("tools/api-source/a.json" to "modified" in files, "$files")
        assertTrue("tools/api-source/b.json" to "new" in files, "$files")
        assertTrue(diff["patch"].asText().contains("\"x\":1"))
    }

    /** Свежий data-репо: git init есть, коммитов нет (HEAD не рождён) — дифф не падает. */
    @Test
    fun `diff - репозиторий без единого коммита`() = testApplication {
        val root = archRoot()
        ProcessBuilder("git", "-C", root.toString(), "init", "-q")
            .redirectErrorStream(true).start().also { it.waitFor() }
        root.resolve("tools/api-source").createDirectories()
        root.resolve("tools/api-source/a.json").writeText("{}\n")

        application(buildApp(root))
        val diff = json.readTree(client.get("/api/diff").bodyAsText())
        val files = diff["files"].map { it["path"].asText() to it["status"].asText() }
        assertTrue("tools/api-source/a.json" to "new" in files, "$files")
        assertTrue(!diff["patch"].asText().contains("fatal"), "patch: ${diff["patch"].asText()}")
    }
}
