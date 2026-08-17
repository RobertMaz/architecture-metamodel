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
            for (entry in entries) {
                val node = ClassNode()
                jar.getInputStream(entry).use { ClassReader(it.readBytes()).accept(node, ClassReader.SKIP_CODE) }
                facts += recognizeClass(jarPath.name, node)
            }
        }
        return facts
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
                    val attrs = mutableListOf("channel" to topic)
                    value(kl, "groupId")?.let { attrs += "group" to it }
                    facts += fact(FactType.SUBSCRIBE, src(m.name), 0.8, *attrs.toTypedArray())
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
