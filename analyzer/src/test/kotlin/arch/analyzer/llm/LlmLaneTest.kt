package arch.analyzer.llm

import arch.analyzer.core.Evidence
import arch.analyzer.core.FactType
import arch.analyzer.core.InputRef
import arch.analyzer.core.Json
import arch.analyzer.core.RepoInput
import arch.analyzer.core.fact
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmLaneTest {

    private val fixture = Paths.get("src/test/resources/fixtures/calls-app").toAbsolutePath()

    /** archRoot с evidence source-полки: все файлы фикстуры покрыты, кроме TrickyCaller. */
    private fun archRoot(): Path {
        val root = Files.createTempDirectory("llm-lane")
        val ws = root.resolve("workspace/test.app").createDirectories()
        val evidence = Evidence(
            lane = "source",
            input = InputRef("git", fixture.toString()),
            facts = listOf(
                fact(
                    FactType.OUTGOING_CALL, "src/main/java/demo/VisitsFetcher.java#L13", 0.8,
                    "method" to "GET", "host" to "visits-service",
                ),
                fact(
                    FactType.OUTGOING_CALL, "src/main/java/demo/BillingClient.java#L11", 0.9,
                    "method" to "POST", "feignName" to "billing",
                ),
            ),
        )
        ws.resolve("evidence.source.json").writeText(Json.write(evidence))
        return root
    }

    private val answer = """
        {"calls":[{"method":"POST","path":"/api/v1/refunds","host":"refunds-service","line":12}],"publishes":[]}
    """.trimIndent()

    @Test
    fun `точка внимания - только непокрытый файл, факты с потолком confidence`() {
        val root = archRoot()
        val fake = FakeLlm(answer)
        val lane = LlmLane(root, fake)
        val input = RepoInput("test.app", fixture)

        assertTrue(lane.applicable(input))
        val facts = lane.extract(input)

        assertEquals(1, fake.calls, "LLM зовётся только для TrickyCaller")
        val call = facts.single { it.type == FactType.OUTGOING_CALL }
        assertEquals("POST", call.attrs["method"])
        assertEquals("/api/v1/refunds", call.attrs["path"])
        assertEquals("refunds-service", call.attrs["host"])
        assertEquals("src/main/java/demo/TrickyCaller.java#L12", call.source)
        assertTrue(call.confidence <= 0.7)
    }

    @Test
    fun `файл, покрытый lst-полкой, в LLM не уходит`() {
        val root = archRoot()
        val lst = Evidence(
            lane = "lst",
            input = InputRef("git", fixture.toString()),
            facts = listOf(
                fact(
                    FactType.OUTGOING_CALL, "src/main/java/demo/TrickyCaller.java#TrickyCaller.call", 0.85,
                    "method" to "POST", "urlTemplate" to "http://refunds-service/api/v1/refunds",
                ),
            ),
        )
        root.resolve("workspace/test.app/evidence.lst.json").writeText(Json.write(lst))
        val fake = FakeLlm(answer)
        val facts = LlmLane(root, fake).extract(RepoInput("test.app", fixture))

        assertEquals(0, fake.calls, "всё покрыто типизированными полками — токены не тратим")
        assertTrue(facts.isEmpty())
    }

    @Test
    fun `невалидный json - одна повторная попытка, потом скип без выдумок`() {
        val root = archRoot()
        val fake = FakeLlm("это не json")
        val lane = LlmLane(root, fake)
        val facts = lane.extract(RepoInput("test.app", fixture))
        assertEquals(2, fake.calls, "ровно одна повторная попытка")
        assertEquals(0, facts.size)
        assertEquals(1, lane.failures.size)
    }
}
