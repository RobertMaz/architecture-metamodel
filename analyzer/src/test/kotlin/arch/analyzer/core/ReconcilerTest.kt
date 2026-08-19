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
        val (doc, report) = reconciler.reconcile("petclinic.customers", listOf(source, config), meta)

        assertEquals(emptyList(), report.conflicts, "разные entities — объединение, а не конфликт")
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
    fun `байткод подтверждает эндпоинт из сорцов - confidence растёт, детали от source`() {
        val source = ev(
            "source",
            fact(
                FactType.ENDPOINT, "src/A.java#L10", 0.95,
                "method" to "GET", "path" to "/owners/{ownerId}", "response" to "OwnerDto",
            ),
        )
        val bytecode = ev(
            "bytecode",
            fact(FactType.ENDPOINT, "app.jar!demo.OwnerController#findOwner", 0.8, "method" to "GET", "path" to "/owners/{id}"),
        )
        val (doc, report) = reconciler.reconcile("x.y", listOf(source, bytecode), meta)

        val op = doc.operations.single()
        assertEquals("/owners/{ownerId}", op.path, "детали от приоритетной полки source")
        assertEquals("OwnerDto", op.response)
        assertEquals(0.99, op.confidence, "подтверждение двумя полками")
        assertTrue(op.source.contains("app.jar!"), "источники объединены: ${op.source}")
        assertEquals(emptyList(), report.conflicts, "нормализованный путь — не конфликт: ${report.conflicts}")
    }

    @Test
    fun `пути спеки выравниваются по якорному префиксу из servers url`() {
        val source = ev(
            "source",
            fact(FactType.ENDPOINT, "src/A.java#L1", 0.95, "method" to "GET", "path" to "/api/owners"),
            fact(FactType.ENDPOINT, "src/A.java#L2", 0.95, "method" to "GET", "path" to "/api/owners/{id}"),
        )
        val openapi = ev(
            "openapi",
            fact(FactType.ENDPOINT, "openapi.yml", 0.95, "method" to "GET", "path" to "/owners", "specServerPath" to "/petclinic/api", "summary" to "Все владельцы"),
            fact(FactType.ENDPOINT, "openapi.yml", 0.95, "method" to "GET", "path" to "/owners/{ownerId}", "specServerPath" to "/petclinic/api"),
            fact(FactType.ENDPOINT, "openapi.yml", 0.95, "method" to "POST", "path" to "/owners", "specServerPath" to "/petclinic/api"),
        )
        val (doc, _) = reconciler.reconcile("x.y", listOf(source, openapi), meta)

        val sigs = doc.operations.map { "${it.method} ${it.path}" }.sorted()
        // имя параметра — от приоритетной полки openapi ({ownerId}), идентичность от него не зависит
        assertEquals(listOf("GET /api/owners", "GET /api/owners/{ownerId}", "POST /api/owners"), sigs, "дублей нет: /api выбран якорем")
        val owners = doc.operations.single { it.method == "GET" && it.path == "/api/owners" }
        assertEquals("Все владельцы", owners.summary, "детали спеки слились")
        assertEquals(1.0, owners.confidence, "подтверждение двумя полками (0.95+0.95 -> округление к 1.0)")
        // непересекающийся эндпоинт спеки тоже переехал под якорь
        assertTrue(doc.operations.any { it.method == "POST" && it.path == "/api/owners" })
    }

    @Test
    fun `runtime побеждает source в деталях`() {
        val source = ev(
            "source",
            fact(FactType.STORE_ACCESS, "src/R.java#L1", 0.9, "kind" to "jdbc", "address" to "", "access" to "readwrite"),
        )
        val runtime = ev(
            "runtime",
            fact(FactType.STORE_ACCESS, "actuator:/env", 0.97, "kind" to "jdbc", "address" to "jdbc:mysql://db/x"),
        )
        val (doc, _) = reconciler.reconcile("x.y", listOf(source, runtime), meta)
        assertEquals("jdbc:mysql://db/x", doc.stores.single().address)
        assertTrue(doc.stores.single().confidence > 0.97)
    }

    @Test
    fun `llm-осмысление заполняет пустой summary и description, не перетирая source`() {
        val source = ev(
            "source",
            fact(FactType.ENDPOINT, "src/A.java#L10", 0.95, "method" to "GET", "path" to "/vets"),
        )
        val llm = ev(
            "llm",
            fact(FactType.ENDPOINT, "llm:enrich", 0.6, "method" to "GET", "path" to "/vets", "summary" to "Список ветеринаров"),
            fact(FactType.CONTAINER_HINT, "llm:enrich", 0.6, "description" to "Справочник ветеринаров"),
        )
        val (doc, _) = reconciler.reconcile("petclinic.vets", listOf(source, llm), meta)
        assertEquals("Список ветеринаров", doc.operations.single().summary)
        assertEquals("Справочник ветеринаров", doc.containerInfo.description)
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

    @Test
    fun `springwolf сливается с bytecode по каналу и приоритетнее в деталях`() {
        val springwolf = ev(
            "springwolf",
            fact(
                FactType.SUBSCRIBE, "victim.jar!orders.created_receive_on", 0.9,
                "channel" to "orders.created", "group" to "billing",
                "payload" to "OrderCreatedEvent", "protocol" to "kafka",
            ),
        )
        val bytecode = ev(
            "bytecode",
            fact(FactType.SUBSCRIBE, "app.jar!demo.Listener#on", 0.8, "channel" to "orders.created", "group" to "old-group"),
        )
        val (doc, report) = reconciler.reconcile("x.y", listOf(bytecode, springwolf), meta)

        val sub = doc.subscribes.single()
        assertEquals("billing", sub.group, "детали — от приоритетной полки springwolf")
        assertEquals("OrderCreatedEvent", sub.payload)
        assertEquals("kafka", sub.protocol)
        assertTrue(sub.confidence > 0.9, "две полки подтвердили: ${sub.confidence}")
        assertTrue(report.conflicts.any { "group" in it }, "расхождение group — в отчёт")
    }

    @Test
    fun `channelRole и protocol доезжают до subscribes и publishes`() {
        val e = ev(
            "lst",
            fact(
                FactType.SUBSCRIBE, "src/L.kt#on", 0.85,
                "channel" to "orders.DLT", "channelRole" to "dlq", "protocol" to "kafka",
            ),
            fact(
                FactType.PUBLISH, "src/P.kt#p", 0.85,
                "channel" to "orders-retry", "channelRole" to "retry", "protocol" to "kafka",
            ),
        )
        val (doc, _) = reconciler.reconcile("x.y", listOf(e), meta)

        assertEquals("dlq", doc.subscribes.single().channelRole)
        assertEquals("retry", doc.publishes.single().channelRole)
    }

    @Test
    fun `канал-плейсхолдер отбрасывается с точкой внимания, честные каналы остаются`() {
        val e = ev(
            "lst",
            fact(FactType.PUBLISH, "src/P.kt#send", 0.85, "channel" to "{message}", "protocol" to "kafka"),
            fact(FactType.PUBLISH, "src/P.kt#p", 0.85, "channel" to "orders.created", "protocol" to "kafka"),
            fact(FactType.PUBLISH, "src/V.kt#v", 0.85, "channel" to "\${app.topics.sent}", "protocol" to "kafka"),
            fact(FactType.SUBSCRIBE, "src/L.kt#on", 0.85, "channel" to "{build()}", "protocol" to "amqp"),
        )
        val (doc, report) = reconciler.reconcile("x.y", listOf(e), meta)

        assertEquals(listOf("orders.created"), doc.publishes.map { it.channel }, "плейсхолдеры не становятся каналами")
        assertTrue(doc.subscribes.isEmpty())
        assertTrue(report.lowConfidence.any { "{message}" in it && "src/P.kt#send" in it }, "точка внимания: ${report.lowConfidence}")
        assertTrue(report.lowConfidence.any { "{build()}" in it }, "и для subscribe: ${report.lowConfidence}")
        assertTrue(report.lowConfidence.any { "\${app.topics.sent}" in it }, "нерезолвнутый \${...} тоже: ${report.lowConfidence}")
    }

    @Test
    fun `плейсхолдеры регулярок и lst в urlTemplate — один вызов`() {
        val source = ev(
            "source",
            fact(FactType.OUTGOING_CALL, "src/A.java#L10", 0.7, "method" to "GET", "urlTemplate" to "{_}/owners"),
        )
        val lst = ev(
            "lst",
            fact(FactType.OUTGOING_CALL, "src/A.java#fetch", 0.85, "method" to "GET", "urlTemplate" to "{getCustomerServiceUri()}/owners"),
        )
        val (doc, _) = reconciler.reconcile("x.y", listOf(source, lst), meta)

        val call = doc.calls.single()
        assertEquals("{getCustomerServiceUri()}/owners", call.target["urlTemplate"], "детали от lst")
        assertTrue(call.confidence > 0.85, "две полки подтвердили: ${call.confidence}")
    }

    @Test
    fun `группа операции выводится из пути, когда полки её не дали`() {
        val e = ev(
            "lst",
            fact(FactType.ENDPOINT, "src/A.kt#a", 0.85, "method" to "POST", "path" to "/api/v1/pos/link"),
            fact(FactType.ENDPOINT, "src/B.kt#b", 0.85, "method" to "GET", "path" to "/private/api/v2/terminal/{id}"),
            fact(FactType.ENDPOINT, "src/C.kt#c", 0.85, "method" to "GET", "path" to "/api/v1/secured/replication/run"),
            fact(FactType.ENDPOINT, "src/D.kt#d", 0.85, "method" to "GET", "path" to "/api/v1/{ownerId}/pets"),
            fact(FactType.ENDPOINT, "src/E.kt#e", 0.85, "method" to "GET", "path" to "/owners/{ownerId}"),
            fact(FactType.ENDPOINT, "src/F.kt#f", 0.85, "method" to "GET", "path" to "/"),
        )
        val (doc, _) = reconciler.reconcile("x.y", listOf(e), meta)
        val byPath = doc.operations.associate { it.path to it.group }

        assertEquals("pos", byPath["/api/v1/pos/link"])
        assertEquals("terminal", byPath["/private/api/v2/terminal/{id}"], "служебные префиксы пропущены")
        assertEquals("replication", byPath["/api/v1/secured/replication/run"], "secured пропущен")
        assertEquals("pets", byPath["/api/v1/{ownerId}/pets"], "сегмент-параметр — не группа")
        assertEquals("owners", byPath["/owners/{ownerId}"])
        assertEquals(null, byPath["/"], "нечего вывести — базовый api")
    }

    @Test
    fun `группа от полки приоритетнее выведенной из пути`() {
        val e = ev(
            "openapi",
            fact(
                FactType.ENDPOINT, "openapi.yml", 0.95,
                "method" to "GET", "path" to "/api/v1/pos/link", "group" to "payments",
            ),
        )
        val (doc, _) = reconciler.reconcile("x.y", listOf(e), meta)
        assertEquals("payments", doc.operations.single().group)
    }

    @Test
    fun `context-path из noir срезается по якорям других полок`() {
        val source = ev(
            "source",
            fact(FactType.ENDPOINT, "src/A.java#L5", 0.95, "method" to "GET", "path" to "/api/owners"),
        )
        val noir = ev(
            "noir",
            fact(
                FactType.ENDPOINT, "src/A.java:5", 0.8,
                "method" to "GET", "path" to "/petclinic/api/owners", "contextPrefix" to "/petclinic",
            ),
        )
        val (doc, _) = reconciler.reconcile("x.y", listOf(source, noir), meta)

        val op = doc.operations.single()
        assertEquals("/api/owners", op.path, "context-path срезан, эндпоинты слились: ${doc.operations}")
        assertTrue(op.confidence > 0.95, "две полки подтвердили: ${op.confidence}")
    }

    @Test
    fun `contextPrefix без якорей не срезается`() {
        val noir = ev(
            "noir",
            fact(
                FactType.ENDPOINT, "src/A.java:5", 0.8,
                "method" to "GET", "path" to "/api/owners", "contextPrefix" to "/api",
            ),
        )
        val (doc, _) = reconciler.reconcile("x.y", listOf(noir), meta)
        assertEquals("/api/owners", doc.operations.single().path)
    }

    @Test
    fun `role попадает в target и различает рёбра без адреса`() {
        val e = ev(
            "config",
            fact(FactType.OUTGOING_CALL, "pom.xml", 0.85, "role" to "config-server", "prop" to "spring-cloud-starter-config"),
            fact(FactType.OUTGOING_CALL, "pom.xml", 0.85, "role" to "discovery", "prop" to "eureka-client"),
        )
        val (doc, _) = reconciler.reconcile("x.y", listOf(e), meta)

        assertEquals(2, doc.calls.size, "разные роли — разные вызовы, не склеиваются: ${doc.calls}")
        val cfg = doc.calls.single { it.target["role"] == "config-server" }
        assertEquals("spring-cloud-starter-config", cfg.target["prop"])
        assertTrue(doc.calls.any { it.target["role"] == "discovery" })
    }
}
