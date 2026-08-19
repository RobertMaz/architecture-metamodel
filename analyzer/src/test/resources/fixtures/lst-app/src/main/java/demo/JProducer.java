package demo;

import org.springframework.kafka.core.KafkaTemplate;

public class JProducer {
    private KafkaTemplate kafkaTemplate;

    void publish(String payload) {
        // Java-парсер не видит Kotlin-стаб — тип не атрибутирован, эвристика по имени
        kafkaTemplate.send("orders.created", payload);
    }
}
