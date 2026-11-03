package com.example.client;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionTests {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode http(int status, String body) throws Exception {
        ObjectNode prev = mapper.createObjectNode();
        prev.put("ok", status < 400);
        prev.put("status", status);
        prev.put("body", body);
        prev.set("json", mapper.readTree(body));
        return prev;
    }

    @Test
    void checksStatusAndJsonPaths() throws Exception {
        ObjectNode prev = http(200, "{\"data\": {\"state\": \"done\", \"items\": [{\"id\": 7}, {\"id\": 8}]}}");
        Map<String, String> vars = Map.of("token", "eyJabc");

        assertTrue(Condition.test("status == 200 && data.state == \"done\"", prev, vars));
        assertTrue(Condition.test("data.items.length > 1 and data.items[1].id == 8", prev, vars));
        assertTrue(Condition.test("exists data.items[0].id && not exists data.missing", prev, vars));
        assertTrue(Condition.test("body contains 'done' || status >= 500", prev, vars));
        assertTrue(Condition.test("vars.token matches \"^ey\" && token == 'eyJabc'", prev, vars));
        assertTrue(Condition.test("!(status != 200)", prev, vars));
        assertTrue(Condition.test("", prev, vars));

        assertFalse(Condition.test("status == 404", prev, vars));
        assertFalse(Condition.test("data.state == 'pending' or data.items.length == 0", prev, vars));
    }

    @Test
    void worksWithoutAPreviousResult() {
        assertFalse(Condition.test("status == 200", null, Map.of()));
        assertTrue(Condition.test("not ok", null, Map.of()));
    }

    @Test
    void rejectsWhatItCantRead() throws Exception {
        ObjectNode prev = http(200, "{}");
        assertThrows(IllegalArgumentException.class, () -> Condition.test("status ==", prev, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Condition.test("status = 200", prev, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Condition.test("(status == 200", prev, Map.of()));
    }
}
