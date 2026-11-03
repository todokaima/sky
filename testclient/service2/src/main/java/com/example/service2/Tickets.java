package com.example.service2;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import static com.example.service2.Service2Application.KITCHEN_TOPIC;

/**
 * Reads tickets from kitchen.tickets and sends them to kitchen.events. A ticket (what the endpoints return, and
 * the message, keyed by processingId) is {"ticketId", "processingId", "product", "quantity", "state"}.
 */
@Component
public class Tickets {

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public Tickets(JdbcClient jdbc, KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.mapper = mapper;
    }

    public Optional<Map<String, Object>> find(String processingId) {
        return jdbc.sql("SELECT id, processing_id, product, quantity, state FROM kitchen.tickets WHERE processing_id = ?")
                .param(processingId)
                .query((rs, row) -> {
                    Map<String, Object> ticket = new LinkedHashMap<>();
                    ticket.put("ticketId", rs.getInt("id"));
                    ticket.put("processingId", rs.getString("processing_id"));
                    ticket.put("product", rs.getString("product"));
                    ticket.put("quantity", rs.getInt("quantity"));
                    ticket.put("state", rs.getString("state"));
                    return ticket;
                })
                .optional();
    }

    public void publish(Map<String, Object> ticket) {
        try {
            kafka.send(KITCHEN_TOPIC, (String) ticket.get("processingId"), mapper.writeValueAsString(ticket));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
