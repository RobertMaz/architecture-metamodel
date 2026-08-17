package arch.analyzer.llm

import arch.analyzer.core.Analyze
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Настоящий OpenAiClient ходит по HTTP в фейковый OpenAI-сервер (JDK HttpServer).
 * Второй прогон обязан отработать из кэша — сервер уже выключен.
 */
class LlmE2eTest {

    private val json = ObjectMapper()

    private fun fakeOpenAi(content: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = json.writeValueAsBytes(
                mapOf("choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to content)))),
            )
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    private fun archRoot(port: Int): Path {
        val root = Files.createTempDirectory("llm-e2e")
        val fixture = Paths.get("src/test/resources/fixtures/calls-app").toAbsolutePath()
        root.resolve("registry").createDirectories()
        root.resolve("registry/repos.yml").writeText("repos:\n  test.app:\n    repo: r\n    path: $fixture\n")
        root.resolve("registry/llm.yml").writeText(
            "llm:\n  baseUrl: http://localhost:$port/v1\n  model: fake-qwen\n",
        )
        return root
    }

    @Test
    fun `полный цикл с llm-полкой и кэшем`() {
        val answer = """{"calls":[{"method":"POST","path":"/api/v1/refunds","host":"refunds-service","line":12}],"publishes":[]}"""
        val server = fakeOpenAi(answer)
        val port = server.address.port
        val root = archRoot(port)

        val r1 = Analyze.run(root, "test.app", date = "2026-08-17")
        assertTrue("llm" in r1.lanesRun, "полки: ${r1.lanesRun}")
        val doc1 = root.resolve("tools/api-source/test.app.json").readText()
        assertTrue(doc1.contains("refunds-service"), doc1)

        server.stop(0) // сервер мёртв — второй прогон живёт на кэше
        val r2 = Analyze.run(root, "test.app", date = "2026-08-17")
        assertTrue("llm" in r2.lanesRun)
        assertEquals(doc1, root.resolve("tools/api-source/test.app.json").readText(), "байт-в-байт из кэша")
    }
}
