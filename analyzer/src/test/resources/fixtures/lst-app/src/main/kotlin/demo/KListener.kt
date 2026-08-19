package demo

import org.springframework.kafka.annotation.KafkaListener

class KListener {
    @KafkaListener(topics = [Topics.ORDERS], groupId = "billing")
    fun on(msg: String) {
    }
}
