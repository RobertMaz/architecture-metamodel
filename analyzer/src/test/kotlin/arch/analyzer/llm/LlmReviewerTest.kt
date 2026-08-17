package arch.analyzer.llm

import arch.analyzer.core.ApiSourceDoc
import arch.analyzer.core.ContainerInfo
import arch.analyzer.core.SourceMetaOut
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmReviewerTest {

    private val doc = ApiSourceDoc(
        container = "x.y",
        source = SourceMetaOut("r", "c", "2026-08-17", "t"),
        containerInfo = ContainerInfo("service", "y", "Java"),
    )

    @Test
    fun `подозрения из валидного ответа, отсортированы`() {
        val fake = FakeLlm("""{"suspicions":["б: WebSocketConfig.java не отражён","а: GrpcService.java не отражён"]}""")
        val result = LlmReviewer(fake).review(doc, listOf("src/A.java"))
        assertEquals(2, result.size)
        assertEquals("а: GrpcService.java не отражён", result[0])
    }

    @Test
    fun `невалидный json - честная запись об ошибке, не выдумка`() {
        val fake = FakeLlm("ну такое")
        val result = LlmReviewer(fake).review(doc, emptyList())
        assertEquals(listOf("LLM-ревью не удалось: невалидный JSON"), result)
        assertEquals(2, fake.calls)
    }
}
