package arch.analyzer.core

/**
 * api-source v2 — выход реконсилятора, вход генератора tools/gen-model.mjs.
 * Наличие containerInfo отличает v2 от легаси-доков v1 (их обрабатывает gen-api.mjs).
 * Отличие операций от v1: params/request/response — компактные строки, не объекты
 * (граф про рёбра, полные схемы живут в коде).
 */

data class SourceMeta(
    val repo: String,
    val commit: String,
    val extractedAt: String,
)

data class SourceMetaOut(
    val repo: String,
    val commit: String,
    val extractedAt: String,
    val extractor: String,
)

data class ContainerInfo(
    val kind: String,
    val title: String,
    val technology: String,
    val appName: String? = null,
    val description: String? = null,
)

data class ApiBlock(
    val id: String = "api",
    val title: String,
    val technology: String = "HTTP/JSON",
    val basePath: String,
    val public: Boolean = true,
)

data class Operation(
    val method: String,
    val path: String,
    val summary: String? = null,
    val params: String? = null,
    val request: String? = null,
    val response: String? = null,
    val deprecated: Boolean? = null,
    val sunset: String? = null,
    val source: String,
    val confidence: Double,
)

data class Publish(
    val channel: String,
    val schema: String? = null,
    val fields: String? = null,
    val source: String,
    val confidence: Double,
)

data class Subscribe(
    val channel: String,
    val group: String? = null,
    val payload: String? = null,
    val source: String,
    val confidence: Double,
)

data class Call(
    val method: String? = null,
    val path: String? = null,
    val target: Map<String, String>,
    val source: String,
    val confidence: Double,
)

data class StoreUse(
    val kind: String,
    val address: String,
    val technology: String? = null,
    val access: String,
    val entities: String? = null,
    val source: String,
    val confidence: Double,
)

data class ApiSourceDoc(
    val container: String,
    val source: SourceMetaOut,
    val containerInfo: ContainerInfo,
    val api: ApiBlock? = null,
    val operations: List<Operation> = emptyList(),
    val publishes: List<Publish> = emptyList(),
    val subscribes: List<Subscribe> = emptyList(),
    val calls: List<Call> = emptyList(),
    val stores: List<StoreUse> = emptyList(),
)

data class ReconcileReport(
    val conflicts: List<String> = emptyList(),
    val lowConfidence: List<String> = emptyList(),
    val unresolvedCalls: Int = 0,
)
