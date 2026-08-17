package arch.analyzer.lanes.source

import arch.analyzer.core.FactType
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class KafkaSpringDataTest {

    private val kafkaProject = JavaProject(Paths.get("src/test/resources/fixtures/kafka-app"))
    private val dataProject = JavaProject(Paths.get("src/test/resources/fixtures/data-app"))

    @Test
    fun `kafka listener - подписка с группой и payload`() {
        val f = KafkaRecognizer().recognize(kafkaProject)
            .single { it.type == FactType.SUBSCRIBE }
        assertEquals("order.created", f.attrs["channel"])
        assertEquals("billing-cg", f.attrs["group"])
        assertEquals("OrderCreated", f.attrs["payload"])
        assertEquals(0.9, f.confidence)
    }

    @Test
    fun `kafka template - публикация со схемой`() {
        val f = KafkaRecognizer().recognize(kafkaProject)
            .single { it.type == FactType.PUBLISH }
        assertEquals("payment.succeeded", f.attrs["channel"])
        assertEquals("PaymentSucceeded", f.attrs["schema"])
        assertEquals(0.85, f.confidence)
    }

    @Test
    fun `scheduled - хинт воркера`() {
        val f = KafkaRecognizer().recognize(kafkaProject)
            .single { it.type == FactType.CONTAINER_HINT }
        assertEquals("true", f.attrs["scheduled"])
    }

    @Test
    fun `spring data - repository со списком сущностей`() {
        val f = SpringDataRecognizer().recognize(dataProject)
            .single { it.type == FactType.STORE_ACCESS }
        assertEquals("jdbc", f.attrs["kind"])
        assertEquals("", f.attrs["address"])
        assertEquals("readwrite", f.attrs["access"])
        assertEquals("Owner", f.attrs["entities"])
        assertEquals(0.9, f.confidence)
    }
}
