package arch.analyzer.lanes.source

import arch.analyzer.core.FactType
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteRecognizerTest {

    private val project = JavaProject(Paths.get("src/test/resources/fixtures/mvc-app"))
    private val facts = RouteRecognizer().recognize(project)
        .filter { it.type == FactType.ENDPOINT }

    @Test
    fun `находит три эндпоинта с префиксом класса`() {
        val sigs = facts.map { "${it.attrs["method"]} ${it.attrs["path"]}" }.sorted()
        assertEquals(
            listOf("GET /owners/legacy", "GET /owners/{ownerId}", "POST /owners"),
            sigs,
        )
    }

    @Test
    fun `path-параметр и query-параметр`() {
        val get = facts.single { it.attrs["path"] == "/owners/{ownerId}" }
        assertEquals("ownerId:path:int", get.attrs["params"])
        assertEquals("OwnerDto", get.attrs["response"])

        val post = facts.single { it.attrs["method"] == "POST" }
        assertEquals("source:query:String?", post.attrs["params"])
        assertEquals("OwnerDto", post.attrs["request"])
        assertEquals("OwnerDto", post.attrs["response"], "ResponseEntity разворачивается")
    }

    @Test
    fun `deprecated и source со строкой`() {
        val legacy = facts.single { it.attrs["path"] == "/owners/legacy" }
        assertEquals("true", legacy.attrs["deprecated"])
        assertTrue(
            legacy.source.startsWith("src/main/java/demo/OwnerController.java#L"),
            "source: ${legacy.source}",
        )
        assertEquals(0.95, legacy.confidence)
    }
}
