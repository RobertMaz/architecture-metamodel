package arch.analyzer.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden-примеры из CONTRACTS.md. Схема id — контракт:
 * Kotlin-порт обязан давать те же id, что tools/gen-api.mjs.
 */
class IdsTest {

    @Test
    fun `opId - примеры из CONTRACTS`() {
        assertEquals("post_api_v1_orders", opId("POST", "/api/v1/orders"))
        assertEquals("get_api_v1_orders_p", opId("GET", "/api/v1/orders/{id}"))
        assertEquals("post_api_v1_orders_p_cancel", opId("POST", "/api/v1/orders/{id}/cancel"))
    }

    @Test
    fun `opId - имя параметра не входит в id`() {
        assertEquals(opId("GET", "/owners/{ownerId}"), opId("GET", "/owners/{id}"))
    }

    @Test
    fun `opId - обрезка до 80 символов`() {
        val long = "/api/" + "segment/".repeat(30)
        assertEquals(80, opId("GET", long).length)
    }

    @Test
    fun `normPath - параметры нормализуются одинаково`() {
        assertEquals(normPath("/owners/{id}/pets"), normPath("/owners/{ownerId}/pets"))
    }

    @Test
    fun slug() {
        assertEquals("jdbc_hsqldb_mem_petclinic", slug("jdbc:hsqldb:mem:petclinic"))
        assertEquals("order_created", slug("order.created"))
        assertEquals("visits_service", slug("visits-service"))
    }
}
