package arch.analyzer.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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

class SourcesTest {

    private val json = ObjectMapper()
    private val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()

    private fun archRoot(): Path {
        val root = Files.createTempDirectory("sources-test")
        root.resolve("registry").createDirectories()
        root.resolve("registry/repos.yml").writeText(
            "repos:\n  test.app:\n    repo: r\n    path: $fixture\n",
        )
        return root
    }

    @Test
    fun `добавить jar и runtimeUrl после онбординга, пустая строка удаляет`() = testApplication {
        val root = archRoot()
        application(buildApp(root))

        var rs = client.put("/api/containers/test.app/sources") {
            header("Content-Type", "application/json")
            // Браузер шлёт Origin на всех не-GET запросах — CORS обязан пропускать PUT.
            header("Origin", "http://localhost:5174")
            setBody("""{"jar":"$fixture","runtimeUrl":"http://localhost:9966"}""")
        }
        assertEquals(HttpStatusCode.OK, rs.status)
        var yml = root.resolve("registry/repos.yml").readText()
        assertTrue(yml.contains("jar: $fixture"), yml)
        assertTrue(yml.contains("runtimeUrl: http://localhost:9966"), yml)

        // DTO отдаёт источники
        val c = json.readTree(client.get("/api/containers").bodyAsText())[0]
        assertEquals("http://localhost:9966", c["runtimeUrl"].asText())

        // пустая строка — удалить поле; незатронутые не меняются
        rs = client.put("/api/containers/test.app/sources") {
            header("Content-Type", "application/json")
            setBody("""{"jar":""}""")
        }
        assertEquals(HttpStatusCode.OK, rs.status)
        yml = root.resolve("registry/repos.yml").readText()
        assertTrue(!yml.contains("jar:"), yml)
        assertTrue(yml.contains("runtimeUrl:"), yml)
    }

    @Test
    fun `неизвестный контейнер - 404, несуществующий путь сорцов - 400`() = testApplication {
        application(buildApp(archRoot()))
        var rs = client.put("/api/containers/nope/sources") {
            header("Content-Type", "application/json")
            setBody("""{"jar":"x"}""")
        }
        assertEquals(HttpStatusCode.NotFound, rs.status)

        rs = client.put("/api/containers/test.app/sources") {
            header("Content-Type", "application/json")
            setBody("""{"path":"/нет/такой/директории"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rs.status)
    }
}
