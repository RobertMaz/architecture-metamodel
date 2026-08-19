package arch.analyzer.lanes

import arch.analyzer.core.Evidence
import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.InputRef
import arch.analyzer.core.Json
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import arch.analyzer.core.fact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Полка clientlibs (п. 8 плана): drill down цепочки вызовов через клиентские
 * api-библиотеки. registry/clientlibs.yml маппит артефакт -> контейнер-цель:
 *
 *   clientlibs:
 *     com.acme:billing-client:
 *       container: shop.billing
 *       jar: /path/to/billing-client.jar    # профиль операций из байткода
 *       path: /path/to/billing-client-repo  # или из сорцов (lst, fallback: регулярки)
 *
 * Зависимость в pom/gradle -> факт «использует клиента» (conf 0.6, ребро в
 * контейнер). Если задан jar либы — она профилируется теми же ASM-распознавателями
 * (Feign-интерфейсы, call-sites п. 9); если сорцы — lst-полкой (или source-регулярками,
 * когда экстрактор не собран). Кэш в workspace/_clientlibs/; зависимость + профиль ->
 * call-рёбра в конкретные operations (conf 0.7). Байткод сервиса подтверждает,
 * какие методы либы реально зовутся: подтверждённые — 0.85, остальные не эмитятся
 * (байткод — ground truth по статическим вызовам).
 */
class ClientLibsLane(
    private val archRoot: Path = Paths.get("."),
    private val lstExtractorDir: Path = archRoot.resolve("analyzer/lst-extractor"),
) : Lane {

    override val name = "clientlibs"

    private val yaml = ObjectMapper(YAMLFactory())

    private fun registryFile(): Path = archRoot.resolve("registry/clientlibs.yml")
    private fun cacheDir(): Path = archRoot.resolve("workspace/_clientlibs")

    data class ClientLib(val container: String, val jar: String?, val path: String?)

    private fun clientLibs(): Map<String, ClientLib> {
        val file = registryFile()
        if (!file.exists()) return emptyMap()
        val root = yaml.readTree(file.toFile())?.get("clientlibs") ?: return emptyMap()
        val out = sortedMapOf<String, ClientLib>()
        root.fields().forEach { (coord, node) ->
            val container = node.get("container")?.asText()?.takeIf { it.isNotEmpty() } ?: return@forEach
            out[coord] = ClientLib(
                container,
                node.get("jar")?.asText()?.takeIf { it.isNotEmpty() },
                node.get("path")?.asText()?.takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    private val buildFileNames = setOf("pom.xml", "build.gradle", "build.gradle.kts", "libs.versions.toml")

    private fun buildFiles(input: RepoInput): List<Path> {
        if (!input.repoDir.exists()) return emptyList()
        return Files.walk(input.repoDir, 3).use { s ->
            s.filter { it.isRegularFile() && it.name in buildFileNames }
                .filter { p ->
                    input.repoDir.relativize(p)
                        .none { seg -> seg.toString() in setOf("src", "target", "build", "node_modules") }
                }
                .sorted()
                .toList()
        }
    }

    override fun applicable(input: RepoInput): Boolean =
        registryFile().exists() && buildFiles(input).isNotEmpty()

    override fun extract(input: RepoInput): List<Fact> {
        val libs = clientLibs()
        if (libs.isEmpty()) return emptyList()
        val deps = buildFiles(input).flatMap { dependencies(it) }.toSet()
        val facts = mutableListOf<Fact>()

        for ((coord, lib) in libs) {
            if (coord !in deps) continue
            val profile = profileOps(coord, lib)
            if (profile.isEmpty()) {
                // профиля нет — хотя бы контейнерное ребро «использует клиента»
                facts += fact(
                    FactType.OUTGOING_CALL, "pom/gradle: $coord", 0.6,
                    "container" to lib.container, "prop" to coord,
                )
                continue
            }
            // байткод сервиса подтверждает реально вызываемые методы либы
            val invoked = input.jar?.takeIf { it.exists() }
                ?.let { invokedMethods(it, profile.map { op -> op.owner }.toSet()) }
            for (op in profile) {
                val confirmed = op.method.isNotEmpty() && invoked != null && (op.owner to op.method) in invoked
                // отбрасываем только операции с известным именем метода, которые точно не зовутся
                if (invoked != null && invoked.isNotEmpty() && op.method.isNotEmpty() && !confirmed) continue
                val conf = if (confirmed) 0.85 else 0.7
                val attrs = mutableListOf("container" to lib.container, "prop" to coord)
                op.httpMethod?.let { attrs += "method" to it }
                op.path?.let { attrs += "path" to it }
                facts += fact(
                    FactType.OUTGOING_CALL,
                    "$coord!${op.owner}${if (op.method.isNotEmpty()) "#${op.method}" else ""}",
                    conf, *attrs.toTypedArray(),
                )
            }
        }
        return facts.distinct()
    }

    // ---- зависимости из build-файлов ----------------------------------

    /** groupId:artifactId из pom.xml (DOM) или gradle-скриптов (координаты в кавычках). */
    fun dependencies(buildFile: Path): Set<String> = when {
        buildFile.name == "pom.xml" -> pomDependencies(buildFile)
        else -> gradleDependencies(buildFile)
    }

    private fun pomDependencies(pom: Path): Set<String> = runCatching {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(pom.toFile())
        val out = sortedSetOf<String>()
        val deps = doc.getElementsByTagName("dependency")
        for (i in 0 until deps.length) {
            val dep = deps.item(i)
            var g: String? = null
            var a: String? = null
            val children = dep.childNodes
            for (j in 0 until children.length) {
                when (children.item(j).nodeName) {
                    "groupId" -> g = children.item(j).textContent.trim()
                    "artifactId" -> a = children.item(j).textContent.trim()
                }
            }
            if (g != null && a != null) out += "$g:$a"
        }
        out as Set<String>
    }.getOrElse { emptySet() }

    private fun gradleDependencies(file: Path): Set<String> =
        Regex("[\"']([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+)(?::[^\"']+)?[\"']")
            .findAll(runCatching { file.readText() }.getOrElse { return emptySet() })
            .map { "${it.groupValues[1]}:${it.groupValues[2]}" }
            .toSortedSet()

    // ---- профиль либы ---------------------------------------------------

    data class LibOp(val owner: String, val method: String, val httpMethod: String?, val path: String?)

    /**
     * Профиль либы один раз, кэш в workspace/_clientlibs/<coord>.json:
     * jar -> те же ASM-распознаватели (Feign, call-sites п. 9), инвалидация по mtime jar;
     * сорцы -> lst-полка (typed, имена методов), а без собранного экстрактора —
     * source-регулярки (методов нет — рёбра без подтверждения байткодом, 0.7).
     */
    private fun profileOps(coord: String, lib: ClientLib): List<LibOp> {
        lib.jar?.let { Paths.get(it) }?.takeIf { it.exists() }?.let { jar ->
            return cachedFacts(coord, jar.getLastModifiedTime()) {
                BytecodeLane().extract(RepoInput("clientlib", jar.parent ?: Paths.get("."), jar = jar))
            }.let { jarOps(it) }
        }
        val src = lib.path?.let { Paths.get(it) }?.takeIf { it.exists() } ?: return emptyList()
        val newest = Files.walk(src).use { s ->
            s.filter { it.isRegularFile() && (it.name.endsWith(".java") || it.name.endsWith(".kt")) }
                .map { it.getLastModifiedTime() }
                .max(Comparator.naturalOrder()).orElse(java.nio.file.attribute.FileTime.fromMillis(0))
        }
        return cachedFacts(coord, newest) {
            val input = RepoInput("clientlib", src)
            val lst = LstLane(extractorDir = lstExtractorDir)
            if (lst.applicable(input)) lst.extract(input) else SourceLane().extract(input)
        }.let { sourceOps(it) }
    }

    private fun cachedFacts(coord: String, inputMtime: java.nio.file.attribute.FileTime, compute: () -> List<Fact>): List<Fact> {
        val cache = cacheDir().resolve(coord.replace(':', '_') + ".json")
        if (cache.exists() && cache.getLastModifiedTime() >= inputMtime) {
            return Json.read(cache.readText(), Evidence::class.java).facts
        }
        val extracted = compute()
        cacheDir().createDirectories()
        cache.toFile().writeText(Json.write(Evidence(name, InputRef("clientlib", coord), extracted).canonical()))
        return extracted
    }

    /** Байткод-факты: source «lib.jar!com.acme.BillingApi#create» -> owner FQCN + метод. */
    private fun jarOps(facts: List<Fact>): List<LibOp> = facts
        .filter { it.type == FactType.OUTGOING_CALL && it.source.contains('!') }
        .map { f ->
            LibOp(
                owner = f.source.substringAfter('!').substringBefore('#'),
                method = f.source.substringAfter('#', ""),
                httpMethod = f.attrs["method"],
                path = f.attrs["path"],
            )
        }
        .filter { it.method.isNotEmpty() }

    /**
     * Source-факты: lst даёт «src/main/java/acme/BillingApi.java#BillingApi.create» —
     * FQCN восстанавливается из пути, метод из хвоста. Регулярки дают только
     * «файл#L42» — owner есть, метода нет.
     */
    private fun sourceOps(facts: List<Fact>): List<LibOp> = facts
        .filter { it.type == FactType.OUTGOING_CALL && (it.attrs["path"] != null || it.attrs["urlTemplate"] != null) }
        .map { f ->
            val file = f.source.substringBefore('#')
            val tail = f.source.substringAfter('#', "")
            LibOp(
                owner = file
                    .substringAfter("src/main/java/", file.substringAfter("src/main/kotlin/", file))
                    .removeSuffix(".java").removeSuffix(".kt")
                    .replace('/', '.'),
                method = if (tail.contains('.')) tail.substringAfterLast('.') else "",
                httpMethod = f.attrs["method"],
                path = f.attrs["path"],
            )
        }

    /** Какие методы каких классов либы реально зовутся из байткода сервиса. */
    private fun invokedMethods(serviceJar: Path, libOwners: Set<String>): Set<Pair<String, String>> {
        val slashOwners = libOwners.associateBy { it.replace('.', '/') }
        val out = mutableSetOf<Pair<String, String>>()
        JarFile(serviceJar.toFile()).use { jar ->
            for (entry in jar.entries().asSequence().filter { it.name.endsWith(".class") }) {
                val node = ClassNode()
                jar.getInputStream(entry).use {
                    ClassReader(it.readBytes()).accept(node, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
                }
                for (m in node.methods) {
                    for (insn in m.instructions ?: continue) {
                        if (insn is MethodInsnNode) {
                            slashOwners[insn.owner]?.let { out += it to insn.name }
                        }
                    }
                }
            }
        }
        return out
    }
}
