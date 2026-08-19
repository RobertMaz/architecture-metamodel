package arch.analyzer.lanes

import arch.analyzer.core.FactType
import arch.analyzer.core.RepoInput
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LstLaneTest {

    private val lane = LstLane(extractorDir = Paths.get("lst-extractor"))
    private val fixture = Paths.get("src/test/resources/fixtures/lst-app")

    @Test
    fun `не применима без собранного экстрактора или без сорцов`() {
        val broken = LstLane(extractorDir = Paths.get("/nonexistent"))
        assertTrue(!broken.applicable(RepoInput("x", fixture)))
        assertTrue(!lane.applicable(RepoInput("x", Paths.get("/nonexistent"))))
    }

    /** Приёмка п. 6 плана: инвариант «паритет фактов Java/Kotlin». */
    @Test
    fun `e2e паритет Java и Kotlin — если экстрактор собран`() {
        val input = RepoInput("test.lst", fixture)
        org.junit.jupiter.api.Assumptions.assumeTrue(lane.applicable(input), "экстрактор не собран — пропуск")

        val facts = lane.extract(input)

        // контроллеры: одинаковая форма эндпоинта из .java и из .kt
        val endpoints = facts.filter { it.type == FactType.ENDPOINT }
        assertEquals(
            listOf("/api/j/owners/{ownerId}", "/api/k/owners/{ownerId}"),
            endpoints.mapNotNull { it.attrs["path"] }.sorted(),
            "оба контроллера: $endpoints",
        )
        assertTrue(endpoints.all { it.attrs["method"] == "GET" })

        // продюсеры: Kotlin-константа Topics.ORDERS резолвится; протокол на месте
        val pubs = facts.filter { it.type == FactType.PUBLISH }
        assertEquals(3, pubs.size, "producer из .java, .kt и @Value: $pubs")
        assertTrue(pubs.count { it.attrs["channel"] == "orders.created" } == 2)
        assertTrue(pubs.any { it.source.endsWith(".kt#KProducer.publish") && it.confidence == 0.85 }, "Kotlin typed: $pubs")

        // @Value-топик: Kotlin-эскейп \$ нормализован — честный ${...} для резолвера
        val valuePub = pubs.single { it.source.endsWith("KValueProducer.send") }
        assertEquals("\${app.topics.sent}", valuePub.attrs["channel"], "без backslash: $valuePub")

        // слушатели: Kotlin array-literal с константой и Java-массив дают один канал
        val subs = facts.filter { it.type == FactType.SUBSCRIBE }
        assertEquals(2, subs.size)
        assertTrue(subs.all { it.attrs["channel"] == "orders.created" && it.attrs["group"] == "billing" })

        // WebClient: base из create(BASE) склеен, host извлечён
        val call = facts.single { it.type == FactType.OUTGOING_CALL }
        assertEquals("http://customers-service/owners/1", call.attrs["urlTemplate"])
        assertEquals("customers-service", call.attrs["host"])
        assertEquals("/owners/1", call.attrs["path"])
        assertEquals("GET", call.attrs["method"])
    }
}
