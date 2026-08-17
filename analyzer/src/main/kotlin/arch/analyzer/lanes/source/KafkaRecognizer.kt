package arch.analyzer.lanes.source

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.fact
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.StringLiteralExpr

/**
 * Kafka: @KafkaListener -> SUBSCRIBE, kafkaTemplate.send -> PUBLISH,
 * @Scheduled -> хинт воркера. Топик — только строковый литерал; выражение
 * в топике не распознаётся и уходит в лог полки (не выдумываем факты).
 */
class KafkaRecognizer : SourceRecognizer {

    override fun recognize(project: JavaProject): List<Fact> {
        val facts = mutableListOf<Fact>()
        for ((path, cu) in project.compilationUnits()) {
            for (m in cu.findAll(MethodDeclaration::class.java)) {
                annotation(m, "KafkaListener")?.let { ann ->
                    val topic = annotationValue(ann, "topics") ?: annotationValue(ann)
                    if (topic != null) {
                        val attrs = mutableListOf("channel" to topic)
                        annotationValue(ann, "groupId")?.let { attrs += "group" to it }
                        m.parameters.firstOrNull()?.let { attrs += "payload" to unwrapType(it.type) }
                        facts += fact(FactType.SUBSCRIBE, project.sourceRef(path, m), 0.9, *attrs.toTypedArray())
                    }
                }
                if (annotation(m, "Scheduled") != null) {
                    facts += fact(FactType.CONTAINER_HINT, project.sourceRef(path, m), 0.9, "scheduled" to "true")
                }
            }

            for (call in cu.findAll(MethodCallExpr::class.java)) {
                if (call.nameAsString != "send") continue
                val scope = call.scope.orElse(null) ?: continue
                if (!scope.toString().contains("kafka", ignoreCase = true) &&
                    runCatching { scope.calculateResolvedType().describe() }.getOrNull()
                        ?.contains("KafkaTemplate") != true
                ) continue

                val topic = (call.arguments.firstOrNull() as? StringLiteralExpr)?.value ?: continue
                val attrs = mutableListOf("channel" to topic)
                payloadType(call)?.let { attrs += "schema" to it }
                facts += fact(FactType.PUBLISH, project.sourceRef(path, call), 0.85, *attrs.toTypedArray())
            }
        }
        return facts
    }

    /** Тип последнего аргумента send(topic, [key,] payload) — схема сообщения. */
    private fun payloadType(call: MethodCallExpr): String? {
        val payload = call.arguments.lastOrNull() ?: return null
        val resolved = runCatching { payload.calculateResolvedType().describe() }.getOrNull()
        return resolved?.substringAfterLast('.')
    }
}
