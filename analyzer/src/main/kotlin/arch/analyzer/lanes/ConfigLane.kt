package arch.analyzer.lanes

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import arch.analyzer.core.fact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Полка config: application*.yml|.properties. Извлекает то, что код не скажет:
 * адреса сторов, имя приложения, base-URL'ы внешних вызовов.
 * Профильные файлы (application-*.yml) не сливаются — фиксируются атрибутом profiles.
 */
class ConfigLane : Lane {
    override val name = "config"

    private val yaml = ObjectMapper(YAMLFactory())

    private fun resourcesDir(input: RepoInput): Path = input.repoDir.resolve("src/main/resources")

    private fun configFiles(input: RepoInput): List<Path> {
        val dir = resourcesDir(input)
        if (!dir.exists()) return emptyList()
        return Files.list(dir).use { s ->
            s.filter { it.isRegularFile() }
                .filter { it.name.matches(Regex("application(-[\\w]+)?\\.(yml|yaml|properties)")) }
                .sorted()
                .toList()
        }
    }

    override fun applicable(input: RepoInput): Boolean = configFiles(input).isNotEmpty()

    override fun extract(input: RepoInput): List<Fact> {
        val facts = mutableListOf<Fact>()
        val defaults = configFiles(input).filter { !it.name.contains("-") }
        val profiles = configFiles(input).filter { it.name.contains("-") }
            .map { it.name.substringAfter("-").substringBeforeLast(".") }
            .sorted()

        for (file in defaults) {
            val rel = "src/main/resources/${file.name}"
            val flat = flatten(file)
            facts += recognize(flat, rel)
        }
        if (profiles.isNotEmpty()) {
            facts += fact(
                FactType.CONTAINER_HINT, "src/main/resources", 0.9,
                "profiles" to profiles.joinToString(","),
            )
        }
        return facts
    }

    /** Вложенный yml/properties -> плоская map "a.b.c" -> значение. */
    private fun flatten(file: Path): Map<String, String> {
        val out = sortedMapOf<String, String>()
        if (file.name.endsWith(".properties")) {
            val p = Properties()
            file.readText().reader().use { p.load(it) }
            for (k in p.stringPropertyNames()) out[k] = p.getProperty(k)
            return out
        }
        val root = yaml.readTree(file.toFile()) ?: return out
        fun walk(prefix: String, node: com.fasterxml.jackson.databind.JsonNode) {
            when {
                node.isObject -> node.fields().forEach { (k, v) ->
                    walk(if (prefix.isEmpty()) k else "$prefix.$k", v)
                }
                node.isArray -> out[prefix] = node.joinToString(",") { it.asText() }
                else -> out[prefix] = node.asText()
            }
        }
        walk("", root)
        return out
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
