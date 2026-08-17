package arch.analyzer.lanes

import arch.analyzer.core.Fact
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import arch.analyzer.lanes.source.JavaProject
import arch.analyzer.lanes.source.RouteRecognizer
import arch.analyzer.lanes.source.SourceRecognizer

/**
 * Полка source: статический анализ Java-сорцов. Состав распознавателей растёт;
 * каждый — независимый SourceRecognizer, падение одного не роняет полку
 * (ошибка уходит в failures и потом в отчёт прогона).
 */
class SourceLane(
    private val recognizers: List<SourceRecognizer> = defaultRecognizers(),
) : Lane {

    override val name = "source"

    val failures = mutableListOf<String>()

    override fun applicable(input: RepoInput): Boolean = JavaProject(input.repoDir).exists()

    override fun extract(input: RepoInput): List<Fact> {
        val project = JavaProject(input.repoDir)
        val facts = mutableListOf<Fact>()
        for (r in recognizers) {
            runCatching { facts += r.recognize(project) }
                .onFailure { failures += "${r.javaClass.simpleName}: ${it.message}" }
        }
        return facts
    }

    companion object {
        fun defaultRecognizers(): List<SourceRecognizer> = listOf(
            RouteRecognizer(),
        )
    }
}
