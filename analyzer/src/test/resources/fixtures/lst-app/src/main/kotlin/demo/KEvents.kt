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

class KValueProducer(private val kafkaTemplate: KafkaTemplate<String, String>) {
    @org.springframework.beans.factory.annotation.Value("\${app.topics.sent}")
    lateinit var sentTopic: String

    fun send(payload: String) {
        kafkaTemplate.send(sentTopic, payload)
    }
}
