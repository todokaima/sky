package com.example.service1;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.example.service1.Responses.error;

/**
 * The products in shop.products, {"id", "name", "price"}. service2 uses both for its menu:
 * GET /products for GET /menu, and PUT /products/{name} {"price": 3.80} for PUT /menu/{product}.
 */
@RestController
@RequestMapping("/products")
public class ProductController {

    record PriceRequest(BigDecimal price) {}

    private static final RowMapper<Map<String, Object>> PRODUCT = (rs, row) -> {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", rs.getInt("id"));
        product.put("name", rs.getString("name"));
        product.put("price", rs.getBigDecimal("price"));
        return product;
    };

    private final JdbcClient jdbc;

    public ProductController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.sql("SELECT id, name, price FROM shop.products ORDER BY id").query(PRODUCT).list();
    }

    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updatePrice(@PathVariable String name, @RequestBody PriceRequest request) {
        if (request.price() == null || request.price().signum() < 0) {
            return error(HttpStatus.BAD_REQUEST, "price is required and not negative, e.g. {\"price\": 3.80}");
        }
        Optional<Map<String, Object>> product = jdbc.sql("UPDATE shop.products SET price = ? WHERE name = ? RETURNING id, name, price")
                .params(request.price(), name).query(PRODUCT).optional();
        if (product.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "no product " + name + ", there are " + list().stream().map(p -> p.get("name")).toList());
        }
        return ResponseEntity.ok(product.get());
    }
}
