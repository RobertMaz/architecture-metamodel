package arch.analyzer.core

import java.nio.file.Path

/**
 * Вход одного прогона анализа: контейнер + то, что реально есть в наличии.
 * Полка сама решает, применима ли она (applicable) — так работает
 * «дал только сорцы — ок, дал больше — точнее».
 */
data class RepoInput(
    val containerId: String,
    val repoDir: Path,
    val jar: Path? = null,
    val runtimeUrl: String? = null,
    val traces: Path? = null,
    val openapi: Path? = null,
)

interface Lane {
    val name: String
    fun applicable(input: RepoInput): Boolean
    fun extract(input: RepoInput): List<Fact>
}
