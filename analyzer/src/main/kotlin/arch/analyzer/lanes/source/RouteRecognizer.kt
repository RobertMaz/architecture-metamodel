package arch.analyzer.lanes.source

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.fact
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import kotlin.jvm.optionals.getOrNull

/** Аннотации маппингов Spring MVC/WebFlux (annotation-based). */
private val MAPPINGS = mapOf(
    "GetMapping" to "GET",
    "PostMapping" to "POST",
    "PutMapping" to "PUT",
    "DeleteMapping" to "DELETE",
    "PatchMapping" to "PATCH",
)

/** Обёртки, которые разворачиваются до полезного типа ответа. */
private val WRAPPERS = setOf("ResponseEntity", "Mono", "Flux", "Optional", "List", "Set", "Collection", "Iterable")

internal fun annotation(n: NodeWithAnnotations<*>, name: String): AnnotationExpr? =
    n.annotations.firstOrNull { it.name.identifier == name }

/** value/path из аннотации: literal, массив (берём первый) или именованный атрибут. */
internal fun annotationValue(a: AnnotationExpr, attr: String = "value"): String? {
    fun unwrap(e: com.github.javaparser.ast.expr.Expression): String? = when {
        e is StringLiteralExpr -> e.value
        e is ArrayInitializerExpr -> e.values.firstOrNull()?.let { unwrap(it) }
        else -> null
    }
    return when (a) {
        is SingleMemberAnnotationExpr -> if (attr == "value" || attr == "path") unwrap(a.memberValue) else null
        is NormalAnnotationExpr -> a.pairs.firstOrNull { it.name.identifier == attr || (attr == "value" && it.name.identifier == "path") }
            ?.let { unwrap(it.value) }
        else -> null
    }
}

internal fun joinPath(prefix: String?, value: String?): String {
    val p = (prefix ?: "").trim().removeSuffix("/")
    val v = (value ?: "").trim()
    val joined = when {
        v.isEmpty() -> p
        v.startsWith("/") -> p + v
        else -> "$p/$v"
    }
    return if (joined.startsWith("/")) joined else "/$joined"
}

/** Простое имя типа с разворотом обёрток: ResponseEntity<Mono<X>> -> X. */
internal fun unwrapType(t: Type): String {
    var cur: Type = t
    while (cur is ClassOrInterfaceType && cur.nameAsString in WRAPPERS) {
        val args = cur.typeArguments.getOrNull()
        cur = args?.firstOrNull() ?: return cur.nameAsString
    }
    return if (cur is ClassOrInterfaceType) cur.nameAsString else cur.toString()
}

/**
 * Роуты Spring MVC/WebFlux (annotation-based): @RestController + @*Mapping.
 * WebFlux functional routing и Ktor — отдельные распознаватели (позже).
 */
class RouteRecognizer : SourceRecognizer {

    override fun recognize(project: JavaProject): List<Fact> {
        val facts = mutableListOf<Fact>()
        for ((path, cu) in project.compilationUnits()) {
            for (cls in cu.findAll(ClassOrInterfaceDeclaration::class.java)) {
                if (annotation(cls, "RestController") == null) continue
                val prefix = annotation(cls, "RequestMapping")?.let { annotationValue(it) }
                val group = groupOf(cls.nameAsString)
                for (m in cls.methods) {
                    facts += recognizeMethod(project, path, prefix, group, m) ?: continue
                }
            }
        }
        return facts
    }

    /** Домен операции из имени контроллера: OwnerRestControllerV1 -> owner. */
    private fun groupOf(className: String): String? {
        val stripped = className.replace(Regex("(Rest)?(Controller|Resource|Api)(V\\d+)?$"), "")
        return if (stripped.isEmpty() || stripped == className) null else stripped.lowercase()
    }

    private fun recognizeMethod(project: JavaProject, file: java.nio.file.Path, prefix: String?, group: String?, m: MethodDeclaration): Fact? {
        val (httpMethod, mappingAnn) = httpMethodOf(m) ?: return null
        val path = joinPath(prefix, mappingAnn?.let { annotationValue(it) })

        val attrs = mutableListOf(
            "method" to httpMethod,
            "path" to path,
        )
        group?.let { attrs += "group" to it }
        params(m)?.let { attrs += "params" to it }
        requestBody(m)?.let { attrs += "request" to it }
        unwrapType(m.type).takeIf { it != "void" && it != "Void" }?.let { attrs += "response" to it }
        if (annotation(m, "Deprecated") != null) attrs += "deprecated" to "true"

        return fact(FactType.ENDPOINT, project.sourceRef(file, m), 0.95, *attrs.toTypedArray())
    }

    private fun httpMethodOf(m: MethodDeclaration): Pair<String, AnnotationExpr?>? {
        for ((ann, http) in MAPPINGS) {
            annotation(m, ann)?.let { return http to it }
        }
        annotation(m, "RequestMapping")?.let { rm ->
            // @RequestMapping(method = RequestMethod.GET, ...)
            val method = (rm as? NormalAnnotationExpr)?.pairs
                ?.firstOrNull { it.name.identifier == "method" }
                ?.value?.toString()?.substringAfterLast('.')
            return (method ?: "GET") to rm
        }
        return null
    }

    /** name:in:type[?] через запятую, в порядке объявления. */
    private fun params(m: MethodDeclaration): String? {
        val parts = m.parameters.mapNotNull { p -> paramSpec(p) }
        return parts.joinToString(", ").ifEmpty { null }
    }

    private fun paramSpec(p: Parameter): String? {
        val kinds = listOf("PathVariable" to "path", "RequestParam" to "query", "RequestHeader" to "header")
        for ((ann, where) in kinds) {
            val a = annotation(p, ann) ?: continue
            val name = a.let { annotationValue(it, "name") ?: annotationValue(it, "value") } ?: p.nameAsString
            val optional = (a as? NormalAnnotationExpr)?.pairs
                ?.any { it.name.identifier == "required" && it.value.toString() == "false" } == true
            val type = unwrapType(p.type)
            return "$name:$where:$type${if (optional) "?" else ""}"
        }
        return null
    }

    private fun requestBody(m: MethodDeclaration): String? =
        m.parameters.firstOrNull { annotation(it, "RequestBody") != null }
            ?.let { unwrapType(it.type) }
}
