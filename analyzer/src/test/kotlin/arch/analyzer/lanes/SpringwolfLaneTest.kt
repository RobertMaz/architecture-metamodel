package arch.analyzer.lanes

import arch.analyzer.core.FactType
import arch.analyzer.core.RepoInput
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpringwolfLaneTest {

    private val fixture = Paths.get("src/test/resources/fixtures/springwolf/asyncapi.json")

    @Test
    fun `receive-операции становятся SUBSCRIBE с группой, payload и протоколом`() {
        val facts = parseAsyncApi(fixture.readText(), "victim.jar")
        val subs = facts.filter { it.type == FactType.SUBSCRIBE }

        val kafka = subs.single { it.attrs["channel"] == "orders.created" }
        assertEquals("billing", kafka.attrs["group"])
        assertEquals("OrderCreatedEvent", kafka.attrs["payload"])
        assertEquals("kafka", kafka.attrs["protocol"])
        assertEquals(0.9, kafka.confidence)
        assertTrue(kafka.source.startsWith("victim.jar!"), "source: ${kafka.source}")

        val amqp = subs.single { it.attrs["channel"] == "billing-queue" }
        assertEquals("amqp", amqp.attrs["protocol"])
        assertEquals("BillingCommand", amqp.attrs["payload"])
    }

    @Test
    fun `нерезолвнутый плейсхолдер топика — низкая уверенность и externalConfig`() {
        val facts = parseAsyncApi(fixture.readText(), "victim.jar")
        val ext = facts.single { it.type == FactType.SUBSCRIBE && it.attrs["externalConfig"] == "true" }
        assertEquals("\$_topic.orders_", ext.attrs["channel"])
        assertEquals(0.6, ext.confidence)
    }

    @Test
    fun `send-операции игнорируются — producers остаются за ASM`() {
        val facts = parseAsyncApi(fixture.readText(), "victim.jar")
        assertEquals(3, facts.filter { it.type == FactType.SUBSCRIBE }.size)
        assertTrue(facts.none { it.type == FactType.PUBLISH })
    }

    @Test
    fun `e2e скан демо-жертвы — если собраны сканер и жертва`() {
        val lane = SpringwolfLane(scannerDir = Paths.get("springwolf-scanner"))
        val jar = Paths.get("../workspace/_pilots/springwolf-exp/demo-kafka-app/target/demo-kafka-app-0.0.1.jar")
        val input = RepoInput("demo.kafka", Paths.get("."), jar = jar)
        org.junit.jupiter.api.Assumptions.assumeTrue(lane.applicable(input), "сканер или демо-жертва не собраны — пропуск")

        val subs = lane.extract(input).filter { it.type == FactType.SUBSCRIBE }
        val kafka = subs.single { it.attrs["channel"] == "orders.created" }
        assertEquals("billing", kafka.attrs["group"])
        assertEquals("kafka", kafka.attrs["protocol"])
        val amqp = subs.single { it.attrs["channel"] == "billing-queue" }
        assertEquals("amqp", amqp.attrs["protocol"])
    }

    @Test
    fun `не применима без jar или несобранного сканера`() {
        val lane = SpringwolfLane(scannerDir = Paths.get("/nonexistent"))
        val withJar = RepoInput("x", Paths.get("."), jar = fixture) // сканер не собран
        assertTrue(!lane.applicable(withJar))
        val noJar = RepoInput("x", Paths.get("."))
        assertTrue(!lane.applicable(noJar))
    }
}
