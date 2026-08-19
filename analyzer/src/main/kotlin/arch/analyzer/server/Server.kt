package arch.analyzer.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Paths

/**
 * Запуск: mvn -q -f analyzer/pom.xml compile exec:java \
 *   -Dexec.mainClass=arch.analyzer.server.ServerKt [-Dexec.args="--arch-root <path> --port 8080"]
 */
fun main(args: Array<String>) {
    fun opt(name: String): String? =
        args.toList().indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

    // Корень данных (п. 10): --arch-root > env ARCH_DATA_ROOT > текущий репо
    val archRoot = Paths.get(opt("--arch-root") ?: System.getenv("ARCH_DATA_ROOT") ?: ".")
        .toAbsolutePath().normalize()
    val port = opt("--port")?.toInt() ?: 8080

    println("arch-analyzer server: root=$archRoot port=$port")
    embeddedServer(Netty, port = port, module = buildApp(archRoot)).start(wait = true)
}
