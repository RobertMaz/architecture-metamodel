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
    fun `config-import и eureka дают рёбра в config-server и discovery`() {
        val facts = lane.extract(RepoInput("test.cloud", Paths.get("src/test/resources/fixtures/cloud-app")))
        val calls = facts.filter { it.type == FactType.OUTGOING_CALL }

        // ровно по одному ребру на роль: yml-адрес есть, дубль от зависимости в pom подавлен
        val cfg = calls.single { it.attrs["role"] == "config-server" }
        assertEquals("http://localhost:8888/", cfg.attrs["urlTemplate"], "дефолт из \${VAR:default}")
        assertEquals(null, cfg.attrs["host"], "localhost — не сигнатура цели")
        assertEquals("spring.config.import", cfg.attrs["prop"])
        assertEquals(0.9, cfg.confidence)

        val disc = calls.single { it.attrs["role"] == "discovery" }
        assertEquals("discovery-server", disc.attrs["host"], "адресный документ побеждает localhost")
        assertEquals("http://discovery-server:8761/eureka/", disc.attrs["urlTemplate"])
        assertEquals(0.9, disc.confidence)
    }

    @Test
    fun `зависимости в build-файле без адресов — рёбра по роли`() {
        val depsInput = RepoInput("test.deps", Paths.get("src/test/resources/fixtures/deps-only-app"))
        assertTrue(lane.applicable(depsInput), "полка применима и без resources: build-файл тоже конфиг")

        val calls = lane.extract(depsInput).filter { it.type == FactType.OUTGOING_CALL }
        val cfg = calls.single { it.attrs["role"] == "config-server" }
        assertEquals("spring-cloud-starter-config", cfg.attrs["prop"])
        assertEquals(null, cfg.attrs["host"])
        assertEquals(null, cfg.attrs["urlTemplate"])
        assertEquals("build.gradle", cfg.source)
        assertEquals(0.85, cfg.confidence)

        val disc = calls.single { it.attrs["role"] == "discovery" }
        assertEquals("spring-cloud-starter-netflix-eureka-client", disc.attrs["prop"])
        assertEquals(0.85, disc.confidence)
    }

    @Test
    fun `git uri у config-server — внешний config-репозиторий, сам себе не клиент`() {
        val facts = lane.extract(RepoInput("test.cfgsrv", Paths.get("src/test/resources/fixtures/config-server-app")))
        // единственное ребро: config-repo; spring-cloud-config-server не считается клиентом
        val repo = facts.single { it.type == FactType.OUTGOING_CALL }
        assertEquals("config-repo", repo.attrs["role"])
        assertEquals("github.com", repo.attrs["host"])
        assertEquals("https://github.com/acme/config-repo", repo.attrs["urlTemplate"])
        assertEquals("spring.cloud.config.server.git.uri", repo.attrs["prop"])
        assertEquals(0.9, repo.confidence)
    }

    @Test
    fun `легаси spring cloud config uri и eureka в properties`() {
        val facts = lane.extract(RepoInput("test.legacy", Paths.get("src/test/resources/fixtures/cloud-legacy-app")))
        val calls = facts.filter { it.type == FactType.OUTGOING_CALL }

        val cfg = calls.single { it.attrs["role"] == "config-server" }
        assertEquals("config.acme.io", cfg.attrs["host"])
        assertEquals("spring.cloud.config.uri", cfg.attrs["prop"])

        val disc = calls.single { it.attrs["role"] == "discovery" }
        assertEquals("eureka.acme.io", disc.attrs["host"], "kebab-case ключи eureka распознаются")
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
