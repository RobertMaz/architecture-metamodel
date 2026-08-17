package arch.analyzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcilerTest {

    private val meta = SourceMeta(repo = "acme/app", commit = "abc1234", extractedAt = "2026-08-17")
    private val reconciler = Reconciler()

    private fun ev(lane: String, vararg facts: Fact) =
        Evidence(lane, InputRef("git", "/repo"), facts.toList())

    @Test
    fun `эндпоинт из source попадает в операции со всеми полями`() {
        val e = ev(
            "source",
            fact(
                FactType.ENDPOINT, "src/A.java#L10", 0.95,
                "method" to "GET", "path" to "/owners/{ownerId}",
                "params" to "ownerId:path:int", "response" to "OwnerDto",
            ),
        )
        val (doc, _) = reconciler.reconcile("petclinic.customers", listOf(e), meta)

        assertEquals("petclinic.customers", doc.container)
        assertEquals("service", doc.containerInfo.kind)
        val op = doc.operations.single()
        assertEquals("GET", op.method)
        assertEquals("/owners/{ownerId}", op.path)
        assertEquals("ownerId:path:int", op.params)
        assertEquals("OwnerDto", op.response)
        assertEquals("src/A.java#L10", op.source)
        assertEquals(0.95, op.confidence)
        assertEquals("/owners", doc.api?.basePath)
    }

    @Test
    fun `store без адреса склеивается с адресом из config и растёт confidence`() {
        val source = ev(
            "source",
            fact(
                FactType.STORE_ACCESS, "src/OwnerRepository.java#L5", 0.9,
                "kind" to "jdbc", "address" to "", "access" to "readwrite", "entities" to "Owner",
            ),
            fact(
                FactType.STORE_ACCESS, "src/PetRepository.java#L5", 0.9,
                "kind" to "jdbc", "address" to "", "access" to "readwrite", "entities" to "Pet",
            ),
        )
        val config = ev(
            "config",
            fact(
                FactType.STORE_ACCESS, "src/main/resources/application.yml", 0.9,
                "kind" to "jdbc", "address" to "jdbc:hsqldb:mem:petclinic", "technology" to "HSQLDB",
            ),
        )
        val (doc, _) = reconciler.reconcile("petclinic.customers", listOf(source, config), meta)

        val store = doc.stores.single()
        assertEquals("jdbc:hsqldb:mem:petclinic", store.address)
        assertEquals("HSQLDB", store.technology)
        assertEquals("readwrite", store.access)
        assertEquals("Owner, Pet", store.entities)
        assertTrue(store.confidence > 0.9, "подтверждение двумя полками: ${store.confidence}")
    }

    @Test
    fun `worker - нет эндпоинтов, есть подписка`() {
        val e = ev(
            "source",
            fact(FactType.SUBSCRIBE, "src/L.java#L7", 0.9, "channel" to "order.created", "group" to "cg"),
        )
        val (doc, _) = reconciler.reconcile("shop.notifier", listOf(e), meta)
        assertEquals("worker", doc.containerInfo.kind)
        assertEquals(null, doc.api)
        assertEquals("order.created", doc.subscribes.single().channel)
    }

    @Test
    fun `детерминизм - порядок улик и фактов не влияет на байты`() {
        val e1 = ev(
            "source",
            fact(FactType.ENDPOINT, "src/A.java#L10", 0.95, "method" to "GET", "path" to "/a"),
            fact(FactType.ENDPOINT, "src/A.java#L20", 0.95, "method" to "GET", "path" to "/b"),
            fact(FactType.PUBLISH, "src/A.java#L30", 0.85, "channel" to "t1", "schema" to "S1"),
        )
        val e2 = ev(
            "config",
            fact(FactType.CONTAINER_HINT, "application.yml", 0.95, "appName" to "app"),
        )
        val a = Json.write(reconciler.reconcile("x.y", listOf(e1, e2), meta).first)
        val shuffled = listOf(
            e2,
            e1.copy(facts = e1.facts.reversed()),
        )
        val b = Json.write(reconciler.reconcile("x.y", shuffled, meta).first)
        assertEquals(a, b)
    }

    @Test
    fun `низкий confidence попадает в отчёт`() {
        val e = ev(
            "source",
            fact(FactType.OUTGOING_CALL, "src/F.java#L3", 0.6, "method" to "GET", "urlTemplate" to "http://x/api", "host" to "x"),
        )
        val (doc, report) = reconciler.reconcile("x.y", listOf(e), meta)
        assertEquals(1, doc.calls.size)
        assertEquals(1, report.lowConfidence.size)
        assertEquals(1, report.unresolvedCalls)
    }
}
