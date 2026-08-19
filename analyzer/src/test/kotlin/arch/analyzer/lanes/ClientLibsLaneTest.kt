package arch.analyzer.lanes

import arch.analyzer.core.FactType
import arch.analyzer.core.RepoInput
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientLibsLaneTest {

    /** Jar клиентской либы: Feign-интерфейс с двумя операциями. */
    private fun libJar(dir: Path): Path {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "acme/BillingApi", null, "java/lang/Object", null,
        )
        cw.visitAnnotation("Lorg/springframework/cloud/openfeign/FeignClient;", true).apply {
            visit("name", "billing")
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "create", "()Ljava/lang/Object;", null, null).apply {
            visitAnnotation("Lorg/springframework/web/bind/annotation/PostMapping;", true).apply {
                visitArray("value").apply { visit(null, "/api/v1/invoices"); visitEnd() }
                visitEnd()
            }
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "find", "()Ljava/lang/Object;", null, null).apply {
            visitAnnotation("Lorg/springframework/web/bind/annotation/GetMapping;", true).apply {
                visitArray("value").apply { visit(null, "/api/v1/invoices/{id}"); visitEnd() }
                visitEnd()
            }
            visitEnd()
        }
        cw.visitEnd()
        val jar = dir.resolve("billing-client.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("acme/BillingApi.class"))
            out.write(cw.toByteArray())
            out.closeEntry()
        }
        return jar
    }

    /** Jar сервиса: зовёт только create() — find() не используется. */
    private fun serviceJar(dir: Path): Path {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/PayService", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "billing", "Lacme/BillingApi;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "pay", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "demo/PayService", "billing", "Lacme/BillingApi;")
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "acme/BillingApi", "create", "()Ljava/lang/Object;", true)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        val jar = dir.resolve("service.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("demo/PayService.class"))
            out.write(cw.toByteArray())
            out.closeEntry()
        }
        return jar
    }

    private data class Setup(val lane: ClientLibsLane, val repo: Path, val jars: Path, val root: Path)

    private fun setup(withProfileJar: Boolean = true): Setup {
        val root = Files.createTempDirectory("clientlibs-root")
        val dir = Files.createTempDirectory("clientlibs-jars")
        val jarLine = if (withProfileJar) "\n    jar: ${libJar(dir)}" else ""
        root.resolve("registry").createDirectories().resolve("clientlibs.yml").writeText(
            "clientlibs:\n  com.acme:billing-client:\n    container: shop.billing$jarLine\n",
        )
        val repo = Files.createTempDirectory("clientlibs-repo")
        repo.resolve("pom.xml").writeText(
            """
            <project>
              <dependencies>
                <dependency>
                  <groupId>com.acme</groupId>
                  <artifactId>billing-client</artifactId>
                </dependency>
              </dependencies>
            </project>
            """.trimIndent(),
        )
        return Setup(ClientLibsLane(archRoot = root), repo, dir, root)
    }

    @Test
    fun `профиль либы плюс байткод сервиса - только реально вызываемые операции`() {
        val (lane, repo, jars, _) = setup()
        val input = RepoInput("shop.pay", repo, jar = serviceJar(jars))
        assertTrue(lane.applicable(input))

        val calls = lane.extract(input).filter { it.type == FactType.OUTGOING_CALL }
        val call = calls.single()
        assertEquals("shop.billing", call.attrs["container"])
        assertEquals("POST", call.attrs["method"])
        assertEquals("/api/v1/invoices", call.attrs["path"])
        assertEquals(0.85, call.confidence, "invoke в байткоде подтвердил вызов")
        assertEquals("com.acme:billing-client", call.attrs["prop"])
    }

    @Test
    fun `без jar сервиса - все операции профиля с conf 0_7`() {
        val (lane, repo, _, _) = setup()
        val calls = lane.extract(RepoInput("shop.pay", repo))
        assertEquals(2, calls.size, "обе операции: $calls")
        assertTrue(calls.all { it.confidence == 0.7 && it.attrs["container"] == "shop.billing" })
    }

    @Test
    fun `без jar либы - контейнерный факт использования клиента`() {
        val (lane, repo, _, _) = setup(withProfileJar = false)
        val call = lane.extract(RepoInput("shop.pay", repo)).single()
        assertEquals("shop.billing", call.attrs["container"])
        assertEquals(null, call.attrs["path"])
        assertEquals(0.6, call.confidence)
    }

    @Test
    fun `зависимости нет - фактов нет, кэш профиля создаётся`() {
        val (lane, _, _, _) = setup()
        val emptyRepo = Files.createTempDirectory("no-dep")
        emptyRepo.resolve("build.gradle").writeText("dependencies { implementation 'other:artifact:1.0' }\n")
        assertEquals(emptyList(), lane.extract(RepoInput("x", emptyRepo)))

        val (lane2, repo2, _, root2) = setup()
        lane2.extract(RepoInput("x", repo2))
        val cache = root2.resolve("workspace/_clientlibs/com.acme_billing-client.json")
        assertTrue(cache.exists(), "кэш профиля: $cache")
        val again = lane2.extract(RepoInput("x", repo2))
        assertEquals(2, again.size, "повторный extract читает кэш")
    }
}
