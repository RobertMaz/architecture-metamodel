package arch.analyzer.core

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule

/**
 * Канонический JSON: отсортированные ключи, 2 пробела, \n, финальный перевод строки.
 * Любой файл, который пишет анализатор, обязан проходить через Json.write —
 * это и есть механика «повторный прогон даёт байт-в-байт тот же результат».
 */
object Json {
    private val printer = DefaultPrettyPrinter().apply {
        indentObjectsWith(DefaultIndenter("  ", "\n"))
        indentArraysWith(DefaultIndenter("  ", "\n"))
    }

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        .build()

    fun write(value: Any): String = mapper.writer(printer).writeValueAsString(value) + "\n"

    fun <T> read(text: String, type: Class<T>): T = mapper.readValue(text, type)
}
