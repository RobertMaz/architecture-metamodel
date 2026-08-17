package arch.analyzer.lanes.source

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.fact
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr

private val FEIGN_MAPPINGS = mapOf(
    "GetMapping" to "GET",
    "PostMapping" to "POST",
    "PutMapping" to "PUT",
    "DeleteMapping" to "DELETE",
    "PatchMapping" to "PATCH",
    "RequestLine" to null, // OpenFeign-нативная аннотация: "POST /path"
)

/**
 * Интерфейсы @FeignClient: самый надёжный источник исходящих вызовов —
 * есть и имя целевого сервиса, и контрактные пути.
 */
class FeignRecognizer : SourceRecognizer {

    override fun recognize(project: JavaProject): List<Fact> {
        val facts = mutableListOf<Fact>()
        for ((path, cu) in project.compilationUnits()) {
            for (iface in cu.findAll(ClassOrInterfaceDeclaration::class.java)) {
                if (!iface.isInterface) continue
                val feign = annotation(iface, "FeignClient") ?: continue

                val name = annotationValue(feign, "name")
                    ?: annotationValue(feign, "value")
                val url = (feign as? NormalAnnotationExpr)?.pairs
                    ?.firstOrNull { it.name.identifier == "url" }
                    ?.value?.let { (it as? StringLiteralExpr)?.value }
                val prefix = annotation(iface, "RequestMapping")?.let { annotationValue(it) }

                for (m in iface.methods) {
                    val (http, mapping) = FEIGN_MAPPINGS.entries
                        .firstNotNullOfOrNull { (ann, verb) ->
                            annotation(m, ann)?.let { (verb ?: "GET") to it }
                        } ?: continue
                    val p = joinPath(prefix, annotationValue(mapping))
                    val attrs = mutableListOf("method" to http, "path" to p)
                    name?.let { attrs += "feignName" to it }
                    url?.let { attrs += "urlTemplate" to it }
                    facts += fact(FactType.OUTGOING_CALL, project.sourceRef(path, m), 0.9, *attrs.toTypedArray())
                }
            }
        }
        return facts
    }
}
