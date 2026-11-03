package com.example.client;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaConfigTests {

    @Test
    void readsPropertiesSkippingCommentsAndKeepingEqualsInValues() {
        KafkaConfig config = new KafkaConfig();
        config.setProperties("""
                security.protocol=SASL_SSL
                # sasl.mechanism=PLAIN

                sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="u" password="a=b";
                """);

        assertEquals(Map.of(
                "security.protocol", "SASL_SSL",
                "sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"u\" password=\"a=b\";"),
                config.propertyMap());
    }

    @Test
    void refusesALineWithoutEquals() {
        KafkaConfig config = new KafkaConfig();
        config.setProperties("security.protocol SASL_SSL");

        assertThrows(IllegalArgumentException.class, config::propertyMap);
    }

    @Test
    void defaultsAreValid() {
        assertEquals(Map.of("security.protocol", "PLAINTEXT"), new KafkaConfig().propertyMap());
    }
}
