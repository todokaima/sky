package com.example.service1;

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
 * service1, the shop: orders and products. Talks to service2 (the kitchen) over HTTP (its /tickets) and Kafka
 * (sends orders.events, reads kitchen.events).
 */
@SpringBootApplication
public class Service1Application {

    /** What happens to orders, sent by this service (read by service2) */
    public static final String ORDERS_TOPIC = "orders.events";
    /** What happens to tickets, sent by service2 (read here) */
    public static final String KITCHEN_TOPIC = "kitchen.events";

    public static void main(String[] args) {
        SpringApplication.run(Service1Application.class, args);
    }

    // Both topics are created on startup if they aren't there yet, by whichever service starts first
    @Bean
    NewTopic ordersEvents() {
        return TopicBuilder.name(ORDERS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic kitchenEvents() {
        return TopicBuilder.name(KITCHEN_TOPIC).partitions(3).replicas(1).build();
    }

    // For service2's endpoints
    @Bean
    RestClient kitchen(RestClient.Builder builder, @Value("${kitchen.url}") String url) {
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
