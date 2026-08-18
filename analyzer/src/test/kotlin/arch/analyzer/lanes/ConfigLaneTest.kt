package arch.analyzer.lanes

import arch.analyzer.core.FactType
import arch.analyzer.core.RepoInput
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigLaneTest {

    private val fixture = Paths.get("src/test/resources/fixtures/config-app")
    private val lane = ConfigLane()
    private val input = RepoInput(containerId = "test.app", repoDir = fixture)

    @Test
    fun `применима когда есть application yml`() {
        assertTrue(lane.applicable(input))
        assertEquals("config", lane.name)
    }

    @Test
    fun `извлекает имя приложения, datasource, kafka и url-свойства`() {
        val facts = lane.extract(input)

        val hint = facts.filter { it.type == FactType.CONTAINER_HINT }
        assertTrue(hint.any { it.attrs["appName"] == "customers-service" }, "нет appName: $hint")
        assertTrue(hint.any { it.attrs["kafka"] == "true" }, "нет kafka-хинта: $hint")

        val store = facts.single { it.type == FactType.STORE_ACCESS }
        assertEquals("jdbc", store.attrs["kind"])
        assertEquals("jdbc:hsqldb:mem:petclinic", store.attrs["address"])
        assertEquals("HSQLDB", store.attrs["technology"])
        assertEquals("src/main/resources/application.yml", store.source)

        val call = facts.single { it.type == FactType.OUTGOING_CALL }
        assertEquals("http://billing-service/api", call.attrs["urlTemplate"])
        assertEquals("billing-service", call.attrs["host"])
        assertEquals("billing.url", call.attrs["prop"])
        assertEquals(0.6, call.confidence)
    }

    @Test
    fun `не применима без конфигов`() {
        assertTrue(!lane.applicable(RepoInput("x", Paths.get("/nonexistent"))))
    }

    @Test
    fun `multi-doc yml и маршруты spring cloud gateway`() {
        val gw = Paths.get("src/test/resources/fixtures/gateway-app")
        val facts = lane.extract(RepoInput("test.gw", gw))

        // первый документ побеждает: appName из первого
        assertTrue(facts.any { it.type == FactType.CONTAINER_HINT && it.attrs["appName"] == "api-gateway" })

        val routes = facts.filter { it.type == FactType.OUTGOING_CALL && it.attrs.containsKey("route") }
        assertEquals(2, routes.size, "оба стиля пути роутов: $routes")
        val vets = routes.single { it.attrs["host"] == "vets-service" }
        assertEquals("lb://vets-service", vets.attrs["urlTemplate"])
        assertEquals("/api/vet/**", vets.attrs["route"])
        assertEquals(0.9, vets.confidence)
        assertTrue(routes.any { it.attrs["host"] == "customers-service" })
    }
}
