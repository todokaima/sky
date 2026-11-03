package com.example.service2;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Background traffic, so there's always something to sniff and draw:
 *   every chatter.products-every (15 s)  GET service1's /products, the kitchen checking the prices
 *   every chatter.meal-every (45 s)      a staff meal: POST service1's /orders with "placedBy": "kitchen", which
 *                                        POSTs the ticket back here, cooks it and sends the Kafka events, like any order
 * The meals take turns: Coffee, Tea, Cake. Off with CHATTER_ENABLED=false.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "chatter.enabled", havingValue = "true", matchIfMissing = true)
public class Chatter {

    private static final Logger log = LoggerFactory.getLogger(Chatter.class);
    private static final List<String> MEALS = List.of("Coffee", "Tea", "Cake");

    private final RestClient shop;
    private final AtomicInteger meals = new AtomicInteger();

    public Chatter(RestClient shop) {
        this.shop = shop;
    }

    @Scheduled(initialDelayString = "${chatter.initial-delay:PT20S}", fixedDelayString = "${chatter.products-every:PT15S}")
    void checkThePrices() {
        try {
            shop.get().uri("/products").retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.debug("chatter: service1's /products didn't answer: {}", e.getMessage());   // it may not be up yet
        }
    }

    @Scheduled(initialDelayString = "${chatter.initial-delay:PT20S}", fixedDelayString = "${chatter.meal-every:PT45S}")
    void orderAStaffMeal() {
        String product = MEALS.get(meals.getAndIncrement() % MEALS.size());
        try {
            shop.post().uri("/orders").body(Map.of("product", product, "quantity", 1, "placedBy", "kitchen"))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.debug("chatter: the staff meal ({}) wasn't ordered: {}", product, e.getMessage());
        }
    }
}
