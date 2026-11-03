package com.example.service1;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import static com.example.service1.Service1Application.ORDERS_TOPIC;

/**
 * Reads orders from shop.orders and sends them to orders.events. An order (what the endpoints return) is
 * {"processingId", "orderId", "ticketId", "product", "quantity", "placedBy", "state"}; on the topic it has an
 * "event" in front (created, updated or done) and is keyed by processingId.
 */
@Component
public class Orders {

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public Orders(JdbcClient jdbc, KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.mapper = mapper;
    }

    public Optional<Map<String, Object>> find(String processingId) {
        return jdbc.sql("""
                        SELECT o.processing_id, o.id, o.ticket_id, p.name, o.quantity, o.placed_by, o.state
                        FROM shop.orders o JOIN shop.products p ON p.id = o.product_id
                        WHERE o.processing_id = ?""")
                .param(processingId)
                .query((rs, row) -> {
                    Map<String, Object> order = new LinkedHashMap<>();
                    order.put("processingId", rs.getString("processing_id"));
                    order.put("orderId", rs.getInt("id"));
                    order.put("ticketId", rs.getObject("ticket_id"));
                    order.put("product", rs.getString("name"));
                    order.put("quantity", rs.getInt("quantity"));
                    order.put("placedBy", rs.getString("placed_by"));
                    order.put("state", rs.getString("state"));
                    return order;
                })
                .optional();
    }

    public void publish(String event, Map<String, Object> order) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("event", event);
        message.putAll(order);
        try {
            kafka.send(ORDERS_TOPIC, (String) order.get("processingId"), mapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
