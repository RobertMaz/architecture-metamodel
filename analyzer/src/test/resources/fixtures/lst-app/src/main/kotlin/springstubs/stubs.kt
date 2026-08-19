// Стабы спринговых типов: дают typed-резолв фикстуре без настоящего classpath
package org.springframework.kafka.core

class KafkaTemplate<K, V> {
    fun send(topic: String, data: V) {}
}
