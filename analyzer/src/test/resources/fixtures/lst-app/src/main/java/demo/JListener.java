package demo;

import org.springframework.kafka.annotation.KafkaListener;

public class JListener {
    @KafkaListener(topics = {"orders.created"}, groupId = "billing")
    public void on(String msg) {
    }
}
