package com.example.service1;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.example.service1.Service1Application.KITCHEN_TOPIC;

/**
 * Reads service2's kitchen.events: when a ticket is "ready", its order is marked done and "done" goes to
 * orders.events (which service2 reads to mark the ticket served). The other states are ignored.
 */
@Component
public class KitchenEventsListener {

    private static final Logger log = LoggerFactory.getLogger(KitchenEventsListener.class);

    private final JdbcClient jdbc;
    private final Orders orders;
    private final ObjectMapper mapper;

    public KitchenEventsListener(JdbcClient jdbc, Orders orders, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.mapper = mapper;
    }

    @KafkaListener(topics = KITCHEN_TOPIC)
    public void onKitchenEvent(String message) {
        JsonNode event;
        try {
            event = mapper.readTree(message);
        } catch (JsonProcessingException e) {
            log.warn("Skipping a {} message that isn't JSON: {}", KITCHEN_TOPIC, message);
            return;
        }
        if (!"ready".equals(event.path("state").asText())) {
            return;
        }
        String processingId = event.path("processingId").asText();
        int updated = jdbc.sql("UPDATE shop.orders SET state = 'done' WHERE processing_id = ? AND state = 'created'")
                .param(processingId).update();
        if (updated == 0) {
            log.info("Ticket {} is ready but there's no created order for it", processingId);
            return;
        }
        orders.find(processingId).ifPresent(order -> orders.publish("done", order));
    }
}
