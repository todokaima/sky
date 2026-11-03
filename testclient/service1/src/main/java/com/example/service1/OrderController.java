package com.example.service1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static com.example.service1.Responses.error;

/**
 * The orders. Each one has a ticket in service2's kitchen:
 * <ul>
 *   <li>POST /orders {"product": "Coffee", "quantity": 2} stores it as created, POSTs service2's /tickets and
 *       sends "created" to orders.events. service2 also calls it, for staff meals ("placedBy": "kitchen").
 *       When the kitchen says the ticket is ready (kitchen.events), {@link KitchenEventsListener} marks it done.</li>
 *   <li>GET /orders/{processingId} is the order with its ticket, from service2's GET /tickets/{processingId}.</li>
 *   <li>PUT /orders/{processingId} {"quantity": 3} PUTs service2's /tickets/{processingId} first, then changes
 *       it here and sends "updated" to orders.events.</li>
 * </ul>
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    record OrderRequest(String product, Integer quantity, String placedBy) {}

    record QuantityRequest(Integer quantity) {}

    private final JdbcClient jdbc;
    private final Orders orders;
    private final RestClient kitchen;

    public OrderController(JdbcClient jdbc, Orders orders, RestClient kitchen) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.kitchen = kitchen;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody OrderRequest request) {
        if (request.product() == null || request.product().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "product is required, e.g. {\"product\": \"Coffee\", \"quantity\": 2}");
        }
        int quantity = request.quantity() == null ? 1 : request.quantity();
        if (quantity < 1) {
            return error(HttpStatus.BAD_REQUEST, "quantity must be at least 1");
        }
        String product = request.product().trim();
        Optional<Integer> productId = jdbc.sql("SELECT id FROM shop.products WHERE name = ?")
                .param(product).query(Integer.class).optional();
        if (productId.isEmpty()) {
            List<String> products = jdbc.sql("SELECT name FROM shop.products ORDER BY id").query(String.class).list();
            return error(HttpStatus.NOT_FOUND, "no product " + product + ", there are " + products);
        }
        String placedBy = request.placedBy() == null || request.placedBy().isBlank() ? "customer" : request.placedBy().trim();

        String processingId = UUID.randomUUID().toString();
        int orderId = jdbc.sql("INSERT INTO shop.orders (processing_id, product_id, quantity, placed_by, state) VALUES (?, ?, ?, ?, 'created') RETURNING id")
                .params(processingId, productId.get(), quantity, placedBy).query(Integer.class).single();
        JsonNode ticket;
        try {
            ticket = kitchen.post().uri("/tickets")
                    .body(Map.of("processingId", processingId, "product", product, "quantity", quantity))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            jdbc.sql("UPDATE shop.orders SET state = 'failed' WHERE id = ?").param(orderId).update();
            return error(HttpStatus.BAD_GATEWAY, "order " + orderId + " failed, service2 didn't take the ticket: " + e.getMessage());
        }
        jdbc.sql("UPDATE shop.orders SET ticket_id = ? WHERE id = ?").params(ticket.path("ticketId").asInt(), orderId).update();

        Map<String, Object> order = orders.find(processingId).orElseThrow();
        orders.publish("created", order);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/{processingId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String processingId) {
        Optional<Map<String, Object>> found = orders.find(processingId);
        if (found.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "no order " + processingId);
        }
        Map<String, Object> order = new LinkedHashMap<>(found.get());
        try {
            order.put("ticket", kitchen.get().uri("/tickets/{processingId}", processingId)
                    .exchange((req, res) -> res.getStatusCode().is2xxSuccessful() ? res.bodyTo(JsonNode.class) : null));
        } catch (RestClientException e) {
            order.put("ticket", Map.of("error", "service2 didn't answer: " + e.getMessage()));
        }
        return ResponseEntity.ok(order);
    }

    @PutMapping("/{processingId}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String processingId, @RequestBody QuantityRequest request) {
        if (request.quantity() == null || request.quantity() < 1) {
            return error(HttpStatus.BAD_REQUEST, "quantity is required and at least 1, e.g. {\"quantity\": 3}");
        }
        if (orders.find(processingId).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "no order " + processingId);
        }
        try {
            kitchen.put().uri("/tickets/{processingId}", processingId)
                    .body(Map.of("quantity", request.quantity()))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            return error(HttpStatus.BAD_GATEWAY, "service2 didn't change the ticket, so the order stays as it was: " + e.getMessage());
        }
        jdbc.sql("UPDATE shop.orders SET quantity = ? WHERE processing_id = ?").params(request.quantity(), processingId).update();

        Map<String, Object> order = orders.find(processingId).orElseThrow();
        orders.publish("updated", order);
        return ResponseEntity.ok(order);
    }
}
