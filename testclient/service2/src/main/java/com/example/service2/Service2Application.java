package com.example.service2;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.web.client.RestClient;

/**
 * service2, the kitchen: tickets and recipes. Talks to service1 (the shop) over HTTP (its /orders and /products)
 * and Kafka (sends kitchen.events, reads orders.events).
 */
@SpringBootApplication
public class Service2Application {

    /** What happens to tickets, sent by this service (read by service1) */
    public static final String KITCHEN_TOPIC = "kitchen.events";
    /** What happens to orders, sent by service1 (read here) */
    public static final String ORDERS_TOPIC = "orders.events";

    public static void main(String[] args) {
        SpringApplication.run(Service2Application.class, args);
    }

    // Both topics are created on startup if they aren't there yet, by whichever service starts first
    @Bean
    NewTopic kitchenEvents() {
        return TopicBuilder.name(KITCHEN_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic ordersEvents() {
        return TopicBuilder.name(ORDERS_TOPIC).partitions(3).replicas(1).build();
    }

    // For service1's endpoints
    @Bean
    RestClient shop(RestClient.Builder builder, @Value("${shop.url}") String url) {
        return builder.baseUrl(url).build();
    }

    // Keeps the last requests for /actuator/httpexchanges (off unless there's a repository)
    @Bean
    HttpExchangeRepository httpExchanges() {
        InMemoryHttpExchangeRepository repository = new InMemoryHttpExchangeRepository();
        repository.setCapacity(300);
        return repository;
    }
}
