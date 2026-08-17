package arch.analyzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FactJsonTest {

    private fun sampleEvidence(shuffled: Boolean): Evidence {
        val f1 = fact(
            FactType.ENDPOINT, "src/A.java#L10", 0.95,
            "method" to "GET", "path" to "/owners/{id}",
        )
        val f2 = fact(
            FactType.STORE_ACCESS, "src/B.java#L1", 0.9,
            "kind" to "jdbc", "address" to "jdbc:hsqldb:mem:db", "access" to "readwrite",
        )
        val facts = if (shuffled) listOf(f2, f1) else listOf(f1, f2)
        return Evidence(lane = "source", input = InputRef(kind = "git", path = "/repo"), facts = facts)
    }

    @Test
    fun `сериализация канонична - порядок фактов и ключей не влияет на байты`() {
        val a = Json.write(sampleEvidence(shuffled = false).canonical())
        val b = Json.write(sampleEvidence(shuffled = true).canonical())
        assertEquals(a, b)
        assertTrue(a.endsWith("\n"), "файл должен кончаться переводом строки")
    }

    @Test
    fun `write-read-write - неподвижная точка`() {
        val text = Json.write(sampleEvidence(shuffled = false).canonical())
        val back = Json.read(text, Evidence::class.java)
        assertEquals(text, Json.write(back))
    }

    @Test
    fun `атрибуты факта отсортированы по ключу`() {
        val f = fact(FactType.ENDPOINT, "s", 0.5, "z" to "1", "a" to "2", "m" to "3")
        assertEquals(listOf("a", "m", "z"), f.attrs.keys.toList())
    }
}
