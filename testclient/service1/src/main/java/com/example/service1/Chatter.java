package com.example.service1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Background traffic, so there's always something to sniff and draw: every chatter.menu-every (20 s) the shop asks
 * the kitchen for its GET /menu, which the kitchen answers by calling this service's GET /products back.
 * Off with CHATTER_ENABLED=false.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "chatter.enabled", havingValue = "true", matchIfMissing = true)
public class Chatter {

    private static final Logger log = LoggerFactory.getLogger(Chatter.class);

    private final RestClient kitchen;

    public Chatter(RestClient kitchen) {
        this.kitchen = kitchen;
    }

    @Scheduled(initialDelayString = "${chatter.initial-delay:PT20S}", fixedDelayString = "${chatter.menu-every:PT20S}")
    void askForTheMenu() {
        try {
            kitchen.get().uri("/menu").retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.debug("chatter: service2's /menu didn't answer: {}", e.getMessage());   // it may not be up yet
        }
    }
}
