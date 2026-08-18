package arch.analyzer.lanes

import arch.analyzer.core.FactType
import arch.analyzer.core.RepoInput
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeLaneTest {

    private val mappingsJson = """
    {"contexts":{"application":{"mappings":{"dispatcherServlets":{"dispatcherServlet":[
      {"predicate":"{GET [/vets]}","details":{"requestMappingConditions":{"methods":["GET"],"patterns":["/vets"]}}},
      {"predicate":"{POST [/owners/{id}]}","details":{"requestMappingConditions":{"methods":["POST"],"patterns":["/owners/{id}"]}}},
      {"predicate":"actuator","details":{"requestMappingConditions":{"methods":["GET"],"patterns":["/actuator/health"]}}},
      {"predicate":"error","details":null}
    ]}}}}}
    """.trimIndent()

    private val envJson = """
    {"propertySources":[{"name":"applicationConfig","properties":{
      "spring.datasource.url":{"value":"jdbc:hsqldb:mem:vets"},
      "spring.application.name":{"value":"vets-service"}
    }},{"name":"masked","properties":{
      "spring.datasource.url":{"value":"******"}
    }}]}
    """.trimIndent()

    private fun fakeActuator(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        fun ctx(path: String, body: String) = server.createContext(path) { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        ctx("/actuator/health", """{"status":"UP"}""")
        ctx("/actuator/mappings", mappingsJson)
        ctx("/actuator/env", envJson)
        server.start()
        return server
    }

    @Test
    fun `actuator - эндпоинты без служебных, datasource и имя`() {
        val server = fakeActuator()
        try {
            val input = RepoInput("x", Files.createTempDirectory("r"), runtimeUrl = "http://localhost:${server.address.port}")
            val lane = RuntimeLane()
            assertTrue(lane.applicable(input))
            val facts = lane.extract(input)

            val eps = facts.filter { it.type == FactType.ENDPOINT }
            assertEquals(listOf("GET /vets", "POST /owners/{id}"), eps.map { "${it.attrs["method"]} ${it.attrs["path"]}" }.sorted())
            assertTrue(eps.all { it.confidence == 0.97 && it.source == "actuator:/mappings" })

            val store = facts.single { it.type == FactType.STORE_ACCESS }
            assertEquals("jdbc:hsqldb:mem:vets", store.attrs["address"])
            assertTrue(facts.any { it.type == FactType.CONTAINER_HINT && it.attrs["appName"] == "vets-service" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `otel - client, producer, consumer и db из файла спанов`() {
        val traces = Files.createTempDirectory("otel").resolve("spans.json")
        traces.writeText(
            """
            {"resourceSpans":[{"scopeSpans":[{"spans":[
              {"traceId":"t1","kind":3,"name":"GET","attributes":[
                {"key":"http.request.method","value":{"stringValue":"GET"}},
                {"key":"url.full","value":{"stringValue":"http://customers-service/owners/1?x=1"}}]},
              {"traceId":"t2","kind":4,"name":"send","attributes":[
                {"key":"messaging.system","value":{"stringValue":"kafka"}},
                {"key":"messaging.destination.name","value":{"stringValue":"order.created"}}]},
              {"traceId":"t3","kind":5,"name":"recv","attributes":[
                {"key":"messaging.system","value":{"stringValue":"kafka"}},
                {"key":"messaging.destination.name","value":{"stringValue":"payment.succeeded"}},
                {"key":"messaging.kafka.consumer.group","value":{"stringValue":"cg-1"}}]},
              {"traceId":"t4","kind":3,"name":"SELECT","attributes":[
                {"key":"db.system","value":{"stringValue":"mysql"}},
                {"key":"db.name","value":{"stringValue":"petclinic"}}]}
            ]}]}]}
            """.trimIndent(),
        )
        val input = RepoInput("x", Files.createTempDirectory("r"), traces = traces)
        val lane = TracesLane()
        assertTrue(lane.applicable(input))
        val facts = lane.extract(input)

        val call = facts.single { it.type == FactType.OUTGOING_CALL }
        assertEquals("GET", call.attrs["method"])
        assertEquals("customers-service", call.attrs["host"])
        assertEquals("/owners/{_}", call.attrs["path"], "id нормализован в параметр")
        assertEquals("otel:t1", call.source)

        assertEquals("order.created", facts.single { it.type == FactType.PUBLISH }.attrs["channel"])
        val sub = facts.single { it.type == FactType.SUBSCRIBE }
        assertEquals("payment.succeeded", sub.attrs["channel"])
        assertEquals("cg-1", sub.attrs["group"])

        val db = facts.single { it.type == FactType.STORE_ACCESS }
        assertEquals("jdbc", db.attrs["kind"])
        assertEquals("petclinic", db.attrs["address"])
    }

    @Test
    fun `otel json-lines - OTLP-обёртки построчно и peer service вместо localhost`() {
        val traces = Files.createTempDirectory("otel").resolve("spans.jsonl")
        val line =
            """{"resourceSpans":[{"scopeSpans":[{"spans":[{"traceId":"t9","kind":3,"attributes":[""" +
                """{"key":"http.request.method","value":{"stringValue":"GET"}},""" +
                """{"key":"url.full","value":{"stringValue":"http://localhost:9101/owners"}},""" +
                """{"key":"peer.service","value":{"stringValue":"customers-service"}}]}]}]}]}"""
        // формат logging-otlp: ResourceSpans без обёртки resourceSpans
        val bare =
            """{"resource":{"attributes":[]},"scopeSpans":[{"spans":[{"traceId":"t9","kind":3,"attributes":[""" +
                """{"key":"http.request.method","value":{"stringValue":"GET"}},""" +
                """{"key":"url.full","value":{"stringValue":"http://localhost:9101/owners"}},""" +
                """{"key":"peer.service","value":{"stringValue":"customers-service"}}]}]}]}"""
        traces.writeText("$line\n$bare\n")
        val facts = TracesLane().extract(RepoInput("x", Files.createTempDirectory("r"), traces = traces))
        val call = facts.single { it.type == FactType.OUTGOING_CALL }
        assertEquals("customers-service", call.attrs["host"], "peer.service побеждает localhost")
        assertEquals("/owners", call.attrs["path"])
    }

    @Test
    fun `otel - числовые сегменты пути нормализуются в параметр`() {
        val traces = Files.createTempDirectory("otel").resolve("s.jsonl")
        traces.writeText(
            """{"resourceSpans":[{"scopeSpans":[{"spans":[{"traceId":"t1","kind":3,"attributes":[""" +
                """{"key":"http.request.method","value":{"stringValue":"GET"}},""" +
                """{"key":"url.full","value":{"stringValue":"http://customers-service/owners/17/pets/3"}}]}]}]}]}""",
        )
        val facts = TracesLane().extract(RepoInput("x", Files.createTempDirectory("r"), traces = traces))
        assertEquals("/owners/{_}/pets/{_}", facts.single().attrs["path"], "конкретные id — это параметры")
    }

    @Test
    fun `uuid-имя БД нестабильно - адрес считается неизвестным`() {
        // Spring Boot генерит in-memory БД со случайным UUID-именем: это не адрес,
        // иначе каждый рестарт апки рождал бы новый «стор» и дифф.
        val traces = Files.createTempDirectory("otel").resolve("s.jsonl")
        traces.writeText(
            """{"resourceSpans":[{"scopeSpans":[{"spans":[{"traceId":"t1","kind":3,"attributes":[""" +
                """{"key":"db.system","value":{"stringValue":"hsqldb"}},""" +
                """{"key":"db.name","value":{"stringValue":"89cf7e86-3d29-46d2-bd6b-2326276b9d83"}}]}]}]}]}""",
        )
        val f = TracesLane().extract(RepoInput("x", Files.createTempDirectory("r"), traces = traces)).single()
        assertEquals("", f.attrs["address"], "uuid — не адрес")
        assertEquals("hsqldb", f.attrs["technology"])

        // и в actuator/env: jdbc-url с uuid-именем тоже нестабилен
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val env = """{"propertySources":[{"name":"a","properties":{"spring.datasource.url":{"value":"jdbc:hsqldb:mem:89cf7e86-3d29-46d2-bd6b-2326276b9d83"}}}]}"""
        server.createContext("/actuator/health") { ex -> val b = """{"status":"UP"}""".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.createContext("/actuator/mappings") { ex -> val b = "{}".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.createContext("/actuator/env") { ex -> val b = env.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.start()
        try {
            val facts = RuntimeLane().extract(RepoInput("x", Files.createTempDirectory("r"), runtimeUrl = "http://localhost:${server.address.port}"))
            val store = facts.single { it.type == FactType.STORE_ACCESS }
            assertEquals("", store.attrs["address"], "jdbc-url с uuid — адрес неизвестен")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `не применима без runtimeUrl и traces`() {
        assertTrue(!RuntimeLane().applicable(RepoInput("x", Files.createTempDirectory("r"))))
        assertTrue(!TracesLane().applicable(RepoInput("x", Files.createTempDirectory("r"))))
    }
}
