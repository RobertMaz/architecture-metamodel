package arch.analyzer.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `онбординг контейнера не теряет config у существующих записей`() = testApplication {
        val root = archRoot()
        val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()
        root.resolve("registry/repos.yml").writeText(
            "repos:\n  zeta.old:\n    repo: r\n    path: $fixture\n    config: /etc/ansible/vars\n",
        )
        application(buildApp(root))

        val rs = client.post("/api/containers") {
            header("Content-Type", "application/json")
            setBody("""{"id":"zeta.app","repo":"r","path":"$fixture"}""")
        }
        assertEquals(HttpStatusCode.Created, rs.status)
        val yml = root.resolve("registry/repos.yml").readText()
        assertTrue(yml.contains("config: /etc/ansible/vars"), yml)
    }

    /** Корень с заведённым контейнером zeta.app и всеми производными артефактами. */
    private fun rootWithContainer(): Path {
        val root = archRoot()
        val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()
        root.resolve("registry/systems.yml").writeText(
            "systems:\n" +
                "  - id: beta\n    kind: system\n    title: B\n" +
                "  - id: zeta\n    kind: system\n    title: Z\n",
        )
        root.resolve("registry/repos.yml").writeText(
            "repos:\n  zeta.app:\n    repo: https://github.com/a/b\n    path: $fixture\n" +
                "  zeta.other:\n    repo: r\n    path: $fixture\n",
        )
        root.resolve("registry/aliases.yml").writeText(
            "aliases:\n  app-host: zeta.app\n  other-host: zeta.other\n",
        )
        root.resolve("registry/resolutions.yml").writeText(
            "resolutions:\n" +
                "  unknown.bar:\n    external:\n      id: ext\n      title: Ext\n" +
                "  unknown.baz:\n    assign:\n      container: zeta.app\n" +
                "  unknown.foo:\n    container: zeta.app\n" +
                "  unknown.qux:\n    container: zeta.other\n",
        )
        root.resolve("tools/api-source").createDirectories()
        root.resolve("tools/api-source/zeta.app.json").writeText("""{"container":"zeta.app","api":{"id":"api"}}""")
        root.resolve("workspace/zeta.app").createDirectories()
        root.resolve("workspace/zeta.app/status.json").writeText("""{"state":"done"}""")
        return root
    }

    @Test
    fun `удаление контейнера - реестры и артефакты вычищены`() = testApplication {
        val root = rootWithContainer()
        application(buildApp(root))

        val rs = client.delete("/api/containers/zeta.app")
        assertEquals(HttpStatusCode.OK, rs.status)

        val repos = root.resolve("registry/repos.yml").readText()
        assertFalse(repos.contains("zeta.app"), repos)
        assertTrue(repos.contains("zeta.other"), repos)
        assertFalse(root.resolve("tools/api-source/zeta.app.json").exists())
        assertFalse(root.resolve("workspace/zeta.app").exists())

        val aliases = root.resolve("registry/aliases.yml").readText()
        assertFalse(aliases.contains("zeta.app"), aliases)
        assertTrue(aliases.contains("other-host: zeta.other"), aliases)

        val res = root.resolve("registry/resolutions.yml").readText()
        assertFalse(res.contains("zeta.app"), res)
        assertTrue(res.contains("unknown.bar"), res)
        assertTrue(res.contains("unknown.qux"), res)

        // повторное удаление — контейнера больше нет
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/containers/zeta.app").status)
    }

    @Test
    fun `перенос контейнера в другую систему - id и все ссылки переезжают`() = testApplication {
        val root = rootWithContainer()
        application(buildApp(root))

        val rs = client.post("/api/containers/zeta.app/move") {
            header("Content-Type", "application/json")
            setBody("""{"system":"beta"}""")
        }
        assertEquals(HttpStatusCode.OK, rs.status)

        val repos = root.resolve("registry/repos.yml").readText()
        assertTrue(repos.contains("beta.app:"), repos)
        assertFalse(repos.contains("zeta.app"), repos)
        assertTrue(repos.contains("zeta.other"), repos)

        assertFalse(root.resolve("tools/api-source/zeta.app.json").exists())
        val doc = root.resolve("tools/api-source/beta.app.json").readText()
        assertTrue(doc.contains("\"container\" : \"beta.app\"") || doc.contains("\"container\":\"beta.app\""), doc)

        assertFalse(root.resolve("workspace/zeta.app").exists())
        assertTrue(root.resolve("workspace/beta.app/status.json").exists())

        val aliases = root.resolve("registry/aliases.yml").readText()
        assertTrue(aliases.contains("app-host: beta.app"), aliases)
        assertTrue(aliases.contains("other-host: zeta.other"), aliases)

        val res = root.resolve("registry/resolutions.yml").readText()
        assertTrue(res.contains("container: beta.app"), res)
        assertFalse(res.contains("zeta.app"), res)
        assertTrue(res.contains("container: zeta.other"), res)
    }

    @Test
    fun `перенос - валидации системы, конфликта и 404`() = testApplication {
        val root = rootWithContainer()
        val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()
        // занятый целевой id: beta.app уже существует
        root.resolve("registry/repos.yml").writeText(
            "repos:\n  beta.app:\n    repo: r\n    path: $fixture\n" +
                "  zeta.app:\n    repo: r\n    path: $fixture\n",
        )
        application(buildApp(root))

        // неизвестная система
        var rs = client.post("/api/containers/zeta.app/move") {
            header("Content-Type", "application/json")
            setBody("""{"system":"nope"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rs.status)

        // та же система
        rs = client.post("/api/containers/zeta.app/move") {
            header("Content-Type", "application/json")
            setBody("""{"system":"zeta"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, rs.status)

        // целевой id занят
        rs = client.post("/api/containers/zeta.app/move") {
            header("Content-Type", "application/json")
            setBody("""{"system":"beta"}""")
        }
        assertEquals(HttpStatusCode.Conflict, rs.status)

        // неизвестный контейнер
        rs = client.post("/api/containers/ghost.app/move") {
            header("Content-Type", "application/json")
            setBody("""{"system":"beta"}""")
        }
        assertEquals(HttpStatusCode.NotFound, rs.status)
    }
}
