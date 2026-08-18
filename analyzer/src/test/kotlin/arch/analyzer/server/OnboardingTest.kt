package arch.analyzer.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
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

class OnboardingTest {

    private fun archRoot(): Path {
        val root = Files.createTempDirectory("onboarding-test")
        root.resolve("registry").createDirectories()
        root.resolve("registry/systems.yml").writeText("systems:\n  - id: zeta\n    kind: system\n    title: Z\n")
        root.resolve("registry/repos.yml").writeText("repos: {}\n")
        root.resolve("CODEOWNERS").writeText("# владение\n/tools/ @acme/architecture\n")
        return root
    }

    @Test
    fun `новая система - yml отсортирован, CODEOWNERS дополнен`() = testApplication {
        val root = archRoot()
        application(buildApp(root))

        val rs = client.post("/api/systems") {
            header("Content-Type", "application/json")
            setBody("""{"id":"alpha","title":"Альфа","kind":"system","description":"Д","owner":"@acme/team-a"}""")
        }
        assertEquals(HttpStatusCode.Created, rs.status)

        val yml = root.resolve("registry/systems.yml").readText()
        assertTrue(yml.indexOf("id: alpha") < yml.indexOf("id: zeta"), "сортировка по id:\n$yml")
        assertTrue(yml.contains("title: Альфа"))
        val owners = root.resolve("CODEOWNERS").readText()
        assertTrue(owners.contains("/model/systems/alpha/"), owners)
        assertTrue(owners.contains("@acme/team-a"), owners)
    }

    @Test
    fun `дубль системы - 409`() = testApplication {
        application(buildApp(archRoot()))
        val rs = client.post("/api/systems") {
            header("Content-Type", "application/json")
            setBody("""{"id":"zeta","title":"Z","kind":"system","owner":"@a/b"}""")
        }
        assertEquals(HttpStatusCode.Conflict, rs.status)
    }

    @Test
    fun `новый контейнер - валидация системы и пути`() = testApplication {
        val root = archRoot()
        application(buildApp(root))
        val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()

        // неизвестная система
        var rs = client.post("/api/containers") {
            header("Content-Type", "application/json")
            setBody("""{"id":"nope.app","repo":"r","path":"$fixture"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rs.status)

        // нормальный онбординг
        rs = client.post("/api/containers") {
            header("Content-Type", "application/json")
            setBody("""{"id":"zeta.app","repo":"https://github.com/a/b","path":"$fixture"}""")
        }
        assertEquals(HttpStatusCode.Created, rs.status)
        val yml = root.resolve("registry/repos.yml").readText()
        assertTrue(yml.contains("zeta.app:"), yml)
        assertTrue(yml.contains("path: \"$fixture\"") || yml.contains("path: $fixture"), yml)

        // дубль
        rs = client.post("/api/containers") {
            header("Content-Type", "application/json")
            setBody("""{"id":"zeta.app","repo":"r","path":"$fixture"}""")
        }
        assertEquals(HttpStatusCode.Conflict, rs.status)
    }
}
