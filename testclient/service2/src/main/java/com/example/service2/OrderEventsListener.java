package com.example.service2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.example.service2.Service2Application.ORDERS_TOPIC;

/**
 * Reads service1's orders.events: when an order is "done", its ready ticket is marked served and sent to
 * kitchen.events. The other events are ignored.
 */
@Component
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);

    private final JdbcClient jdbc;
    private final Tickets tickets;
    private final ObjectMapper mapper;

    public OrderEventsListener(JdbcClient jdbc, Tickets tickets, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tickets = tickets;
        this.mapper = mapper;
    }

    @KafkaListener(topics = ORDERS_TOPIC)
    public void onOrderEvent(String message) {
        JsonNode event;
        try {
            event = mapper.readTree(message);
        } catch (JsonProcessingException e) {
            log.warn("Skipping an {} message that isn't JSON: {}", ORDERS_TOPIC, message);
            return;
        }
        if (!"done".equals(event.path("event").asText())) {
            return;
        }
        String processingId = event.path("processingId").asText();
        int updated = jdbc.sql("UPDATE kitchen.tickets SET state = 'served' WHERE processing_id = ? AND state = 'ready'")
                .param(processingId).update();
        if (updated == 0) {
            log.info("Order {} is done but there's no ready ticket for it", processingId);
            return;
        }
        tickets.find(processingId).ifPresent(tickets::publish);
    }
}
