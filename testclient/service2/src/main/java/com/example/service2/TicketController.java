package com.example.service2;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

import static com.example.service2.Responses.error;

/**
 * The tickets, one per service1 order. service1 calls all three:
 * <ul>
 *   <li>POST /tickets {"processingId", "product", "quantity"} when an order is made. It's stored as cooking and sent
 *       to kitchen.events; after the product's prep_seconds (kitchen.recipes, 2 if it has none) it's ready and sent
 *       again, which is what makes service1 mark the order done.</li>
 *   <li>GET /tickets/{processingId} for service1's GET /orders/{processingId}.</li>
 *   <li>PUT /tickets/{processingId} {"quantity": 3} for service1's PUT /orders/{processingId}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/tickets")
public class TicketController {

    static final int DEFAULT_PREP_SECONDS = 2;

    record TicketRequest(String processingId, String product, Integer quantity) {}

    record QuantityRequest(Integer quantity) {}

    private final JdbcClient jdbc;
    private final Tickets tickets;

    public TicketController(JdbcClient jdbc, Tickets tickets) {
        this.jdbc = jdbc;
        this.tickets = tickets;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody TicketRequest request) {
        if (request.processingId() == null || request.processingId().isBlank()
                || request.product() == null || request.product().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "processingId and product are required, e.g. {\"processingId\": \"abc-1\", \"product\": \"Coffee\", \"quantity\": 2}");
        }
        int quantity = request.quantity() == null ? 1 : request.quantity();
        if (quantity < 1) {
            return error(HttpStatus.BAD_REQUEST, "quantity must be at least 1");
        }
        String processingId = request.processingId().trim();
        if (tickets.find(processingId).isPresent()) {
            return error(HttpStatus.CONFLICT, "there's already a ticket for " + processingId);
        }
        String product = request.product().trim();
        int prepSeconds = jdbc.sql("SELECT prep_seconds FROM kitchen.recipes WHERE product = ?")
                .param(product).query(Integer.class).optional().orElse(DEFAULT_PREP_SECONDS);

        int ticketId = jdbc.sql("INSERT INTO kitchen.tickets (processing_id, product, quantity, state) VALUES (?, ?, ?, 'cooking') RETURNING id")
                .params(processingId, product, quantity).query(Integer.class).single();
        Map<String, Object> ticket = tickets.find(processingId).orElseThrow();
        tickets.publish(ticket);
        CompletableFuture.runAsync(() -> ready(ticketId, processingId), CompletableFuture.delayedExecutor(prepSeconds, TimeUnit.SECONDS));

        Map<String, Object> body = new LinkedHashMap<>(ticket);
        body.put("readyInSeconds", prepSeconds);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private void ready(int ticketId, String processingId) {
        jdbc.sql("UPDATE kitchen.tickets SET state = 'ready', ready_at = now() WHERE id = ? AND state = 'cooking'").param(ticketId).update();
        tickets.find(processingId).ifPresent(tickets::publish);
    }

    @GetMapping("/{processingId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String processingId) {
        return tickets.find(processingId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "no ticket for " + processingId));
    }

    @PutMapping("/{processingId}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String processingId, @RequestBody QuantityRequest request) {
        if (request.quantity() == null || request.quantity() < 1) {
            return error(HttpStatus.BAD_REQUEST, "quantity is required and at least 1, e.g. {\"quantity\": 3}");
        }
        int updated = jdbc.sql("UPDATE kitchen.tickets SET quantity = ? WHERE processing_id = ?")
                .params(request.quantity(), processingId).update();
        if (updated == 0) {
            return error(HttpStatus.NOT_FOUND, "no ticket for " + processingId);
        }
        Optional<Map<String, Object>> ticket = tickets.find(processingId);
        return ResponseEntity.ok(ticket.orElseThrow());
    }
}
