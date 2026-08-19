package arch.analyzer

import arch.analyzer.core.Analyze
import arch.analyzer.core.Registry
import java.nio.file.Paths
import java.time.LocalDate

/**
 * Dev-вход (не продуктовый интерфейс — им станет UI):
 *   mvn -q -f analyzer/pom.xml exec:java -Dexec.args="analyze <containerId>|--all [--date YYYY-MM-DD] [--arch-root <path>]"
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] != "analyze") {
        println("использование: analyze <containerId>|--all [--date YYYY-MM-DD] [--arch-root <path>]")
        return
    }
    val opts = args.drop(1)
    fun opt(name: String): String? =
        opts.indexOf(name).takeIf { it >= 0 && it + 1 < opts.size }?.let { opts[it + 1] }

    // Корень данных (п. 10): --arch-root > env ARCH_DATA_ROOT > текущий репо
    val archRoot = Paths.get(opt("--arch-root") ?: System.getenv("ARCH_DATA_ROOT") ?: ".")
        .toAbsolutePath().normalize()
    val date = opt("--date") ?: LocalDate.now().toString()
    val targets =
        if (opts.contains("--all")) Registry(archRoot).repos().keys.toList()
        else listOf(opts.first())

    for (id in targets) {
        val r = Analyze.run(archRoot, id, date)
        println(
            "$id: полки=${r.lanesRun.joinToString(",")} фактов=${r.factCount} " +
                "конфликтов=${r.report.conflicts.size} низкая-уверенность=${r.report.lowConfidence.size} " +
                "вызовов-без-разрешения=${r.report.unresolvedCalls}",
        )
    }
}
