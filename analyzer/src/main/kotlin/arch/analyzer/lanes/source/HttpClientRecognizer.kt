package arch.analyzer.lanes.source

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.fact
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import java.net.URI

/** Имена методов RestTemplate -> HTTP-метод. */
private val REST_TEMPLATE_METHODS = mapOf(
    "getForObject" to "GET",
    "getForEntity" to "GET",
    "postForObject" to "POST",
    "postForEntity" to "POST",
    "postForLocation" to "POST",
    "put" to "PUT",
    "delete" to "DELETE",
    "patchForObject" to "PATCH",
    "exchange" to null, // метод в аргументе HttpMethod.X
)

/** Стартеры цепочки WebClient/RestClient -> HTTP-метод. */
private val FLUENT_STARTERS = mapOf(
    "get" to "GET", "post" to "POST", "put" to "PUT", "delete" to "DELETE", "patch" to "PATCH",
)

/**
 * Call-sites RestTemplate/WebClient/RestClient. Уверенность градуирована:
 * литеральный URL — 0.8, конкатенация — 0.7, exchange без HttpMethod — 0.5.
 * Резолв типа скоупа — best-effort: symbol solver, иначе эвристика по имени.
 */
class HttpClientRecognizer : SourceRecognizer {

    override fun recognize(project: JavaProject): List<Fact> {
        val facts = mutableListOf<Fact>()
        for ((path, cu) in project.compilationUnits()) {
            for (call in cu.findAll(MethodCallExpr::class.java)) {
                recognizeRestTemplate(project, path, call)?.let { facts += it }
                recognizeFluent(project, path, call)?.let { facts += it }
            }
        }
        return facts
    }

    // --- RestTemplate ---------------------------------------------------

    private fun recognizeRestTemplate(project: JavaProject, file: java.nio.file.Path, call: MethodCallExpr): Fact? {
        if (call.nameAsString !in REST_TEMPLATE_METHODS) return null
        val scope = call.scope.orElse(null) ?: return null
        if (!scopeLooksLike(scope, "RestTemplate", Regex("rest|template|client", RegexOption.IGNORE_CASE))) return null

        val urlArg = call.arguments.firstOrNull() ?: return null
        val (template, fromConcat) = urlTemplate(urlArg) ?: return null

        var http = REST_TEMPLATE_METHODS[call.nameAsString]
        var confidence = if (fromConcat) 0.7 else 0.8
        if (http == null) {
            http = call.arguments.getOrNull(1)?.toString()?.substringAfterLast('.')
                ?.takeIf { it.matches(Regex("[A-Z]+")) }
            if (http == null) {
                http = "GET"
                confidence = 0.5
            }
        }
        return callFact(project, file, call, http, template, confidence)
    }

    // --- WebClient / RestClient: client.get().uri("...") ----------------

    private fun recognizeFluent(project: JavaProject, file: java.nio.file.Path, call: MethodCallExpr): Fact? {
        if (call.nameAsString != "uri") return null
        val starter = call.scope.orElse(null) as? MethodCallExpr ?: return null
        val http = FLUENT_STARTERS[starter.nameAsString] ?: return null
        // Отсечь не-HTTP цепочки: в скоупе стартера должно быть что-то клиентское.
        val root = starter.scope.orElse(null) ?: return null
        if (!root.toString().contains(Regex("webClient|client|wcb|\\.build\\(\\)", RegexOption.IGNORE_CASE))) return null

        val urlArg = call.arguments.firstOrNull() ?: return null
        val (template, fromConcat) = urlTemplate(urlArg) ?: return null
        return callFact(project, file, call, http, template, if (fromConcat) 0.7 else 0.8)
    }

    // --- общее ----------------------------------------------------------

    private fun scopeLooksLike(scope: Expression, typeName: String, nameHint: Regex): Boolean {
        val resolved = runCatching { scope.calculateResolvedType().describe() }.getOrNull()
        if (resolved != null) return resolved.substringAfterLast('.').contains(typeName)
        return nameHint.containsMatchIn(scope.toString())
    }

    /** Литерал или конкатенация литералов: выражения -> {_}. null, если строки нет вовсе. */
    private fun urlTemplate(e: Expression): Pair<String, Boolean>? = when (e) {
        is StringLiteralExpr -> e.value to false
        is BinaryExpr -> {
            if (e.operator != BinaryExpr.Operator.PLUS) null
            else {
                val parts = flattenConcat(e)
                if (parts.none { it.second }) null
                else parts.joinToString("") { (txt, isLit) -> if (isLit) txt else "{_}" } to true
            }
        }
        else -> null
    }

    private fun flattenConcat(e: Expression): List<Pair<String, Boolean>> = when {
        e is BinaryExpr && e.operator == BinaryExpr.Operator.PLUS ->
            flattenConcat(e.left) + flattenConcat(e.right)
        e is StringLiteralExpr -> listOf(e.value to true)
        else -> listOf(e.toString() to false)
    }

    private fun callFact(
        project: JavaProject, file: java.nio.file.Path, node: MethodCallExpr,
        http: String, template: String, confidence: Double,
    ): Fact {
        val base = template.substringBefore('?')
        val attrs = mutableListOf("method" to http, "urlTemplate" to base)
        if (base.startsWith("http://") || base.startsWith("https://")) {
            val parseable = base.replace(Regex("\\{[^}]*}"), "_")
            runCatching { URI(parseable) }.getOrNull()?.let { uri ->
                uri.host?.let { attrs += "host" to it }
                fixPlaceholders(base, "")
                    .takeIf { it.isNotEmpty() }
                    ?.let { attrs += "path" to it }
            }
        } else if (base.startsWith("/")) {
            attrs += "path" to base
        }
        return fact(FactType.OUTGOING_CALL, project.sourceRef(file, node), confidence, *attrs.toTypedArray())
    }

    /**
     * Путь берём из исходного шаблона (не из URI с подменой), чтобы {id} и {_}
     * не искажались: срез после хоста и до query.
     */
    private fun fixPlaceholders(template: String, fallback: String): String {
        val afterScheme = template.substringAfter("://", "")
        if (afterScheme.isEmpty()) return fallback
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return fallback
        return afterScheme.substring(slash).substringBefore('?')
    }
}
