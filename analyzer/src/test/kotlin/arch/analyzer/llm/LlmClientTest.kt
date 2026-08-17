package arch.analyzer.llm

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeLlm(private val answer: String = "{}") : LlmClient {
    var calls = 0
    override fun complete(system: String, user: String): String {
        calls++
        return answer
    }
}

class LlmClientTest {

    @Test
    fun `конфиг читается из llm yml, без файла - null`() {
        val root = Files.createTempDirectory("llm")
        assertNull(LlmConfig.load(root))

        root.resolve("registry").createDirectories()
        root.resolve("registry/llm.yml").writeText(
            "llm:\n  baseUrl: http://localhost:9099/v1\n  model: qwen3.5-397b\n  enrich: true\n",
        )
        val cfg = LlmConfig.load(root)!!
        assertEquals("http://localhost:9099/v1", cfg.baseUrl)
        assertEquals("qwen3.5-397b", cfg.model)
        assertEquals(true, cfg.enrich)
    }

    @Test
    fun `кэш - повторный запрос не зовёт делегата`() {
        val dir = Files.createTempDirectory("llm-cache")
        val fake = FakeLlm(answer = """{"x":1}""")
        val cached = CachedLlm(fake, dir, model = "m")

        assertEquals("""{"x":1}""", cached.complete("sys", "user"))
        assertEquals("""{"x":1}""", cached.complete("sys", "user"))
        assertEquals(1, fake.calls, "второй ответ обязан прийти из кэша")

        // другой вход — другой ключ
        cached.complete("sys", "другое")
        assertEquals(2, fake.calls)
    }
}
