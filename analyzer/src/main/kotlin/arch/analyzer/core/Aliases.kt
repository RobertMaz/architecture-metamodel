package arch.analyzer.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * registry/aliases.yml — таблица разрешения адресов: host/feign-name/appName -> container-id.
 * Пополняется автоматически при анализе (сервис декларирует свои имена) и руками.
 * Ручные записи не перетираются: конфликт уходит в отчёт прогона.
 */
class Aliases(private val archRoot: Path) {

    private val yaml = ObjectMapper(YAMLFactory())

    private val header =
        "# Таблица разрешения адресов: host / feign-name / spring.application.name -> container-id.\n" +
            "# Пополняется анализатором при прогоне; ручные записи не перетираются.\n"

    private fun file(): Path = archRoot.resolve("registry/aliases.yml")

    fun all(): Map<String, String> {
        if (!file().exists()) return emptyMap()
        val root = yaml.readTree(file().toFile())?.get("aliases") ?: return emptyMap()
        val out = sortedMapOf<String, String>()
        root.fields().forEach { (k, v) -> out[k] = v.asText() }
        return out
    }

    /** Возвращает список конфликтов «ключ уже занят другим контейнером». */
    fun upsert(entries: Map<String, String>): List<String> {
        val current = all().toSortedMap()
        val conflicts = mutableListOf<String>()
        for ((k, v) in entries.toSortedMap()) {
            val existing = current[k]
            when {
                existing == null -> current[k] = v
                existing != v -> conflicts += "алиас «$k» уже указывает на $existing (не перетираю на $v)"
            }
        }
        write(current)
        return conflicts
    }

    /** Переприцелка при переносе/удалении контейнера: newId == null — записи удаляются. */
    fun retarget(oldId: String, newId: String?) {
        if (!file().exists()) return
        val current = all().toSortedMap()
        val keys = current.filterValues { it == oldId }.keys
        if (keys.isEmpty()) return
        for (k in keys) if (newId == null) current.remove(k) else current[k] = newId
        write(current)
    }

    private fun write(entries: Map<String, String>) {
        val out = StringBuilder(header).append("aliases:\n")
        for ((k, v) in entries) out.append("  $k: $v\n")
        file().parent.createDirectories()
        val text = out.toString()
        if (!file().exists() || file().readText() != text) file().writeText(text)
    }
}
