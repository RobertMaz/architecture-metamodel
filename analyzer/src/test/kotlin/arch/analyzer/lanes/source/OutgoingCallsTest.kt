package arch.analyzer.lanes.source

import arch.analyzer.core.FactType
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutgoingCallsTest {

    private val project = JavaProject(Paths.get("src/test/resources/fixtures/calls-app"))

    @Test
    fun `feign - метод, путь, имя и url-шаблон`() {
        val f = FeignRecognizer().recognize(project)
            .single { it.type == FactType.OUTGOING_CALL }
        assertEquals("POST", f.attrs["method"])
        assertEquals("/api/v1/invoices", f.attrs["path"])
        assertEquals("billing", f.attrs["feignName"])
        assertEquals("\${billing.url}", f.attrs["urlTemplate"])
        assertEquals(0.9, f.confidence)
    }

    @Test
    fun `webclient - литеральный uri с хостом, query отрезается`() {
        val facts = HttpClientRecognizer().recognize(project)
        val wc = facts.single { it.attrs["host"] == "visits-service" }
        assertEquals("GET", wc.attrs["method"])
        assertEquals("/pets/visits", wc.attrs["path"])
        assertEquals("http://visits-service/pets/visits", wc.attrs["urlTemplate"])
        assertEquals(0.8, wc.confidence)
    }

    @Test
    fun `resttemplate - конкатенация превращается в плейсхолдер`() {
        val facts = HttpClientRecognizer().recognize(project)
        val rt = facts.single { it.attrs["host"] == "customers-service" }
        assertEquals("GET", rt.attrs["method"])
        assertEquals("/owners/{_}", rt.attrs["path"])
        assertTrue(rt.confidence <= 0.7, "конкатенация — ниже уверенность: ${rt.confidence}")
    }
}
