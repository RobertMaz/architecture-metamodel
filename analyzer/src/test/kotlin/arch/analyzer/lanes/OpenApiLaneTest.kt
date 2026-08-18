package arch.analyzer.lanes

import arch.analyzer.core.FactType
import arch.analyzer.core.RepoInput
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiLaneTest {

    private val fixture = Paths.get("src/test/resources/fixtures/openapi-app")
    private val lane = OpenApiLane()

    @Test
    fun `явный путь к спеке - эндпоинты со всеми деталями`() {
        val input = RepoInput("x", fixture, openapi = fixture.resolve("openapi.yml"))
        assertTrue(lane.applicable(input))
        val eps = lane.extract(input).filter { it.type == FactType.ENDPOINT }

        assertEquals(
            listOf("DELETE /api/v1/orders/{id}", "GET /api/v1/orders/{id}", "POST /api/v1/orders"),
            eps.map { "${it.attrs["method"]} ${it.attrs["path"]}" }.sorted(),
        )
        val post = eps.single { it.attrs["method"] == "POST" }
        assertEquals("Создать заказ", post.attrs["summary"])
        assertEquals("CreateOrderRequest", post.attrs["request"])
        assertEquals("OrderResponse", post.attrs["response"])
        assertEquals(0.95, post.confidence)
        assertEquals("openapi.yml", post.source)

        val get = eps.single { it.attrs["method"] == "GET" }
        assertEquals("id:path:string, expand:query:string?", get.attrs["params"])

        assertEquals("true", eps.single { it.attrs["method"] == "DELETE" }.attrs["deprecated"])
        assertTrue(
            eps.all { it.attrs["specServerPath"] == "/petclinic/api" },
            "path-часть servers.url едет атрибутом — реконсилятор согласует префиксы",
        )
    }

    @Test
    fun `автодетект спеки в resources`() {
        val repo = Files.createTempDirectory("oa")
        repo.resolve("src/main/resources").createDirectories()
        fixture.resolve("openapi.yml").copyTo(repo.resolve("src/main/resources/openapi.yml"))
        val input = RepoInput("x", repo)
        assertTrue(lane.applicable(input), "спека найдена без явного указания")
        assertEquals(3, lane.extract(input).size)
    }

    @Test
    fun `не применима без спеки`() {
        assertTrue(!lane.applicable(RepoInput("x", Files.createTempDirectory("empty"))))
    }
}
