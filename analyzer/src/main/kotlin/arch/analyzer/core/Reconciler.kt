package arch.analyzer.core

import kotlin.math.roundToInt

/**
 * Чистая функция: (улики всех полок) -> api-source v2 + отчёт.
 * Правила из спеки: группировка по ключу идентичности; детали — от полки
 * с высшим приоритетом; подтверждение >=2 полками повышает confidence;
 * конфликт деталей — приоритетная полка + запись в отчёт.
 */
class Reconciler(
    private val lanePriority: List<String> = listOf("runtime", "traces", "openapi", "springwolf", "lst", "source", "bytecode", "clientlibs", "noir", "config", "llm"),
) {

    private val extractor = "arch-analyzer source+config v1"

    private fun rank(lane: String): Int {
        val i = lanePriority.indexOf(lane)
        return if (i >= 0) i else lanePriority.size
    }

    private data class Located(val lane: String, val fact: Fact)

    /** Слитая группа фактов одной идентичности. */
    private data class Merged(
        val attrs: Map<String, String>,
        val source: String,
        val confidence: Double,
    )

    fun reconcile(containerId: String, evidences: List<Evidence>, meta: SourceMeta): Pair<ApiSourceDoc, ReconcileReport> {
        val all = evidences
            .flatMap { e -> e.facts.map { Located(e.lane, it) } }
            .sortedWith(compareBy({ rank(it.lane) }, { it.fact.attrs.toString() }, { it.fact.source }))
            .let(::alignSpecPaths)
            .let(::alignContextPrefixes)

        val conflicts = mutableListOf<String>()
        val lowConfidence = mutableListOf<String>()

        fun byType(t: FactType) = all.filter { it.fact.type == t }

        fun mergeGroup(label: String, group: List<Located>): Merged {
            val winner = group.first() // список уже отсортирован по приоритету полки
            val attrs = winner.fact.attrs.toMutableMap()
            for (other in group.drop(1)) {
                for ((k, v) in other.fact.attrs) {
                    val cur = attrs[k]
                    when {
                        cur == null || cur.isEmpty() -> attrs[k] = v
                        v.isEmpty() || v == cur -> {}
                        // Имя path-параметра/плейсхолдера не различает эндпоинты — и не конфликтует.
                        (k == "path" || k == "urlTemplate") && normPath(cur) == normPath(v) -> {}
                        else -> conflicts += "$label: $k «$cur» (${winner.lane}) vs «$v» (${other.lane})"
                    }
                }
            }
            val lanes = group.map { it.lane }.distinct()
            val confidence =
                if (lanes.size < 2) group.first().fact.confidence
                else round2(1 - group.map { 1 - it.fact.confidence }.reduce(Double::times))
            val source = group.map { it.fact.source }.distinct().joinToString("; ")
            if (confidence < 0.8) lowConfidence += "$label (confidence $confidence)"
            return Merged(attrs, source, confidence)
        }

        fun grouped(t: FactType, key: (Fact) -> String): List<Pair<String, Merged>> =
            byType(t).groupBy { key(it.fact) }
                .map { (k, g) -> k to mergeGroup("${t.name} $k", g) }
                .sortedBy { it.first }

        // --- эндпоинты -> операции --------------------------------------
        val endpointGroups = grouped(FactType.ENDPOINT) {
            "${it.attrs["method"]} ${normPath(it.attrs["path"] ?: "")}"
        }
        val operations = endpointGroups.map { (_, m) ->
            Operation(
                method = m.attrs["method"] ?: "GET",
                path = m.attrs["path"] ?: "/",
                group = m.attrs["group"] ?: inferGroupFromPath(m.attrs["path"] ?: ""),
                summary = m.attrs["summary"],
                params = m.attrs["params"],
                request = m.attrs["request"],
                response = m.attrs["response"],
                deprecated = if (m.attrs["deprecated"] == "true") true else null,
                sunset = m.attrs["sunset"],
                source = m.source,
                confidence = m.confidence,
            )
        }.sortedWith(compareBy({ it.path }, { it.method }))

        // --- сторы: пустой jdbc-адрес склеивается с единственным адресным --
        val storeFacts = byType(FactType.STORE_ACCESS)
        val storeGroups = storeFacts.groupBy { "${it.fact.attrs["kind"]}|${it.fact.attrs["address"]}" }
        val jdbcWithAddress = storeGroups.keys.filter { it.startsWith("jdbc|") && it != "jdbc|" }
        val mergedStoreGroups: Map<String, List<Located>> =
            if ("jdbc|" in storeGroups && jdbcWithAddress.size == 1) {
                val target = jdbcWithAddress.single()
                storeGroups
                    .filterKeys { it != "jdbc|" && it != target }
                    .plus(target to (storeGroups.getValue(target) + storeGroups.getValue("jdbc|")))
            } else storeGroups

        val stores = mergedStoreGroups.entries
            .sortedBy { it.key }
            .map { (key, group) ->
                val sorted = group.sortedWith(compareBy({ rank(it.lane) }, { it.fact.source }))
                    // entities объединяются ниже — их расхождение не конфликт
                    .map { it.copy(fact = it.fact.copy(attrs = java.util.TreeMap(it.fact.attrs.filterKeys { k -> k != "entities" }))) }
                val m = mergeGroup("STORE_ACCESS $key", sorted)
                val entities = group.mapNotNull { it.fact.attrs["entities"] }
                    .filter { it.isNotEmpty() }
                    .flatMap { it.split(",").map(String::trim) }
                    .distinct().sorted()
                StoreUse(
                    kind = m.attrs["kind"] ?: "jdbc",
                    address = m.attrs["address"] ?: "",
                    technology = m.attrs["technology"],
                    access = m.attrs["access"] ?: "readwrite",
                    entities = entities.joinToString(", ").ifEmpty { null },
                    source = m.source,
                    confidence = m.confidence,
                )
            }

        // --- каналы -----------------------------------------------------
        val publishes = grouped(FactType.PUBLISH) { it.attrs["channel"] ?: "" }.map { (_, m) ->
            Publish(
                channel = m.attrs["channel"] ?: "",
                schema = m.attrs["schema"],
                fields = m.attrs["fields"],
                protocol = m.attrs["protocol"],
                channelRole = m.attrs["channelRole"],
                source = m.source,
                confidence = m.confidence,
            )
        }
        val subscribes = grouped(FactType.SUBSCRIBE) { it.attrs["channel"] ?: "" }.map { (_, m) ->
            Subscribe(
                channel = m.attrs["channel"] ?: "",
                group = m.attrs["group"],
                payload = m.attrs["payload"],
                protocol = m.attrs["protocol"],
                channelRole = m.attrs["channelRole"],
                source = m.source,
                confidence = m.confidence,
            )
        }

        // --- исходящие вызовы -------------------------------------------
        val TARGET_KEYS = listOf("container", "feignName", "host", "role", "urlTemplate", "prop", "route")
        val calls = grouped(FactType.OUTGOING_CALL) {
            // urlTemplate нормализуется: {_} у регулярок и {getUri()} у lst — один вызов
            val target = it.attrs["container"] ?: it.attrs["feignName"] ?: it.attrs["host"] ?: it.attrs["role"]
                ?: it.attrs["urlTemplate"]?.let(::normPath) ?: ""
            "${it.attrs["method"] ?: ""} $target ${normPath(it.attrs["path"] ?: "")}"
        }.map { (_, m) ->
            Call(
                method = m.attrs["method"],
                path = m.attrs["path"],
                target = TARGET_KEYS.mapNotNull { k -> m.attrs[k]?.let { k to it } }.toMap(),
                source = m.source,
                confidence = m.confidence,
            )
        }
        val unresolvedCalls = calls.count { "container" !in it.target }

        // --- контейнер ---------------------------------------------------
        val hints = byType(FactType.CONTAINER_HINT).flatMap { it.fact.attrs.entries.map { (k, v) -> k to v } }.toMap()
        val kind =
            if (operations.isEmpty() && (subscribes.isNotEmpty() || hints["scheduled"] == "true")) "worker"
            else "service"
        val shortName = containerId.substringAfterLast('.')
        val info = ContainerInfo(
            kind = kind,
            title = hints["appName"] ?: shortName,
            technology = "Java, Spring Boot",
            appName = hints["appName"],
            description = hints["description"],
        )

        val api = if (operations.isEmpty()) null else ApiBlock(
            title = "${info.title} API",
            basePath = commonBasePath(operations.map { it.path }),
        )

        val doc = ApiSourceDoc(
            container = containerId,
            source = SourceMetaOut(meta.repo, meta.commit, meta.extractedAt, extractor),
            containerInfo = info,
            api = api,
            operations = operations,
            publishes = publishes,
            subscribes = subscribes,
            calls = calls,
            stores = stores,
        )
        val report = ReconcileReport(
            conflicts = conflicts.distinct().sorted(),
            lowConfidence = lowConfidence.distinct().sorted(),
            unresolvedCalls = unresolvedCalls,
        )
        return doc to report
    }

    /**
     * Якорная синхронизация путей спеки: OpenAPI описывает пути относительно
     * servers.url (например /petclinic/api + /owners), а код и mappings —
     * servlet-относительно (/api/owners). Из суффиксов path-части servers.url
     * ('' , /api, /petclinic/api) выбирается тот, при котором максимум путей
     * спеки совпадает с эндпоинтами других полок, — и применяется ко всем.
     */
    private fun alignSpecPaths(all: List<Located>): List<Located> {
        val specEndpoints = all.filter { it.fact.type == FactType.ENDPOINT && it.fact.attrs.containsKey("specServerPath") }
        if (specEndpoints.isEmpty()) return all
        val anchorPaths = all
            .filter { it.fact.type == FactType.ENDPOINT && !it.fact.attrs.containsKey("specServerPath") }
            .mapNotNull { it.fact.attrs["path"]?.let { p -> "${it.fact.attrs["method"]} ${normPath(p)}" } }
            .toSet()

        val base = specEndpoints.first().fact.attrs["specServerPath"] ?: return all
        val segments = base.split('/').filter { it.isNotEmpty() }
        val candidates = (segments.indices.map { i -> "/" + segments.drop(i).joinToString("/") } + "")
        val best = candidates
            .map { prefix ->
                prefix to specEndpoints.count {
                    val p = it.fact.attrs["path"] ?: ""
                    "${it.fact.attrs["method"]} ${normPath(prefix + p)}" in anchorPaths
                }
            }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            .first()
        if (best.second == 0 || best.first.isEmpty()) {
            // якоря нет — пути спеки остаются как есть, только чистим служебный атрибут
            return all.map { strip(it) }
        }
        return all.map { located ->
            if (!located.fact.attrs.containsKey("specServerPath") || located.fact.type != FactType.ENDPOINT) strip(located)
            else {
                val attrs = java.util.TreeMap(located.fact.attrs.filterKeys { it != "specServerPath" })
                attrs["path"] = best.first + (located.fact.attrs["path"] ?: "")
                located.copy(fact = located.fact.copy(attrs = attrs))
            }
        }
    }

    /**
     * Зеркало alignSpecPaths для полок с абсолютными путями (noir): пути могут нести
     * servlet context-path (/petclinic/api/...), которого нет у якорных полок.
     * Кандидат на срез приходит атрибутом contextPrefix; срезаем, только если
     * совпадений с якорями становится строго больше, чем без среза.
     */
    private fun alignContextPrefixes(all: List<Located>): List<Located> {
        val prefixed = all.filter { it.fact.type == FactType.ENDPOINT && it.fact.attrs.containsKey("contextPrefix") }
        if (prefixed.isEmpty()) return all
        val anchors = all
            .filter { it.fact.type == FactType.ENDPOINT && !it.fact.attrs.containsKey("contextPrefix") }
            .mapNotNull { it.fact.attrs["path"]?.let { p -> "${it.fact.attrs["method"]} ${normPath(p)}" } }
            .toSet()
        val prefix = prefixed.first().fact.attrs["contextPrefix"] ?: return all
        fun matches(cut: String) = prefixed.count {
            val p = it.fact.attrs["path"] ?: return@count false
            p.startsWith(cut) && "${it.fact.attrs["method"]} ${normPath(p.removePrefix(cut))}" in anchors
        }
        val cut = if (matches(prefix) > matches("")) prefix else ""
        return all.map { located ->
            if (located.fact.type != FactType.ENDPOINT || !located.fact.attrs.containsKey("contextPrefix")) located
            else {
                val attrs = java.util.TreeMap(located.fact.attrs.filterKeys { it != "contextPrefix" })
                if (cut.isNotEmpty()) attrs["path"] = (located.fact.attrs["path"] ?: "").removePrefix(cut)
                located.copy(fact = located.fact.copy(attrs = attrs))
            }
        }
    }

    private fun strip(located: Located): Located =
        if (!located.fact.attrs.containsKey("specServerPath")) located
        else located.copy(
            fact = located.fact.copy(attrs = java.util.TreeMap(located.fact.attrs.filterKeys { it != "specServerPath" })),
        )

    /**
     * Fallback-группа операции из пути — когда ни одна полка группу не дала
     * (OpenAPI tags и имена Java-контроллеров её дают, Kotlin/bytecode/noir — нет):
     * первый значимый сегмент после версии API и служебных префиксов.
     * /api/v1/pos/link -> pos; /private/api/v2/terminal/{id} -> terminal.
     * Сегменты-параметры {…} группой не бывают. Ничего не вывели — базовый api.
     */
    private val skipSegments = setOf("api", "private", "secured", "public", "internal")

    private fun inferGroupFromPath(path: String): String? =
        path.split('/')
            .filter { it.isNotEmpty() }
            .firstOrNull {
                it.lowercase() !in skipSegments && !it.matches(Regex("v\\d+")) && !it.startsWith("{")
            }
            ?.lowercase()

    private fun round2(x: Double): Double = (x * 100).roundToInt() / 100.0

    /** Общий префикс путей по сегментам; '/' если общего нет. */
    private fun commonBasePath(paths: List<String>): String {
        if (paths.isEmpty()) return "/"
        val split = paths.map { it.trim('/').split('/') }
        val prefix = mutableListOf<String>()
        for (i in 0 until split.minOf { it.size }) {
            val seg = split.first()[i]
            if (seg.startsWith("{")) break
            if (split.all { it[i] == seg }) prefix += seg else break
        }
        return "/" + prefix.joinToString("/")
    }
}
