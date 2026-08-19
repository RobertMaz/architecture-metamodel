package arch.analyzer.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.TreeMap
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/** Общий парсинг конфигов: yml (multi-doc) и properties -> плоская map "a.b.c" -> значение. */
object ConfigParsing {
    private val yaml = ObjectMapper(YAMLFactory())

    /** Все документы multi-doc yml (---); properties — один «документ». */
    fun readDocs(file: Path): List<JsonNode> {
        if (file.name.endsWith(".properties")) {
            val p = Properties()
            file.readText().reader().use { p.load(it) }
            val node = yaml.createObjectNode()
            for (k in p.stringPropertyNames().sorted()) node.put(k, p.getProperty(k))
            return listOf(node)
        }
        val parser = yaml.factory.createParser(file.toFile())
        return yaml.readValues(parser, JsonNode::class.java).readAll().filterNotNull()
    }

    /** Документы -> плоская map; первый документ побеждает. */
    fun flatten(docs: List<JsonNode>): Map<String, String> {
        val out = sortedMapOf<String, String>()
        fun walk(prefix: String, node: JsonNode) {
            when {
                node.isObject -> node.fields().forEach { (k, v) ->
                    walk(if (prefix.isEmpty()) k else "$prefix.$k", v)
                }
                node.isArray -> out.putIfAbsent(prefix, node.joinToString(",") { it.asText() })
                else -> out.putIfAbsent(prefix, node.asText())
            }
        }
        for (doc in docs) walk("", doc)
        return out
    }
}

/**
 * П. 7 плана: единый резолв `${...}` по всем конфигам, один на всех потребителей
 * (топики Kafka, @Value в lst-полке, *.url-свойства config-полки).
 *
 * Цепочка источников значения (первый нашедший ключ выигрывает):
 * application.* -> профили application-*.* -> bootstrap.* -> helm values*.yaml ->
 * внешний конфиг из repos.yml (`config:` — файл или каталог: ansible vars и т.п.).
 * Не нашли — значение остаётся `${...}`: «во внешнем конфиге», в триаж.
 */
class PlaceholderResolver(values: Map<String, String>) {

    // exact-ключи + relaxed binding (topic.orders-out == TOPIC_ORDERSOUT)
    private val exact = values
    private val relaxed = values.entries.associate { (k, v) -> canonical(k) to v }

    private fun canonical(key: String) = key.lowercase().replace("-", "").replace('_', '.')

    fun lookup(key: String): String? = exact[key] ?: relaxed[canonical(key)]

    /** Подстановка всех `${key}`/`${key:default}` в строке; нерезолвнутое остаётся как есть. */
    fun resolve(s: String, depth: Int = 0): String {
        if (depth > 5 || !s.contains("\${")) return s
        return PLACEHOLDER.replace(s) { m ->
            val key = m.groupValues[1]
            val def = m.groupValues[3].takeIf { m.groupValues[2].isNotEmpty() }
            val v = lookup(key) ?: def
            if (v == null) m.value else resolve(v, depth + 1)
        }
    }

    fun hasUnresolved(s: String): Boolean = PLACEHOLDER.containsMatchIn(s)

    companion object {
        // ключ без { } :, опциональный :default (без вложенных {})
        private val PLACEHOLDER = Regex("\\$\\{([^{}:]+)(:([^{}]*))?}")

        private val SKIP_DIRS = setOf("target", "build", "node_modules", ".git", "test")

        fun load(repoDir: Path, extraConfig: Path?): PlaceholderResolver {
            if (!repoDir.exists()) return PlaceholderResolver(emptyMap())
            val all = Files.walk(repoDir).use { s ->
                s.filter { it.isRegularFile() }
                    .filter { p -> repoDir.relativize(p).none { seg -> seg.toString() in SKIP_DIRS } }
                    .sorted()
                    .toList()
            }
            fun tier(pred: (String) -> Boolean) = all.filter { pred(it.name) }

            val ordered =
                tier { it.matches(Regex("application\\.(yml|yaml|properties)")) } +
                    tier { it.matches(Regex("application-[\\w]+\\.(yml|yaml|properties)")) } +
                    tier { it.matches(Regex("bootstrap(-[\\w]+)?\\.(yml|yaml|properties)")) } +
                    tier { it.matches(Regex("values[\\w.-]*\\.(yml|yaml)")) } +
                    extraFiles(extraConfig)

            val values = TreeMap<String, String>()
            for (file in ordered) {
                val flat = runCatching { ConfigParsing.flatten(ConfigParsing.readDocs(file)) }.getOrNull() ?: continue
                for ((k, v) in flat) values.putIfAbsent(k, v)
            }
            return PlaceholderResolver(values)
        }

        private fun extraFiles(extra: Path?): List<Path> {
            if (extra == null || !extra.exists()) return emptyList()
            if (extra.isRegularFile()) return listOf(extra)
            return Files.walk(extra).use { s ->
                s.filter { it.isRegularFile() && it.name.matches(Regex(".*\\.(yml|yaml|properties)")) }
                    .sorted()
                    .toList()
            }
        }
    }
}

private val ENV_SUFFIX = Regex("[-._](dev|test|qa|stage|staging|uat|preprod|prod|production)$", RegexOption.IGNORE_CASE)
private val DLQ_SUFFIX = Regex("[-._](dlt|dlq|deadletter|dead-letter)$", RegexOption.IGNORE_CASE)
private val RETRY_SUFFIX = Regex("[-._]retry(-?\\d+)?$", RegexOption.IGNORE_CASE)

private fun hostOfUrl(url: String): String? =
    runCatching { URI(url).host }.getOrNull()?.takeIf { !it.contains("{") && !it.contains("$") }

/**
 * П. 5 плана: пост-обработка фактов единым резолвером — топики/URL/группы из
 * `${...}`, нормализация суффиксов окружений (orders-prod -> orders + envSuffix),
 * распознавание DLQ/retry-топиков (channelRole), дорезолв host после подстановки.
 * Evidence-файлы на диске остаются сырыми — резолв только на входе реконсиляции.
 */
fun resolveFacts(evidence: Evidence, resolver: PlaceholderResolver): Evidence =
    evidence.copy(facts = evidence.facts.map { resolveFact(it, resolver) })

private fun resolveFact(fact: Fact, r: PlaceholderResolver): Fact {
    val attrs = TreeMap(fact.attrs)
    var confidence = fact.confidence
    for (key in listOf("channel", "urlTemplate", "group")) {
        attrs[key]?.let { attrs[key] = r.resolve(it) }
    }

    // канал: суффикс окружения срезается (иначе один топик распался бы на узлы по средам),
    // DLQ/retry — отдельная роль, но отдельный узел: суффикс роли остаётся в имени
    attrs["channel"]?.takeIf { !r.hasUnresolved(it) }?.let { channel ->
        var base = channel
        var roleSuffix = ""
        (DLQ_SUFFIX.find(base) ?: RETRY_SUFFIX.find(base))?.let { m ->
            roleSuffix = m.value
            base = base.removeSuffix(m.value)
            attrs["channelRole"] = if (DLQ_SUFFIX.containsMatchIn(m.value)) "dlq" else "retry"
        }
        ENV_SUFFIX.find(base)?.let { m ->
            if (base.length > m.value.length) {
                attrs["envSuffix"] = m.value.drop(1)
                base = base.removeSuffix(m.value)
            }
        }
        attrs["channel"] = base + roleSuffix
    }

    // значение так и не нашлось — «во внешнем конфиге»: в отчёт и триаж
    val unresolved = listOf("channel", "urlTemplate").any { attrs[it]?.let(r::hasUnresolved) == true }
    if (unresolved) {
        attrs["externalConfig"] = "true"
        confidence = minOf(confidence, 0.6)
    }

    // после подстановки url мог стать абсолютным — дорезолв host
    attrs["urlTemplate"]?.let { url ->
        if (attrs["host"] == null && (url.startsWith("http://") || url.startsWith("https://"))) {
            hostOfUrl(url)?.let { attrs["host"] = it }
        }
    }
    return fact.copy(attrs = attrs, confidence = confidence)
}
