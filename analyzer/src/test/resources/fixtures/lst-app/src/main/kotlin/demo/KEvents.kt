package demo

import org.springframework.kafka.core.KafkaTemplate

object Topics {
    const val ORDERS = "orders.created"
}

class KProducer(private val kafkaTemplate: KafkaTemplate<String, String>) {
    fun publish(payload: String) {
        kafkaTemplate.send(Topics.ORDERS, payload)
    }
}
