package com.example.client;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryTemplateTests {

    @Test
    void expandsAnHttpExtractorValue() {
        List<QueryTemplate.Run> runs = QueryTemplate.expand(
                List.of(Map.of("processingId", "ed017a2b-297b-4b29-bd69-9952cae545a6")),
                "SELECT * FROM form WHERE form_id = '$(processingId)'");

        assertEquals("SELECT * FROM form WHERE form_id = 'ed017a2b-297b-4b29-bd69-9952cae545a6'", runs.getFirst().sql());
    }

    @Test
    void fillsAKafkaTopicFromVariables() {
        assertEquals("orders.acme.events", QueryTemplate.fill("orders.$(tenant).events", Map.of("tenant", "acme")));
    }

    @Test
    void refusesAnUnknownVariableInATopic() {
        assertThrows(IllegalArgumentException.class, () -> QueryTemplate.fill("orders.$(tenant)", Map.of()));
    }
}
