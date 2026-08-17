package arch.analyzer.lanes

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import arch.analyzer.core.fact
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Полка jqassistant — опциональная, через адаптер-скрипт.
 *
 * jQAssistant с Neo4j не тянется в сборку: он ставится руками, а его Cypher-запросы
 * пользователь оборачивает в analyzer/jqassistant/extract.sh, который получает путь
 * к JAR первым аргументом и печатает факты построчно:
 *
 *   TYPE|attr=value|attr=value|source|confidence
 *   ENDPOINT|method=GET|path=/vets|app.jar!demo.VetResource#list|0.75
 *
 * Тот же контракт годится любому внешнему инструменту. Neo4j при этом остаётся
 * доступным для ручных раскопок — полка лишь снимает с него факты для конвейера.
 */
class JqassistantLane(
    private val adapter: Path = Paths.get("analyzer/jqassistant/extract.sh"),
) : Lane {

    override val name = "jqassistant"

    override fun applicable(input: RepoInput): Boolean =
        adapter.exists() && input.jar?.exists() == true

    override fun extract(input: RepoInput): List<Fact> {
        val jar = input.jar ?: return emptyList()
        val p = ProcessBuilder(adapter.toAbsolutePath().toString(), jar.toAbsolutePath().toString())
            .redirectErrorStream(false)
            .start()
        val lines = p.inputStream.bufferedReader().readLines()
        if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
            error("адаптер jqassistant завершился с ошибкой (exit=${runCatching { p.exitValue() }.getOrNull()})")
        }
        return parseFactLines(lines, name)
    }
}

/** Разбор строк формата TYPE|attr=value|...|source|confidence; мусор пропускается молча в лог. */
fun parseFactLines(lines: List<String>, lane: String): List<Fact> {
    val facts = mutableListOf<Fact>()
    for (line in lines) {
        val parts = line.trim().split('|')
        if (parts.size < 3) continue
        val type = runCatching { FactType.valueOf(parts[0]) }.getOrNull() ?: continue
        val confidence = parts.last().toDoubleOrNull() ?: continue
        val source = parts[parts.size - 2]
        val attrs = parts.drop(1).dropLast(2)
            .mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.take(i) to kv.substring(i + 1)
            }
        facts += fact(type, source, confidence, *attrs.toTypedArray())
    }
    return facts
}
