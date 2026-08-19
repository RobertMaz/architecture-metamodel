package arch.analyzer.lanes

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.Lane
import arch.analyzer.core.RepoInput
import arch.analyzer.core.fact
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Полка springwolf: Kafka/AMQP consumers из чужого Spring Boot fat-jar без запуска.
 * Springwolf standalone сканирует BOOT-INF/classes жертвы на classpath отдельного
 * процесса-сканера (analyzer/springwolf-scanner, собирается один раз) и отдаёт
 * AsyncAPI 3 JSON: каналы, groupId, payload, protocol (kafka|amqp), с резолвом
 * `${...}`-топиков из application.yml жертвы.
 *
 * Полка опциональная, как jqassistant: нет jar или несобран сканер — не применима,
 * упала (Boot 2/javax-жертва) — реконсилятор живёт на ASM-фактах. Producers
 * (KafkaTemplate.send) Springwolf не видит — они остаются за bytecode-полкой.
 */
class SpringwolfLane(
    private val scannerDir: Path = Paths.get("analyzer/springwolf-scanner"),
) : Lane {

    override val name = "springwolf"

    private fun scannerClasses() = scannerDir.resolve("target/classes")
    private fun scannerCp() = scannerDir.resolve("target/cp.txt")

    override fun applicable(input: RepoInput): Boolean =
        input.jar?.exists() == true && scannerClasses().exists() && scannerCp().exists()

    override fun extract(input: RepoInput): List<Fact> {
        val jar = input.jar ?: return emptyList()
        val basePackage = startClassPackage(jar)
            ?: error("в манифесте нет Start-Class — не Spring Boot fat-jar: $jar")
        val tmp = Files.createTempDirectory("springwolf-scan")
        try {
            explode(jar, tmp)
            val out = tmp.resolve("asyncapi.json")
            runScanner(basePackage, input.containerId, tmp, out)
            return parseAsyncApi(out.readText(), jar.name)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun startClassPackage(jar: Path): String? =
        JarFile(jar.toFile()).use { j ->
            j.manifest?.mainAttributes?.getValue("Start-Class")?.substringBeforeLast('.')
        }

    /** BOOT-INF распакованного fat-jar встаёт на classpath сканера. */
    private fun explode(jar: Path, dst: Path) {
        ZipFile(jar.toFile()).use { zip ->
            for (e in zip.entries()) {
                if (!e.name.startsWith("BOOT-INF/")) continue
                val target = dst.resolve(e.name).normalize()
                require(target.startsWith(dst)) { "zip-slip: ${e.name}" }
                if (e.isDirectory) {
                    target.createDirectories()
                } else {
                    target.parent.createDirectories()
                    zip.getInputStream(e).use { Files.copy(it, target) }
                }
            }
        }
    }

    private fun runScanner(basePackage: String, title: String, exploded: Path, out: Path) {
        val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        // Порядок важен: classpath сканера раньше BOOT-INF/lib жертвы,
        // чтобы Spring самого сканера выигрывал у спринга жертвы.
        val cp = listOf(
            scannerClasses().toAbsolutePath().toString(),
            scannerCp().readText().trim(),
            exploded.resolve("BOOT-INF/classes").toString(),
            exploded.resolve("BOOT-INF/lib").toString() + java.io.File.separator + "*",
        ).joinToString(java.io.File.pathSeparator)
        val p = ProcessBuilder(javaBin, "-cp", cp, "com.scanner.Main", basePackage, out.toString(), title)
            .redirectErrorStream(true)
            .start()
        val log = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(5, TimeUnit.MINUTES) || p.exitValue() != 0) {
            p.destroyForcibly()
            error("сканер springwolf упал (exit=${runCatching { p.exitValue() }.getOrNull()}): ${errorDigest(log)}")
        }
    }
}

/**
 * AsyncAPI 3 -> факты SUBSCRIBE. Только receive-операции: producers Springwolf
 * не видит (подтверждено пилотом), это зона ASM. Канал с нерезолвнутым
 * плейсхолдером (`$_..._`) — «топик из внешнего конфига»: confidence 0.6
 * и externalConfig=true, чтобы попасть в отчёт на разбор.
 */
fun parseAsyncApi(json: String, jarName: String): List<Fact> {
    val root = ObjectMapper().readTree(json)
    val facts = mutableListOf<Fact>()
    for ((opId, op) in root.path("operations").fields().asSequence().sortedBy { it.key }) {
        if (op.path("action").asText() != "receive") continue
        val channel = op.path("channel").path("\$ref").asText("").substringAfterLast('/')
        if (channel.isEmpty()) continue
        val protocol = op.path("bindings").fieldNames().asSequence()
            .firstOrNull { it in setOf("kafka", "amqp") }
        val group = op.path("bindings").path("kafka").path("groupId").path("enum")
            .firstOrNull()?.asText()
        val payload = op.path("messages")
            .mapNotNull { it.path("\$ref").asText("").substringAfterLast('/').takeIf(String::isNotEmpty) }
            .map { it.substringAfterLast('.') }
            .distinct().sorted().joinToString(",").ifEmpty { null }
        val unresolved = channel.contains("\$_") || channel.contains("\${")

        val attrs = mutableListOf("channel" to channel)
        group?.let { attrs += "group" to it }
        payload?.let { attrs += "payload" to it }
        protocol?.let { attrs += "protocol" to it }
        if (unresolved) attrs += "externalConfig" to "true"
        facts += fact(FactType.SUBSCRIBE, "$jarName!$opId", if (unresolved) 0.6 else 0.9, *attrs.toTypedArray())
    }
    return facts
}
