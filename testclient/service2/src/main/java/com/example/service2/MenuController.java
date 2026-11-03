package com.example.service2;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static com.example.service2.Responses.error;

/**
 * What the kitchen asks of service1:
 * <ul>
 *   <li>GET /menu is service1's GET /products with each product's prepSeconds from kitchen.recipes.</li>
 *   <li>PUT /menu/{product} {"price": 3.80, "prepSeconds": 4} (either or both) PUTs the price to service1's
 *       /products/{product} and keeps the prep time here.</li>
 *   <li>POST /staff-meals {"product": "Cake", "quantity": 1} orders through service1's POST /orders with
 *       "placedBy": "kitchen", so service1 then POSTs the ticket back here like any order.</li>
 * </ul>
 * When service1 doesn't answer they're a 502.
 */
@RestController
public class MenuController {

    record MenuRequest(BigDecimal price, Integer prepSeconds) {}

    record StaffMealRequest(String product, Integer quantity) {}

    private final JdbcClient jdbc;
    private final RestClient shop;

    public MenuController(JdbcClient jdbc, RestClient shop) {
        this.jdbc = jdbc;
        this.shop = shop;
    }

    @GetMapping("/menu")
    public ResponseEntity<?> menu() {
        try {
            return ResponseEntity.ok(fetchMenu());
        } catch (RestClientException e) {
            return error(HttpStatus.BAD_GATEWAY, "service1 didn't give the products: " + e.getMessage());
        }
    }

    @PutMapping("/menu/{product}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String product, @RequestBody MenuRequest request) {
        if (request.price() == null && request.prepSeconds() == null) {
            return error(HttpStatus.BAD_REQUEST, "price, prepSeconds or both, e.g. {\"price\": 3.80, \"prepSeconds\": 4}");
        }
        if (request.prepSeconds() != null && request.prepSeconds() < 0) {
            return error(HttpStatus.BAD_REQUEST, "prepSeconds can't be negative");
        }
        try {
            List<Map<String, Object>> menu = fetchMenu();
            if (find(menu, product).isEmpty()) {
                return error(HttpStatus.NOT_FOUND, "no product " + product + ", there are " + menu.stream().map(item -> item.get("name")).toList());
            }
            if (request.price() != null) {
                // service1 checks the price; its 400 comes back as a 502 with service1's message in it
                shop.put().uri("/products/{name}", product).body(Map.of("price", request.price())).retrieve().toBodilessEntity();
            }
            if (request.prepSeconds() != null) {
                jdbc.sql("INSERT INTO kitchen.recipes (product, prep_seconds) VALUES (?, ?) ON CONFLICT (product) DO UPDATE SET prep_seconds = EXCLUDED.prep_seconds")
                        .params(product, request.prepSeconds()).update();
            }
            return ResponseEntity.ok(find(fetchMenu(), product).orElseThrow());
        } catch (RestClientException e) {
            return error(HttpStatus.BAD_GATEWAY, "service1 didn't change the product: " + e.getMessage());
        }
    }

    @PostMapping("/staff-meals")
    public ResponseEntity<?> staffMeal(@RequestBody StaffMealRequest request) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("product", request.product());
        order.put("quantity", request.quantity());
        order.put("placedBy", "kitchen");
        try {
            // service1's answer as it is, errors too
            return shop.post().uri("/orders").body(order)
                    .exchange((req, res) -> ResponseEntity.status(res.getStatusCode()).body(res.bodyTo(JsonNode.class)));
        } catch (RestClientException e) {
            return error(HttpStatus.BAD_GATEWAY, "service1 didn't take the order: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> fetchMenu() {
        JsonNode products = shop.get().uri("/products").retrieve().body(JsonNode.class);
        Map<String, Integer> prepSeconds = jdbc.sql("SELECT product, prep_seconds FROM kitchen.recipes")
                .query((rs, row) -> Map.entry(rs.getString("product"), rs.getInt("prep_seconds"))).list()
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<Map<String, Object>> menu = new ArrayList<>();
        for (JsonNode product : products) {
            String name = product.path("name").asText();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("price", product.path("price").decimalValue());
            item.put("prepSeconds", prepSeconds.getOrDefault(name, TicketController.DEFAULT_PREP_SECONDS));
            menu.add(item);
        }
        return menu;
    }

    private static Optional<Map<String, Object>> find(List<Map<String, Object>> menu, String product) {
        return menu.stream().filter(item -> product.equals(item.get("name"))).findFirst();
    }
}
