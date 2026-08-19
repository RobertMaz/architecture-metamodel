package arch.analyzer.lanes

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import arch.analyzer.core.fact
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.name

private const val PKG_WEB = "Lorg/springframework/web/bind/annotation/"

private val MAPPINGS = mapOf(
    "${PKG_WEB}GetMapping;" to "GET",
    "${PKG_WEB}PostMapping;" to "POST",
    "${PKG_WEB}PutMapping;" to "PUT",
    "${PKG_WEB}DeleteMapping;" to "DELETE",
    "${PKG_WEB}PatchMapping;" to "PATCH",
)

private val REPOSITORY_BASES = setOf(
    "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
    "ListCrudRepository", "ListPagingAndSortingRepository",
    "ReactiveCrudRepository", "R2dbcRepository", "MongoRepository",
)

/**
 * Полка bytecode: ASM по JAR. Второй взгляд на те же факты — аннотации с
 * RUNTIME retention видны в class-файлах. Закрывает «сорцов нет, бинарь есть»
 * и места, где Kotlin-сорцы врут (inline, корутины): компилятор уже всё раскрыл.
 * Детали (params/response) не извлекаются — их даёт source, байткод подтверждает.
 */
class BytecodeLane : Lane {

    override val name = "bytecode"

    override fun applicable(input: RepoInput): Boolean = input.jar?.exists() == true

    override fun extract(input: RepoInput): List<Fact> {
        val jarPath = input.jar ?: return emptyList()
        val facts = mutableListOf<Fact>()
        JarFile(jarPath.toFile()).use { jar ->
            val entries = jar.entries().asSequence()
                .filter { it.name.endsWith(".class") && !it.name.endsWith("module-info.class") }
                .sortedBy { it.name }
                .toList()
            // Два прохода (п. 9): сначала все классы + константы полей (javac инлайнит
            // static final в LDC, но Kotlin-объекты и чужие поля идут через GETSTATIC),
            // потом распознавание — аннотации и call-sites из тел методов.
            val nodes = mutableListOf<ClassNode>()
            for (entry in entries) {
                val node = ClassNode()
                jar.getInputStream(entry).use {
                    ClassReader(it.readBytes()).accept(node, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
                }
                nodes += node
            }
            // Константы полей + @Value-плейсхолдеры: GETFIELD такого поля отдаёт `${...}`,
            // которое добьёт центральный PlaceholderResolver из конфигов (пп. 5+7).
            val constants = buildMap {
                for (n in nodes) for (f in n.fields ?: emptyList()) {
                    val const = f.value as? String
                        ?: value(ann(f.visibleAnnotations, "Lorg/springframework/beans/factory/annotation/Value;"), "value")
                    const?.let { put("${n.name}#${f.name}", it) }
                }
            }
            for (node in nodes) {
                facts += recognizeClass(jarPath.name, node)
                facts += callSites(jarPath.name, node, constants)
            }
        }
        return facts.distinct()
    }

    private val restTemplateMethods = setOf(
        "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation",
        "put", "delete", "exchange", "execute", "patchForObject", "headForHeaders", "optionsForAllow",
    )
    private val httpVerbs = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

    /**
     * Call-sites из тел методов (п. 9 плана): invoke* на шаблонах Kafka/Rabbit/StreamBridge
     * и HTTP-клиентах + простой стековый резолв аргументов — LDC-строки и константы
     * полей назад от вызова до границы предыдущего invoke. Confidence 0.7: эвристика
     * по окну инструкций, а не честная симуляция стека.
     */
    private fun callSites(jarName: String, cls: ClassNode, constants: Map<String, String>): List<Fact> {
        val facts = mutableListOf<Fact>()
        val fqcn = cls.name.replace('/', '.')
        for (m in cls.methods.sortedBy { it.name }) {
            val insns = m.instructions ?: continue
            val src = "$jarName!$fqcn#${m.name}"
            for (insn in insns) {
                if (insn !is org.objectweb.asm.tree.MethodInsnNode) continue
                when {
                    insn.owner == "org/springframework/kafka/core/KafkaTemplate" && insn.name == "send" ->
                        topicArg(insn, constants)?.let {
                            facts += fact(FactType.PUBLISH, src, 0.7, "channel" to it, "protocol" to "kafka")
                        }
                    insn.owner == "org/springframework/cloud/stream/function/StreamBridge" && insn.name == "send" ->
                        topicArg(insn, constants)?.let {
                            facts += fact(FactType.PUBLISH, src, 0.7, "channel" to it, "protocol" to "kafka")
                        }
                    insn.owner == "org/springframework/amqp/rabbit/core/RabbitTemplate" &&
                        (insn.name == "send" || insn.name == "convertAndSend") ->
                        topicArg(insn, constants)?.let {
                            facts += fact(FactType.PUBLISH, src, 0.7, "channel" to it, "protocol" to "amqp")
                        }
                    insn.owner == "org/springframework/web/client/RestTemplate" && insn.name in restTemplateMethods ->
                        urlArg(insn, constants)?.let { url ->
                            facts += callFact(src, verbOfRest(insn.name), url)
                        }
                    insn.name == "uri" &&
                        (insn.owner.startsWith("org/springframework/web/reactive/function/client/WebClient") ||
                            insn.owner.startsWith("org/springframework/web/client/RestClient")) ->
                        urlArg(insn, constants)?.let { url ->
                            facts += callFact(src, precedingVerb(insn), url)
                        }
                }
            }
        }
        return facts
    }

    private fun callFact(src: String, verb: String?, url: String): Fact {
        val attrs = mutableListOf("urlTemplate" to url)
        verb?.let { attrs += "method" to it }
        pathOf(url)?.let { attrs += "path" to it }
        return fact(FactType.OUTGOING_CALL, src, 0.7, *attrs.toTypedArray())
    }

    /** Строковые кандидаты аргументов: назад от вызова до предыдущего invoke, в порядке загрузки. */
    private fun strCandidates(
        insn: org.objectweb.asm.tree.AbstractInsnNode,
        constants: Map<String, String>,
        window: Int = 12,
    ): List<String> {
        val out = mutableListOf<String>()
        var cur = insn.previous
        var steps = 0
        while (cur != null && steps < window) {
            when {
                cur is org.objectweb.asm.tree.LdcInsnNode && cur.cst is String -> out += cur.cst as String
                cur is org.objectweb.asm.tree.FieldInsnNode -> constants["${cur.owner}#${cur.name}"]?.let { out += it }
                // lateinit/null-чеки Kotlin — не граница аргументов
                cur is org.objectweb.asm.tree.MethodInsnNode && !cur.owner.startsWith("kotlin/jvm/internal/") ->
                    return out.reversed()
            }
            cur = cur.previous
            steps++
        }
        return out.reversed()
    }

    private fun topicArg(insn: org.objectweb.asm.tree.AbstractInsnNode, constants: Map<String, String>): String? =
        strCandidates(insn, constants).firstOrNull { it.isNotEmpty() && !it.contains(' ') }

    private fun urlArg(insn: org.objectweb.asm.tree.AbstractInsnNode, constants: Map<String, String>): String? =
        strCandidates(insn, constants).firstOrNull { it.startsWith("http") || it.contains('/') }

    /** Для WebClient/RestClient глагол — из предыдущего .get()/.post() в цепочке. */
    private fun precedingVerb(insn: org.objectweb.asm.tree.AbstractInsnNode, window: Int = 8): String? {
        var cur = insn.previous
        var steps = 0
        while (cur != null && steps < window) {
            if (cur is org.objectweb.asm.tree.MethodInsnNode) {
                val verb = cur.name.uppercase()
                return if (verb in httpVerbs) verb else null
            }
            cur = cur.previous
            steps++
        }
        return null
    }

    private fun verbOfRest(m: String): String? = when {
        m.startsWith("get") -> "GET"
        m.startsWith("post") -> "POST"
        m.startsWith("put") -> "PUT"
        m.startsWith("delete") -> "DELETE"
        m.startsWith("patch") -> "PATCH"
        else -> null
    }

    /** path-часть URL: после хоста или как есть; без query. */
    private fun pathOf(url: String): String? {
        val path = when {
            url.startsWith("http://") || url.startsWith("https://") -> {
                val rest = url.substringAfter("//")
                if (rest.contains('/')) "/" + rest.substringAfter('/') else null
            }
            url.startsWith("/") -> url
            else -> null
        }
        return path?.substringBefore('?')
    }

    private fun ann(list: List<AnnotationNode>?, desc: String): AnnotationNode? =
        list?.firstOrNull { it.desc == desc }

    /** Значение атрибута аннотации: строка или первый элемент массива строк. */
    private fun value(a: AnnotationNode?, key: String): String? {
        val values = a?.values ?: return null
        var i = 0
        while (i < values.size - 1) {
            if (values[i] == key) {
                return when (val v = values[i + 1]) {
                    is String -> v
                    is List<*> -> v.firstOrNull() as? String
                    else -> null
                }
            }
            i += 2
        }
        return null
    }

    private fun joinPath(prefix: String?, value: String?): String {
        val p = (prefix ?: "").trim().removeSuffix("/")
        val v = (value ?: "").trim()
        val joined = when {
            v.isEmpty() -> p
            v.startsWith("/") -> p + v
            else -> "$p/$v"
        }
        return if (joined.startsWith("/")) joined else "/$joined"
    }

    private fun recognizeClass(jarName: String, cls: ClassNode): List<Fact> {
        val facts = mutableListOf<Fact>()
        val fqcn = cls.name.replace('/', '.')
        fun src(method: String?) = "$jarName!$fqcn${method?.let { "#$it" } ?: ""}"

        val isController = ann(cls.visibleAnnotations, "${PKG_WEB}RestController;") != null ||
            ann(cls.visibleAnnotations, "Lorg/springframework/stereotype/RestController;") != null
        val prefix = value(ann(cls.visibleAnnotations, "${PKG_WEB}RequestMapping;"), "value")
            ?: value(ann(cls.visibleAnnotations, "${PKG_WEB}RequestMapping;"), "path")
        val feign = ann(cls.visibleAnnotations, "Lorg/springframework/cloud/openfeign/FeignClient;")

        for (m in cls.methods.sortedBy { it.name }) {
            val mapping = MAPPINGS.entries.firstOrNull { ann(m.visibleAnnotations, it.key) != null }
            if (mapping != null) {
                val a = ann(m.visibleAnnotations, mapping.key)
                val path = joinPath(prefix, value(a, "value") ?: value(a, "path"))
                when {
                    feign != null -> {
                        val attrs = mutableListOf("method" to mapping.value, "path" to path)
                        (value(feign, "name") ?: value(feign, "value"))?.let { attrs += "feignName" to it }
                        value(feign, "url")?.let { attrs += "urlTemplate" to it }
                        facts += fact(FactType.OUTGOING_CALL, src(m.name), 0.8, *attrs.toTypedArray())
                    }
                    isController -> {
                        val attrs = mutableListOf("method" to mapping.value, "path" to path)
                        if (ann(m.visibleAnnotations, "Ljava/lang/Deprecated;") != null) attrs += "deprecated" to "true"
                        facts += fact(FactType.ENDPOINT, src(m.name), 0.8, *attrs.toTypedArray())
                    }
                }
            }

            ann(m.visibleAnnotations, "Lorg/springframework/kafka/annotation/KafkaListener;")?.let { kl ->
                val topic = value(kl, "topics") ?: value(kl, "value")
                if (topic != null) {
                    val attrs = mutableListOf("channel" to topic, "protocol" to "kafka")
                    value(kl, "groupId")?.let { attrs += "group" to it }
                    facts += fact(FactType.SUBSCRIBE, src(m.name), 0.8, *attrs.toTypedArray())
                }
            }
            ann(m.visibleAnnotations, "Lorg/springframework/amqp/rabbit/annotation/RabbitListener;")?.let { rl ->
                val queue = value(rl, "queues") ?: value(rl, "value")
                if (queue != null) {
                    facts += fact(
                        FactType.SUBSCRIBE, src(m.name), 0.8,
                        "channel" to queue, "protocol" to "amqp",
                    )
                }
            }
            if (ann(m.visibleAnnotations, "Lorg/springframework/scheduling/annotation/Scheduled;") != null) {
                facts += fact(FactType.CONTAINER_HINT, src(m.name), 0.8, "scheduled" to "true")
            }
        }

        // Spring Data: интерфейс, наследующий известный репозиторий; entity — из generic-сигнатуры.
        val repoBase = cls.interfaces?.firstOrNull { it.substringAfterLast('/') in REPOSITORY_BASES }
        if (repoBase != null && (cls.access and org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0) {
            val entity = cls.signature
                ?.substringAfter('<', "")
                ?.substringBefore(';', "")
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotEmpty() } ?: ""
            facts += fact(
                FactType.STORE_ACCESS, src(null), 0.8,
                "kind" to "jdbc", "address" to "", "access" to "readwrite", "entities" to entity,
            )
        }
        return facts
    }
}
