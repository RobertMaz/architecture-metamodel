package arch.analyzer.core

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AliasesTest {

    @Test
    fun `upsert создаёт файл и сортирует ключи`() {
        val root = Files.createTempDirectory("aliases")
        root.resolve("registry").createDirectories()

        val conflicts = Aliases(root).upsert(
            mapOf("zeta-service" to "shop.zeta", "alpha-service" to "shop.alpha"),
        )
        assertEquals(emptyList(), conflicts)
        val text = root.resolve("registry/aliases.yml").readText()
        assertTrue(text.indexOf("alpha-service") < text.indexOf("zeta-service"), text)
        assertTrue(text.contains("alpha-service: shop.alpha"), text)
    }

    @Test
    fun `ручная запись не перетирается - конфликт в отчёт`() {
        val root = Files.createTempDirectory("aliases")
        root.resolve("registry").createDirectories()
        root.resolve("registry/aliases.yml").writeText("aliases:\n  billing: shop.billing\n")

        val conflicts = Aliases(root).upsert(mapOf("billing" to "other.billing"))
        assertEquals(1, conflicts.size)
        assertTrue(root.resolve("registry/aliases.yml").readText().contains("billing: shop.billing"))
    }

    @Test
    fun `повторный upsert не меняет байты`() {
        val root = Files.createTempDirectory("aliases")
        root.resolve("registry").createDirectories()
        Aliases(root).upsert(mapOf("a" to "s.a"))
        val first = root.resolve("registry/aliases.yml").readText()
        Aliases(root).upsert(mapOf("a" to "s.a"))
        assertEquals(first, root.resolve("registry/aliases.yml").readText())
    }
}
