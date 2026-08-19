package arch.analyzer.lanes

import arch.analyzer.core.FactType
import arch.analyzer.core.RepoInput
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тестовый JAR собирается самим ASM: аннотации в class-файле — это строки-дескрипторы,
 * реальный Spring на classpath не нужен. Это же гарантирует, что полка читает
 * именно байткод, а не рефлексию.
 */
class BytecodeLaneTest {

    private fun buildJar(): Path {
        val dir = Files.createTempDirectory("bytecode-jar")
        val jar = dir.resolve("demo-app.jar")

        fun controller(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/OwnerController", null, "java/lang/Object", null)
            cw.visitAnnotation("Lorg/springframework/web/bind/annotation/RestController;", true).visitEnd()
            cw.visitAnnotation("Lorg/springframework/web/bind/annotation/RequestMapping;", true).apply {
                visitArray("value").apply { visit(null, "/owners"); visitEnd() }
                visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "findOwner", "(I)Ldemo/OwnerDto;", null, null).apply {
                visitAnnotation("Lorg/springframework/web/bind/annotation/GetMapping;", true).apply {
                    visitArray("value").apply { visit(null, "/{ownerId}"); visitEnd() }
                    visitEnd()
                }
                visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "legacy", "()V", null, null).apply {
                visitAnnotation("Lorg/springframework/web/bind/annotation/GetMapping;", true).apply {
                    visitArray("value").apply { visit(null, "/legacy"); visitEnd() }
                    visitEnd()
                }
                visitAnnotation("Ljava/lang/Deprecated;", true).visitEnd()
                visitEnd()
            }
            cw.visitEnd()
            return cw.toByteArray()
        }

        fun feign(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(
                Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                "demo/BillingClient", null, "java/lang/Object", null,
            )
            cw.visitAnnotation("Lorg/springframework/cloud/openfeign/FeignClient;", true).apply {
                visit("name", "billing")
                visit("url", "\${billing.url}")
                visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "create", "()Ljava/lang/Object;", null, null).apply {
                visitAnnotation("Lorg/springframework/web/bind/annotation/PostMapping;", true).apply {
                    visitArray("value").apply { visit(null, "/api/v1/invoices"); visitEnd() }
                    visitEnd()
                }
                visitEnd()
            }
            cw.visitEnd()
            return cw.toByteArray()
        }

        fun listener(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/OrderEvents", null, "java/lang/Object", null)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "on", "(Ldemo/OrderCreated;)V", null, null).apply {
                visitAnnotation("Lorg/springframework/kafka/annotation/KafkaListener;", true).apply {
                    visitArray("topics").apply { visit(null, "order.created"); visitEnd() }
                    visit("groupId", "billing-cg")
                    visitEnd()
                }
                visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null).apply {
                visitAnnotation("Lorg/springframework/scheduling/annotation/Scheduled;", true).visitEnd()
                visitEnd()
            }
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** Реальный байткод вызовов: п. 9 — call-sites без SKIP_CODE. */
        fun publisher(): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/OrderPublisher", null, "java/lang/Object", null)
            cw.visitField(Opcodes.ACC_PRIVATE, "kafka", "Lorg/springframework/kafka/core/KafkaTemplate;", null, null).visitEnd()
            cw.visitField(Opcodes.ACC_PRIVATE, "rabbit", "Lorg/springframework/amqp/rabbit/core/RabbitTemplate;", null, null).visitEnd()
            cw.visitField(Opcodes.ACC_PRIVATE, "rest", "Lorg/springframework/web/client/RestTemplate;", null, null).visitEnd()
            cw.visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "RETRY_TOPIC", "Ljava/lang/String;", null, "orders.retry",
            ).visitEnd()

            // kafka.send("orders.created", payload)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "publish", "(Ljava/lang/Object;)V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitFieldInsn(Opcodes.GETFIELD, "demo/OrderPublisher", "kafka", "Lorg/springframework/kafka/core/KafkaTemplate;")
                visitLdcInsn("orders.created")
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "org/springframework/kafka/core/KafkaTemplate", "send",
                    "(Ljava/lang/String;Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;", false,
                )
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            // kafka.send(RETRY_TOPIC, payload) — топик из константы другого поля
            cw.visitMethod(Opcodes.ACC_PUBLIC, "retry", "(Ljava/lang/Object;)V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitFieldInsn(Opcodes.GETFIELD, "demo/OrderPublisher", "kafka", "Lorg/springframework/kafka/core/KafkaTemplate;")
                visitFieldInsn(Opcodes.GETSTATIC, "demo/OrderPublisher", "RETRY_TOPIC", "Ljava/lang/String;")
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "org/springframework/kafka/core/KafkaTemplate", "send",
                    "(Ljava/lang/String;Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;", false,
                )
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            // rabbit.convertAndSend("billing-queue", payload)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "bill", "(Ljava/lang/Object;)V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitFieldInsn(Opcodes.GETFIELD, "demo/OrderPublisher", "rabbit", "Lorg/springframework/amqp/rabbit/core/RabbitTemplate;")
                visitLdcInsn("billing-queue")
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "org/springframework/amqp/rabbit/core/RabbitTemplate", "convertAndSend",
                    "(Ljava/lang/String;Ljava/lang/Object;)V", false,
                )
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            // rest.postForObject("http://billing-service/api/v1/invoices", body, String.class)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "pay", "(Ljava/lang/Object;)V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitFieldInsn(Opcodes.GETFIELD, "demo/OrderPublisher", "rest", "Lorg/springframework/web/client/RestTemplate;")
                visitLdcInsn("http://billing-service/api/v1/invoices")
                visitVarInsn(Opcodes.ALOAD, 1)
                visitLdcInsn(org.objectweb.asm.Type.getType("Ljava/lang/String;"))
                visitInsn(Opcodes.ICONST_0)
                visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "org/springframework/web/client/RestTemplate", "postForObject",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;", false,
                )
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            cw.visitEnd()
            return cw.toByteArray()
        }

        fun repository(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(
                Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                "demo/OwnerRepository",
                "Ljava/lang/Object;Lorg/springframework/data/jpa/repository/JpaRepository<Ldemo/Owner;Ljava/lang/Integer;>;",
                "java/lang/Object",
                arrayOf("org/springframework/data/jpa/repository/JpaRepository"),
            )
            cw.visitEnd()
            return cw.toByteArray()
        }

        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            for ((name, bytes) in listOf(
                "demo/OwnerController.class" to controller(),
                "demo/BillingClient.class" to feign(),
                "demo/OrderEvents.class" to listener(),
                "demo/OrderPublisher.class" to publisher(),
                "demo/OwnerRepository.class" to repository(),
            )) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private val lane = BytecodeLane()

    /** javac-байткод, а не собранный ASM: продюсер с топиком из @Value-поля. */
    @Test
    fun `e2e демо-жертва - топик из @Value-поля через GETFIELD`() {
        val jar = Path.of("../workspace/_pilots/springwolf-exp/demo-kafka-app/target/demo-kafka-app-0.0.1.jar")
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(jar), "демо-жертва не собрана — пропуск")

        val facts = lane.extract(RepoInput("demo.kafka", Files.createTempDirectory("r"), jar = jar))
        val pub = facts.single { it.type == FactType.PUBLISH }
        assertEquals("\${app.topics.notifications}", pub.attrs["channel"], "плейсхолдер честно ждёт резолвера")
        assertEquals("kafka", pub.attrs["protocol"])
        assertTrue(pub.source.endsWith("NotificationService#sendEmailNotification"), pub.source)
    }

    @Test
    fun `полка применима только при наличии jar`() {
        val dir = Files.createTempDirectory("no-jar")
        assertTrue(!lane.applicable(RepoInput("x", dir)))
        assertTrue(lane.applicable(RepoInput("x", dir, jar = buildJar())))
    }

    @Test
    fun `эндпоинты с классовым префиксом и deprecated`() {
        val facts = lane.extract(RepoInput("x", Files.createTempDirectory("r"), jar = buildJar()))
        val eps = facts.filter { it.type == FactType.ENDPOINT }
        val sigs = eps.map { "${it.attrs["method"]} ${it.attrs["path"]}" }.sorted()
        assertEquals(listOf("GET /owners/legacy", "GET /owners/{ownerId}"), sigs)
        assertEquals("true", eps.single { it.attrs["path"] == "/owners/legacy" }.attrs["deprecated"])
        assertTrue(eps.all { it.confidence == 0.8 })
        assertTrue(eps.all { it.source.startsWith("demo-app.jar!demo.OwnerController#") }, eps.toString())
    }

    @Test
    fun `call-sites без SKIP_CODE - producers и вызовы из тел методов`() {
        val facts = lane.extract(RepoInput("x", Files.createTempDirectory("r"), jar = buildJar()))

        val pubs = facts.filter { it.type == FactType.PUBLISH }
        val kafka = pubs.single { it.attrs["channel"] == "orders.created" }
        assertEquals("kafka", kafka.attrs["protocol"])
        assertEquals(0.7, kafka.confidence)
        assertTrue(kafka.source.endsWith("demo.OrderPublisher#publish"), kafka.source)

        assertTrue(
            pubs.any { it.attrs["channel"] == "orders.retry" },
            "топик из константы поля через GETSTATIC: $pubs",
        )
        val amqp = pubs.single { it.attrs["protocol"] == "amqp" }
        assertEquals("billing-queue", amqp.attrs["channel"])

        val rest = facts.single {
            it.type == FactType.OUTGOING_CALL && it.attrs["urlTemplate"] != null && it.attrs["feignName"] == null
        }
        assertEquals("POST", rest.attrs["method"])
        assertEquals("http://billing-service/api/v1/invoices", rest.attrs["urlTemplate"])
        assertEquals("/api/v1/invoices", rest.attrs["path"])
    }

    @Test
    fun `feign, kafka, scheduled и spring data из байткода`() {
        val facts = lane.extract(RepoInput("x", Files.createTempDirectory("r"), jar = buildJar()))

        val call = facts.single { it.type == FactType.OUTGOING_CALL && it.attrs["feignName"] != null }
        assertEquals("POST", call.attrs["method"])
        assertEquals("/api/v1/invoices", call.attrs["path"])
        assertEquals("billing", call.attrs["feignName"])

        val sub = facts.single { it.type == FactType.SUBSCRIBE }
        assertEquals("order.created", sub.attrs["channel"])
        assertEquals("billing-cg", sub.attrs["group"])

        assertTrue(facts.any { it.type == FactType.CONTAINER_HINT && it.attrs["scheduled"] == "true" })

        val store = facts.single { it.type == FactType.STORE_ACCESS }
        assertEquals("jdbc", store.attrs["kind"])
        assertEquals("Owner", store.attrs["entities"])
        assertEquals("", store.attrs["address"])
    }
}
