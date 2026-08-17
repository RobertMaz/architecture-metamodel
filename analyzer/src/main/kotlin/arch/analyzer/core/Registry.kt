package arch.analyzer.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * registry/repos.yml — инвентарь: container-id -> репозиторий и источники.
 * Единственное место, где человек привязывает репо к id (и системе через префикс id).
 */
data class RepoEntry(
    val repo: String,
    val path: String,
    val jar: String? = null,
    val runtimeUrl: String? = null,
    val traces: String? = null,
)

class Registry(private val archRoot: Path) {

    private val yaml = ObjectMapper(YAMLFactory())

    fun repos(): Map<String, RepoEntry> {
        val file = archRoot.resolve("registry/repos.yml")
        if (!file.exists()) return emptyMap()
        val root = yaml.readTree(file.toFile())?.get("repos") ?: return emptyMap()
        val out = sortedMapOf<String, RepoEntry>()
        root.fields().forEach { (id, node) ->
            val repo = node.get("repo")?.asText() ?: ""
            val path = node.get("path")?.asText() ?: ""
            val jar = node.get("jar")?.asText()?.takeIf { it.isNotEmpty() }
            val runtimeUrl = node.get("runtimeUrl")?.asText()?.takeIf { it.isNotEmpty() }
            val traces = node.get("traces")?.asText()?.takeIf { it.isNotEmpty() }
            if (path.isNotEmpty()) out[id] = RepoEntry(repo, path, jar, runtimeUrl, traces)
        }
        return out
    }

    fun entry(containerId: String): RepoEntry =
        repos()[containerId]
            ?: error("контейнер «$containerId» не найден в registry/repos.yml")
}
