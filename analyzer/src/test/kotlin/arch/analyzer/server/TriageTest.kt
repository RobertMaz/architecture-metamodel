package arch.analyzer.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriageTest {

    private val json = ObjectMapper()

    private fun archRoot(): Path {
        val root = Files.createTempDirectory("triage")
        val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()
        root.resolve("registry").createDirectories()
        root.resolve("registry/repos.yml").writeText(
            "repos:\n  test.app:\n    repo: r\n    path: $fixture\n",
        )
        root.resolve("registry/unresolved.json").writeText(
            """{"unresolved":[{"stubId":"unknown.legacy_billing","signature":{"hosts":["legacy-billing"]},"observedEndpoints":[],"callers":[],"candidates":[]}]}""",
        )
        return root
    }

    @Test
    fun `unresolved отдаётся как есть`() = testApplication {
        application(buildApp(archRoot()))
        val body = json.readTree(client.get("/api/unresolved").bodyAsText())
        assertEquals("unknown.legacy_billing", body["unresolved"][0]["stubId"].asText())
    }

    @Test
    fun `resolve container - пишет resolutions и валидирует`() = testApplication {
        val root = archRoot()
        application(buildApp(root))

        // контейнер не из repos.yml — отказ
        var rs = client.post("/api/unresolved/unknown.legacy_billing/resolve") {
            header("Content-Type", "application/json")
            setBody("""{"container":"nope.app"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rs.status)

        rs = client.post("/api/unresolved/unknown.legacy_billing/resolve") {
            header("Content-Type", "application/json")
            setBody("""{"container":"test.app"}""")
        }
        assertEquals(HttpStatusCode.OK, rs.status)
        val yml = root.resolve("registry/resolutions.yml").readText()
        assertTrue(yml.contains("unknown.legacy_billing:"), yml)
        assertTrue(yml.contains("container: test.app"), yml)

        // повторное решение — 409
        rs = client.post("/api/unresolved/unknown.legacy_billing/resolve") {
            header("Content-Type", "application/json")
            setBody("""{"container":"test.app"}""")
        }
        assertEquals(HttpStatusCode.Conflict, rs.status)
    }

    @Test
    fun `resolve external - пишет блок external`() = testApplication {
        val root = archRoot()
        application(buildApp(root))
        val rs = client.post("/api/unresolved/unknown.legacy_billing/resolve") {
            header("Content-Type", "application/json")
            setBody("""{"external":{"id":"stripe","title":"Stripe","contract":"MSA-1"}}""")
        }
        assertEquals(HttpStatusCode.OK, rs.status)
        val yml = root.resolve("registry/resolutions.yml").readText()
        assertTrue(yml.contains("external:"), yml)
        assertTrue(yml.contains("id: stripe"), yml)
        assertTrue(yml.contains("contract: MSA-1"), yml)
    }

    @Test
    fun `resolve неизвестного stubId - 404`() = testApplication {
        application(buildApp(archRoot()))
        val rs = client.post("/api/unresolved/unknown.nope/resolve") {
            header("Content-Type", "application/json")
            setBody("""{"container":"test.app"}""")
        }
        assertEquals(HttpStatusCode.NotFound, rs.status)
    }
}
