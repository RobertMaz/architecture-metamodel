package arch.analyzer.lanes

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import arch.analyzer.core.fact
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Полка config: application*.yml|.properties + build-файлы (pom/gradle).
 * Извлекает то, что код не скажет: адреса сторов, имя приложения, base-URL'ы
 * внешних вызовов, рёбра в инфраструктуру Spring Cloud (config-server, discovery).
 * Профильные файлы (application-*.yml) не сливаются — фиксируются атрибутом profiles.
 */
class ConfigLane : Lane {
    override val name = "config"

    private fun resourcesDir(input: RepoInput): Path = input.repoDir.resolve("src/main/resources")

    /** Рекурсивно все application* и bootstrap* под src/main/resources (п. 7 плана). */
    private fun configFiles(input: RepoInput): List<Path> {
        val dir = resourcesDir(input)
        if (!dir.exists()) return emptyList()
        return Files.walk(dir).use { s ->
            s.filter { it.isRegularFile() }
                .filter { it.name.matches(Regex("(application|bootstrap)(-[\\w]+)?\\.(yml|yaml|properties)")) }
                .sorted()
                .toList()
        }
    }

    private val buildFileNames = setOf("pom.xml", "build.gradle", "build.gradle.kts", "libs.versions.toml")

    private fun buildFiles(input: RepoInput): List<Path> {
        if (!input.repoDir.exists()) return emptyList()
        return Files.walk(input.repoDir, 3).use { s ->
            s.filter { it.isRegularFile() && it.name in buildFileNames }
                .filter { p ->
                    input.repoDir.relativize(p)
                        .none { seg -> seg.toString() in setOf("src", "target", "build", "node_modules") }
                }
                .sorted()
                .toList()
        }
    }

    override fun applicable(input: RepoInput): Boolean =
        configFiles(input).isNotEmpty() || buildFiles(input).isNotEmpty()

    override fun extract(input: RepoInput): List<Fact> {
        val facts = mutableListOf<Fact>()
        val infra = mutableListOf<InfraSignal>()
        val defaults = configFiles(input).filter { !it.name.contains("-") }
        val profiles = configFiles(input).filter { it.name.contains("-") }
            .map { it.name.substringAfter("-").substringBeforeLast(".") }
            .sorted()

        for (file in defaults) {
            val rel = input.repoDir.relativize(file).toString().replace('\\', '/')
            val docs = readDocs(file)
            facts += recognize(flatten(docs), rel)
            facts += gatewayRoutes(docs, rel)
            infra += infraSignals(docs, rel)
        }
        infra += dependencySignals(input)
        facts += infraFacts(infra)
        if (profiles.isNotEmpty()) {
            facts += fact(
                FactType.CONTAINER_HINT, "src/main/resources", 0.9,
                "profiles" to profiles.joinToString(","),
            )
        }
        return facts
    }

    /** Парсинг общий с PlaceholderResolver — arch.analyzer.core.ConfigParsing. */
    private fun readDocs(file: Path) = arch.analyzer.core.ConfigParsing.readDocs(file)

    private fun flatten(docs: List<com.fasterxml.jackson.databind.JsonNode>) =
        arch.analyzer.core.ConfigParsing.flatten(docs)

    /**
     * Маршруты Spring Cloud Gateway — рёбра, живущие в yml, а не в коде.
     * Оба пути конфига: spring.cloud.gateway.routes и .gateway.server.webflux.routes.
     */
    private fun gatewayRoutes(docs: List<com.fasterxml.jackson.databind.JsonNode>, source: String): List<Fact> {
        val facts = mutableListOf<Fact>()
        for (doc in docs) {
            val gateway = doc.path("spring").path("cloud").path("gateway")
            for (routes in listOf(gateway.path("routes"), gateway.path("server").path("webflux").path("routes"))) {
                if (!routes.isArray) continue
                for (r in routes) {
                    val uri = r.path("uri").asText("")
                    if (uri.isEmpty()) continue
                    val host = when {
                        uri.startsWith("lb://") -> uri.removePrefix("lb://")
                        uri.startsWith("http://") || uri.startsWith("https://") -> hostOf(uri) ?: continue
                        else -> continue
                    }
                    val path = r.path("predicates").firstOrNull { it.asText().startsWith("Path=") }
                        ?.asText()?.removePrefix("Path=")
                    val attrs = mutableListOf("host" to host, "urlTemplate" to uri)
                    path?.let { attrs += "route" to it }
                    facts += fact(FactType.OUTGOING_CALL, source, 0.9, *attrs.toTypedArray())
                }
            }
        }
        return facts
    }

    /**
     * Сигнал ребра в инфраструктуру Spring Cloud (эвристики украдены у Code2DFD):
     * роль цели известна всегда, адрес — не всегда (зависимость в pom без yml-адреса).
     */
    private data class InfraSignal(
        val role: String,
        val prop: String,
        val url: String?,
        val source: String,
        val confidence: Double,
    )

    /** Ключи yml без регистра и дефисов: serviceUrl == service-url. */
    private fun normKey(k: String) = k.lowercase().replace("-", "")

    /** `${VAR:default}` -> default; `${VAR}` без дефолта остаётся как есть (значение во внешнем конфиге). */
    private fun resolveInlineDefault(v: String) =
        v.replace(Regex("\\$\\{[^:{}]+:([^{}]*)}")) { it.groupValues[1] }

    private fun isLocalHost(host: String) =
        host in setOf("localhost", "127.0.0.1", "::1", "host.docker.internal")

    /** config-server, discovery и git-репозиторий конфигов из одного yml-документа. */
    private fun infraSignals(docs: List<com.fasterxml.jackson.databind.JsonNode>, source: String): List<InfraSignal> {
        val signals = mutableListOf<InfraSignal>()
        for (doc in docs) {
            val flat = flatten(listOf(doc))
            flat["spring.config.import"]?.split(",")?.forEach { imp ->
                val v = imp.trim()
                if (v.contains("configserver:")) {
                    signals += InfraSignal(
                        "config-server", "spring.config.import",
                        v.substringAfter("configserver:"), source, 0.9,
                    )
                }
            }
            flat["spring.cloud.config.uri"]?.split(",")?.forEach {
                signals += InfraSignal("config-server", "spring.cloud.config.uri", it.trim(), source, 0.9)
            }
            for ((k, v) in flat) {
                if (normKey(k) != "eureka.client.serviceurl.defaultzone") continue
                v.split(",").map(String::trim).filter { it.isNotEmpty() }.forEach {
                    signals += InfraSignal("discovery", "eureka.client.serviceUrl.defaultZone", it, source, 0.9)
                }
            }
            flat["spring.cloud.config.server.git.uri"]?.let {
                signals += InfraSignal("config-repo", "spring.cloud.config.server.git.uri", it, source, 0.9)
            }
        }
        return signals
    }

    /**
     * Зависимости в build-файлах: наличие клиента без адреса. `@EnableDiscoveryClient`
     * отдельно не ищем — аннотация живёт в той же зависимости, а с Spring Cloud 2022+
     * клиент активен и без неё.
     */
    private fun dependencySignals(input: RepoInput): List<InfraSignal> {
        val signals = mutableListOf<InfraSignal>()
        for (bf in buildFiles(input)) {
            val text = bf.readText()
            val rel = input.repoDir.relativize(bf).toString()
            if (text.contains("spring-cloud-starter-config") || text.contains("spring-cloud-config-client")) {
                signals += InfraSignal("config-server", "spring-cloud-starter-config", null, rel, 0.85)
            }
            if (text.contains("eureka-client")) {
                signals += InfraSignal("discovery", "spring-cloud-starter-netflix-eureka-client", null, rel, 0.85)
            }
        }
        return signals
    }

    /**
     * Сигналы -> факты, по одной цели на роль: адресные варианты побеждают
     * зависимость, не-localhost-хосты побеждают localhost (иначе все сервисы
     * склеились бы в один stub «localhost»). Цель без адреса несёт только role —
     * резолв по алиасам или в триаж.
     */
    private fun infraFacts(signals: List<InfraSignal>): List<Fact> {
        val facts = mutableListOf<Fact>()
        for ((role, group) in signals.groupBy { it.role }.toSortedMap()) {
            val urled = group.filter { it.url != null }.map { s ->
                val url = resolveInlineDefault(s.url!!)
                Triple(s, url, hostOf(url)?.takeIf { !isLocalHost(it) })
            }
            val chosen = urled.filter { it.third != null }.distinctBy { it.third }
                .ifEmpty { urled.distinctBy { it.second }.take(1) }
            if (chosen.isNotEmpty()) {
                for ((s, url, host) in chosen) {
                    val attrs = mutableListOf("role" to role, "prop" to s.prop, "urlTemplate" to url)
                    host?.let { attrs += "host" to it }
                    facts += fact(FactType.OUTGOING_CALL, s.source, s.confidence, *attrs.toTypedArray())
                }
            } else {
                val dep = group.first()
                facts += fact(FactType.OUTGOING_CALL, dep.source, dep.confidence, "role" to role, "prop" to dep.prop)
            }
        }
        return facts
    }

    private fun jdbcTechnology(url: String): String {
        val m = Regex("^jdbc:([a-z0-9]+):").find(url) ?: return "JDBC"
        return when (m.groupValues[1]) {
            "mysql", "mariadb" -> "MySQL"
            "postgresql" -> "PostgreSQL"
            "hsqldb" -> "HSQLDB"
            "h2" -> "H2"
            "oracle" -> "Oracle"
            "sqlserver" -> "SQL Server"
            else -> "JDBC"
        }
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()

    private fun recognize(flat: Map<String, String>, source: String): List<Fact> {
        val facts = mutableListOf<Fact>()

        flat["spring.application.name"]?.let {
            facts += fact(FactType.CONTAINER_HINT, source, 0.95, "appName" to it)
        }
        (flat["spring.datasource.url"] ?: flat["spring.r2dbc.url"])?.let { url ->
            facts += fact(
                FactType.STORE_ACCESS, source, 0.9,
                "kind" to "jdbc", "address" to url, "technology" to jdbcTechnology(url),
            )
        }
        (flat["spring.data.redis.host"] ?: flat["spring.redis.host"])?.let { host ->
            facts += fact(
                FactType.STORE_ACCESS, source, 0.85,
                "kind" to "redis", "address" to host, "technology" to "Redis",
            )
        }
        if (flat.containsKey("spring.kafka.bootstrap-servers")) {
            facts += fact(FactType.CONTAINER_HINT, source, 0.9, "kafka" to "true")
        }
        if (flat.containsKey("spring.rabbitmq.host") || flat.containsKey("spring.rabbitmq.addresses")) {
            facts += fact(FactType.CONTAINER_HINT, source, 0.9, "rabbit" to "true")
        }

        // Свойства *.url|*.uri|*.base-url с http(s) — намёк на исходящий вызов.
        // spring.* исключаем: это инфраструктура (datasource, cloud и т.п.), не вызовы.
        for ((key, value) in flat) {
            if (key.startsWith("spring.")) continue
            if (!key.matches(Regex(".*\\.(url|uri|base-url)$"))) continue
            if (!value.startsWith("http://") && !value.startsWith("https://")) continue
            val f = mutableListOf("urlTemplate" to value, "prop" to key)
            hostOf(value)?.let { f += "host" to it }
            facts += fact(FactType.OUTGOING_CALL, source, 0.6, *f.toTypedArray())
        }
        return facts
    }
}
