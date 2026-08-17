package demo;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

class OrderEvents {

    private KafkaTemplate<String, PaymentSucceeded> kafkaTemplate;

    @KafkaListener(topics = "order.created", groupId = "billing-cg")
    void on(OrderCreated e) {
    }

    void emit(PaymentSucceeded evt) {
        kafkaTemplate.send("payment.succeeded", evt);
    }

    @Scheduled(fixedDelay = 1000)
    void tick() {
    }
}

class OrderCreated {
}

class PaymentSucceeded {
}
