package arch.analyzer.server

import arch.analyzer.llm.FakeLlm
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class HypothesesTest {

    private val json = ObjectMapper()

    private fun archRoot(): Path {
        val root = Files.createTempDirectory("hyp")
        val fixture = Paths.get("src/test/resources/fixtures/mvc-app").toAbsolutePath()
        root.resolve("registry").createDirectories()
        root.resolve("registry/repos.yml").writeText("repos:\n  shop.billing:\n    repo: r\n    path: $fixture\n")
        root.resolve("registry/unresolved.json").writeText(
            """{"unresolved":[{"stubId":"unknown.legacy_billing","signature":{"hosts":["legacy-billing"]},"observedEndpoints":[],"callers":[],"candidates":[]}]}""",
        )
        return root
    }

    @Test
    fun `гипотезы с валидацией container по известным`() = testApplication {
        val fake = FakeLlm(
            """{"hypotheses":[{"name":"Биллинг","container":"shop.billing","confidence":0.9},""" +
                """{"name":"Левак","container":"не.существует","confidence":0.4}]}""",
        )
        application(buildApp(archRoot(), llm = fake))
        val body = json.readTree(client.get("/api/unresolved/unknown.legacy_billing/hypotheses").bodyAsText())
        assertEquals(true, body["configured"].asBoolean())
        assertEquals(2, body["hypotheses"].size())
        assertEquals("shop.billing", body["hypotheses"][0]["container"].asText())
        assertEquals(0.7, body["hypotheses"][0]["confidence"].asDouble(), "потолок 0.7")
        assertEquals(true, body["hypotheses"][1]["container"] == null || body["hypotheses"][1]["container"].isNull)
    }

    @Test
    fun `без llm - configured false`() = testApplication {
        application(buildApp(archRoot(), llm = null))
        val body = json.readTree(client.get("/api/unresolved/unknown.legacy_billing/hypotheses").bodyAsText())
        assertEquals(false, body["configured"].asBoolean())
    }
}
