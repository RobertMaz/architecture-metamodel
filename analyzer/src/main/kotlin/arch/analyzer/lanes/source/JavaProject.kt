package arch.analyzer.lanes.source

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.jvm.optionals.getOrNull

/**
 * Java-сорцы одного репозитория: обход src/main/java, парсинг с symbol solver'ом
 * (ReflectionTypeSolver + сорцы самого проекта — этого хватает, чтобы резолвить
 * свои типы; чужие резолвятся best-effort). Порядок файлов отсортирован — детерминизм.
 */
class JavaProject(private val repoDir: Path) {

    val srcRoot: Path = repoDir.resolve("src/main/java")

    fun exists(): Boolean = srcRoot.exists()

    private val units: List<Pair<Path, CompilationUnit>> by lazy {
        if (!exists()) return@lazy emptyList()
        val solver = CombinedTypeSolver(ReflectionTypeSolver(false), JavaParserTypeSolver(srcRoot))
        val parser = JavaParser(
            ParserConfiguration()
                .setSymbolResolver(JavaSymbolSolver(solver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE),
        )
        Files.walk(srcRoot).use { s ->
            s.filter { it.isRegularFile() && it.extension == "java" }
                .sorted()
                .toList()
        }.mapNotNull { p ->
            parser.parse(p).result.getOrNull()?.let { p to it }
        }
    }

    fun compilationUnits(): List<Pair<Path, CompilationUnit>> = units

    /** Путь относительно корня репозитория — таким он едет в source-поле факта. */
    fun rel(p: Path): String = repoDir.relativize(p).toString().replace('\\', '/')

    fun line(node: Node): Int = node.range.getOrNull()?.begin?.line ?: 0

    fun sourceRef(p: Path, node: Node): String = "${rel(p)}#L${line(node)}"
}

interface SourceRecognizer {
    fun recognize(project: JavaProject): List<arch.analyzer.core.Fact>
}
