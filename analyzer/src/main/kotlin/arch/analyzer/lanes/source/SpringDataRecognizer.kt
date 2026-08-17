package arch.analyzer.lanes.source

import arch.analyzer.core.Fact
import arch.analyzer.core.FactType
import arch.analyzer.core.fact
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import kotlin.jvm.optionals.getOrNull

private val REPOSITORY_BASES = setOf(
    "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
    "ListCrudRepository", "ListPagingAndSortingRepository", "Repository",
    "ReactiveCrudRepository", "R2dbcRepository", "MongoRepository",
)

/**
 * Spring Data: интерфейс-репозиторий = read/write в дефолтный datasource.
 * Адрес пустой — его даст config-полка, реконсилятор склеит. Все репозитории
 * контейнера сливаются в один сторо-факт на уровне реконсилятора (entities — объединение).
 */
class SpringDataRecognizer : SourceRecognizer {

    override fun recognize(project: JavaProject): List<Fact> {
        val facts = mutableListOf<Fact>()
        for ((path, cu) in project.compilationUnits()) {
            for (iface in cu.findAll(ClassOrInterfaceDeclaration::class.java)) {
                if (!iface.isInterface) continue
                val base = iface.extendedTypes.firstOrNull { it.nameAsString in REPOSITORY_BASES } ?: continue
                val entity = base.typeArguments.getOrNull()?.firstOrNull()?.toString() ?: ""
                facts += fact(
                    FactType.STORE_ACCESS, project.sourceRef(path, iface), 0.9,
                    "kind" to "jdbc", "address" to "", "access" to "readwrite",
                    "entities" to entity,
                )
            }
        }
        return facts
    }
}
