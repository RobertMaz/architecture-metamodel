package arch.analyzer.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceholdersTest {

    private fun repo(build: (Path) -> Unit): Path {
        val dir = Files.createTempDirectory("phr")
        build(dir)
        return dir
    }

    @Test
    fun `цепочка источников - application, профили, bootstrap, helm`() {
        val dir = repo {
            val res = it.resolve("src/main/resources").createDirectories()
            res.resolve("application.yml").writeText("topic:\n  orders: orders.default\nurl:\n  billing: http://billing\n")
            res.resolve("application-prod.yml").writeText("topic:\n  orders: orders.prod\n  payments: payments\n")
            res.resolve("bootstrap.yml").writeText("boot:\n  key: from-bootstrap\n")
            it.resolve("helm").createDirectories().resolve("values.yaml").writeText("helm:\n  key: from-helm\n")
        }
        val r = PlaceholderResolver.load(dir, null)

        assertEquals("orders.default", r.resolve("\${topic.orders}"), "application.yml выигрывает у профиля")
        assertEquals("payments", r.resolve("\${topic.payments}"), "профиль добавляет недостающее")
        assertEquals("from-bootstrap", r.resolve("\${boot.key}"))
        assertEquals("from-helm", r.resolve("\${helm.key}"))
        assertEquals("http://billing/api", r.resolve("\${url.billing}/api"), "подстановка внутри строки")
        assertEquals("\${no.such.key}", r.resolve("\${no.such.key}"), "нерезолвнутое остаётся как есть")
        assertEquals("fallback", r.resolve("\${no.such.key:fallback}"), "инлайн-дефолт")
    }

    @Test
    fun `relaxed binding - env-имя находит spring-ключ`() {
        val dir = repo {
            it.resolve("src/main/resources").createDirectories()
                .resolve("application.yml").writeText("topic:\n  orders-out: orders\n")
        }
        val r = PlaceholderResolver.load(dir, null)
        assertEquals("orders", r.resolve("\${TOPIC_ORDERSOUT}"))
    }

    @Test
    fun `внешний конфиг из repos yml - последний в цепочке`() {
        val extra = Files.createTempDirectory("extra")
        extra.resolve("vars.yml").writeText("ansible:\n  topic: from-ansible\n")
        val dir = repo { it.resolve("src/main/resources").createDirectories() }
        val r = PlaceholderResolver.load(dir, extra)
        assertEquals("from-ansible", r.resolve("\${ansible.topic}"))
    }

    @Test
    fun `resolveFacts - топик из конфига, суффикс окружения, DLQ и cap уверенности`() {
        val dir = repo {
            it.resolve("src/main/resources").createDirectories()
                .resolve("application.yml").writeText("topic:\n  orders: orders-prod\nbilling:\n  url: http://billing-svc\n")
        }
        val r = PlaceholderResolver.load(dir, null)
        val ev = Evidence(
            "lst", InputRef("git", "/repo"),
            listOf(
                fact(FactType.PUBLISH, "src/P.kt#p", 0.85, "channel" to "\${topic.orders}", "protocol" to "kafka"),
                fact(FactType.SUBSCRIBE, "src/L.kt#on", 0.85, "channel" to "orders-prod.DLT", "group" to "\${group.id:billing}"),
                fact(FactType.PUBLISH, "src/X.kt#x", 0.85, "channel" to "\${topic.unknown}"),
                fact(FactType.OUTGOING_CALL, "src/C.kt#c", 0.85, "method" to "GET", "urlTemplate" to "\${billing.url}/api/pay", "path" to "/api/pay"),
            ),
        )
        val out = resolveFacts(ev, r).facts

        val pub = out.single { it.source == "src/P.kt#p" }
        assertEquals("orders", pub.attrs["channel"], "резолв + суффикс окружения срезан: $pub")
        assertEquals("prod", pub.attrs["envSuffix"])

        val dlt = out.single { it.source == "src/L.kt#on" }
        assertEquals("orders.DLT", dlt.attrs["channel"], "суффикс окружения срезан и в DLT-имени")
        assertEquals("dlq", dlt.attrs["channelRole"])
        assertEquals("billing", dlt.attrs["group"], "инлайн-дефолт у group")

        val unknown = out.single { it.source == "src/X.kt#x" }
        assertEquals("\${topic.unknown}", unknown.attrs["channel"])
        assertEquals("true", unknown.attrs["externalConfig"], "значение во внешнем конфиге — в триаж")
        assertEquals(0.6, unknown.confidence, "cap уверенности")

        val call = out.single { it.type == FactType.OUTGOING_CALL }
        assertEquals("http://billing-svc/api/pay", call.attrs["urlTemplate"])
        assertEquals("billing-svc", call.attrs["host"], "host дорезолвлен после подстановки")
    }
}
