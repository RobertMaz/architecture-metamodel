package arch.analyzer.server

import arch.analyzer.core.Analyze
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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

class ApiTest {

    private val json = ObjectMapper()

    private fun archRoot(): Path {
        val root = Files.createTempDirectory("api-test")
        val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()
        root.resolve("registry").createDirectories()
        root.resolve("registry/systems.yml").writeText(
            "systems:\n  - id: test\n    kind: system\n    title: Тест\n    description: Демо\n",
        )
        root.resolve("registry/repos.yml").writeText(
            "repos:\n  test.app:\n    repo: https://github.com/acme/app\n    path: $fixture\n",
        )
        return root
    }

    @Test
    fun `health отвечает ok`() = testApplication {
        application(buildApp(archRoot()))
        val rs = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, rs.status)
        assertTrue(rs.bodyAsText().contains("ok"))
    }

    @Test
    fun `systems из реестра`() = testApplication {
        application(buildApp(archRoot()))
        val body: JsonNode = json.readTree(client.get("/api/systems").bodyAsText())
        assertEquals(1, body.size())
        assertEquals("test", body[0]["id"].asText())
        assertEquals("Тест", body[0]["title"].asText())
    }

    @Test
    fun `containers - до анализа idle, после - счётчики`() = testApplication {
        val root = archRoot()
        application(buildApp(root))

        var body: JsonNode = json.readTree(client.get("/api/containers").bodyAsText())
        assertEquals(1, body.size())
        assertEquals("test.app", body[0]["id"].asText())
        assertEquals(false, body[0]["analyzed"].asBoolean())
        assertEquals("idle", body[0]["state"].asText())

        Analyze.run(root, "test.app", date = "2026-08-17")

        body = json.readTree(client.get("/api/containers").bodyAsText())
        assertEquals(true, body[0]["analyzed"].asBoolean())
        assertEquals(3, body[0]["operations"].asInt())
        assertEquals("test", body[0]["system"].asText())
    }
}
