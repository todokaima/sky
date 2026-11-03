package com.example.client;

import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionFileTests {

    // As Spring Boot configures it
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private QueryForm chain() {
        QueryForm form = new QueryForm();
        QueryForm.Request post = form.getRequests().get(0);
        post.setMethod("POST");
        post.setUrl("http://localhost:8085/orders");
        post.setNext("d0");
        post.setWhen("{\"d0\": \"status == 201\"}");
        post.setConfig(0);
        QueryForm.Decision decision = new QueryForm.Decision();
        decision.setCondition("state == \"done\"");
        decision.setNext("q0");
        decision.setNextElse("h1");
        form.getDecisions().add(decision);
        form.getRequests().add(new QueryForm.Request());
        form.getBlocks().get(0).setQuery("SELECT 1;");
        return form;
    }

    private SessionFile.SavedConfigs configs() {
        HttpConfig http = new HttpConfig();
        http.setJwt("abc.def.ghi");
        http.setHeaders("X-Tenant: shop");
        return new SessionFile.SavedConfigs(List.of(http), List.of(new DatasourceConfig()), List.of(new KafkaConfig()));
    }

    @Test
    void writesTabsThenConfigsThenLinks() throws Exception {
        JsonNode root = mapper.readTree(SessionFile.write(mapper, chain(), configs()));

        List<String> parts = new java.util.ArrayList<>();
        root.fieldNames().forEachRemaining(parts::add);
        assertEquals(List.of("tabs", "configs", "links"), parts);
        assertEquals("h0", root.path("tabs").get(0).path("id").asText());
        assertEquals("http", root.path("tabs").get(0).path("kind").asText());
        assertTrue(root.path("tabs").get(0).path("next").isMissingNode(), "links aren't repeated in the tabs");
        assertEquals("", root.path("configs").path("http").get(0).path("jwt").asText(), "no bearer tokens in the file");
        assertEquals(mapper.readTree("""
                [ {"from": "h0", "to": "d0", "when": "status == 201"},
                  {"from": "d0", "to": "q0", "branch": "true"},
                  {"from": "d0", "to": "h1", "branch": "false"} ]"""), root.path("links"));
    }

    @Test
    void readsBackWhatItWrote() throws Exception {
        SessionFile.Opened opened = SessionFile.read(mapper, SessionFile.write(mapper, chain(), configs()));
        QueryForm form = opened.form();

        assertEquals(2, form.getRequests().size());
        assertEquals("POST", form.getRequests().get(0).getMethod());
        assertEquals("d0", form.getRequests().get(0).getNext());
        assertEquals("status == 201", mapper.readTree(form.getRequests().get(0).getWhen()).path("d0").asText());
        assertEquals(0, form.getRequests().get(0).getConfig());
        assertEquals("q0", form.getDecisions().get(0).getNext());
        assertEquals("h1", form.getDecisions().get(0).getNextElse());
        assertEquals("SELECT 1;", form.getBlocks().get(0).getQuery());
        assertEquals("X-Tenant: shop", opened.configs().http().get(0).getHeaders());
    }

    @Test
    void opensASessionSavedInTheOldFormat() throws Exception {
        String old = mapper.writeValueAsString(chain());

        SessionFile.Opened opened = SessionFile.read(mapper, old);

        assertEquals("d0", opened.form().getRequests().get(0).getNext());
        assertEquals(null, opened.configs());
    }
}
