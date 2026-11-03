package com.example.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A saved session, as plain JSON, in three parts:
 * <pre>
 * {
 *   "tabs":    [ {"id": "h0", "kind": "http", "method": "POST", "url": "...", ...}, {"id": "k0", "kind": "kafka", ...} ],
 *   "configs": { "http": [...], "db": [...], "kafka": [...] },
 *   "links":   [ {"from": "h0", "to": "k0"}, {"from": "d0", "to": "q0", "branch": "false", "when": "status == 200"} ]
 * }
 * </pre>
 * Tabs are the cards (http, query, if, kafka) with everything typed in them; a card's "config" is an index into its
 * kind's configs. A link goes from one card to the next; "when" is its condition (left out when there is none) and
 * "branch" is set on the links out of an if diamond ("true" / "false"). The HTTP configs' bearer tokens are left out.
 */
public final class SessionFile {

    private SessionFile() {}

    /** The configs a session carries, saved with it and restored when it's opened. */
    public record SavedConfigs(List<HttpConfig> http, List<DatasourceConfig> db, List<KafkaConfig> kafka) {}

    /** What opening a session gives back. */
    public record Opened(QueryForm form, SavedConfigs configs) {}

    private enum Kind {
        HTTP("http", 'h'), QUERY("query", 'q'), IF("if", 'd'), KAFKA("kafka", 'k');

        final String name;
        final char prefix;

        Kind(String name, char prefix) { this.name = name; this.prefix = prefix; }

        static Kind of(String name) {
            for (Kind kind : values()) if (kind.name.equals(name)) return kind;
            throw new IllegalArgumentException("unknown tab kind \"" + name + "\" (http, query, if, kafka)");
        }
    }

    // The link fields of a card: they go to "links", not into its tab
    private static final List<String> LINK_FIELDS = List.of("next", "nextElse", "when");

    public static String write(ObjectMapper mapper, QueryForm form, SavedConfigs configs) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode tabs = root.putArray("tabs");
        ArrayNode links = mapper.createArrayNode();

        List<?>[] cards = { form.getRequests(), form.getBlocks(), form.getDecisions(), form.getSubscribers() };
        for (Kind kind : Kind.values()) {
            List<?> list = cards[kind.ordinal()];
            for (int i = 0; i < list.size(); i++) {
                String id = kind.prefix + String.valueOf(i);
                ObjectNode card = mapper.valueToTree(list.get(i));
                ObjectNode tab = tabs.addObject().put("id", id).put("kind", kind.name);
                card.properties().forEach(e -> { if (!LINK_FIELDS.contains(e.getKey())) tab.set(e.getKey(), e.getValue()); });

                Map<String, String> when = conditions(mapper, card.path("when").asText(""));
                addLinks(links, id, card.path("next").asText(""), kind == Kind.IF ? "true" : null, when);
                if (kind == Kind.IF) addLinks(links, id, card.path("nextElse").asText(""), "false", when);
            }
        }

        ObjectNode saved = root.putObject("configs");
        List<HttpConfig> http = configs.http().stream().map(c -> withoutToken(mapper, c)).toList();
        saved.set("http", mapper.valueToTree(http));
        saved.set("db", mapper.valueToTree(configs.db()));
        saved.set("kafka", mapper.valueToTree(configs.kafka()));

        root.set("links", links);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    public static Opened read(ObjectMapper mapper, String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        if (!root.has("tabs")) {
            // Saved before this format: the page form as it was posted
            return new Opened(mapper.treeToValue(root, QueryForm.class), null);
        }

        // The tabs, kind by kind in file order; their ids are renumbered from 0, links follow the new ids
        Map<Kind, List<ObjectNode>> byKind = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) byKind.put(kind, new ArrayList<>());
        Map<String, String> newIds = new LinkedHashMap<>();
        for (JsonNode tab : root.path("tabs")) {
            Kind kind = Kind.of(tab.path("kind").asText());
            List<ObjectNode> list = byKind.get(kind);
            newIds.put(tab.path("id").asText(), kind.prefix + String.valueOf(list.size()));
            ObjectNode card = ((ObjectNode) tab).deepCopy();
            card.remove(List.of("id", "kind"));
            LINK_FIELDS.forEach(card::remove);
            list.add(card);
        }

        // Each link back into its card's next / nextElse, and its condition into the card's when
        Map<String, List<String>> next = new LinkedHashMap<>(), nextElse = new LinkedHashMap<>();
        Map<String, ObjectNode> when = new LinkedHashMap<>();
        for (JsonNode link : root.path("links")) {
            String from = newIds.get(link.path("from").asText()), to = newIds.get(link.path("to").asText());
            if (from == null || to == null) continue;   // a link to a tab that isn't there
            ("false".equals(link.path("branch").asText()) ? nextElse : next).computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            String condition = link.path("when").asText("");
            if (!condition.isBlank()) when.computeIfAbsent(from, k -> mapper.createObjectNode()).put(to, condition);
        }
        for (Kind kind : Kind.values()) {
            List<ObjectNode> list = byKind.get(kind);
            for (int i = 0; i < list.size(); i++) {
                String id = kind.prefix + String.valueOf(i);
                list.get(i).put("next", String.join(",", next.getOrDefault(id, List.of())));
                if (kind == Kind.IF) list.get(i).put("nextElse", String.join(",", nextElse.getOrDefault(id, List.of())));
                list.get(i).put("when", when.containsKey(id) ? when.get(id).toString() : "");
            }
        }

        QueryForm form = new QueryForm();
        form.setRequests(cards(mapper, byKind.get(Kind.HTTP), QueryForm.Request.class));
        form.setBlocks(cards(mapper, byKind.get(Kind.QUERY), QueryForm.Block.class));
        form.setDecisions(cards(mapper, byKind.get(Kind.IF), QueryForm.Decision.class));
        form.setSubscribers(cards(mapper, byKind.get(Kind.KAFKA), QueryForm.Subscriber.class));
        // The page always has at least one HTTP and one query card
        if (form.getRequests().isEmpty()) form.getRequests().add(new QueryForm.Request());
        if (form.getBlocks().isEmpty()) form.getBlocks().add(new QueryForm.Block());

        JsonNode configs = root.path("configs");
        SavedConfigs saved = configs.isObject() ? new SavedConfigs(
                list(mapper, configs.path("http"), HttpConfig.class),
                list(mapper, configs.path("db"), DatasourceConfig.class),
                list(mapper, configs.path("kafka"), KafkaConfig.class)) : null;
        return new Opened(form, saved);
    }

    private static void addLinks(ArrayNode links, String from, String targets, String branch, Map<String, String> when) {
        for (String target : targets.split(",")) {
            String to = target.trim();
            if (to.isEmpty()) continue;
            ObjectNode link = links.addObject().put("from", from).put("to", to);
            if (branch != null) link.put("branch", branch);
            String condition = when.getOrDefault(to, "");
            if (!condition.isBlank()) link.put("when", condition);
        }
    }

    // A card's "when": {"q1": "status == 200"}
    private static Map<String, String> conditions(ObjectMapper mapper, String when) {
        Map<String, String> conditions = new LinkedHashMap<>();
        if (when.isBlank()) return conditions;
        try {
            mapper.readTree(when).properties().forEach(e -> conditions.put(e.getKey(), e.getValue().asText()));
        } catch (JsonProcessingException ignored) {
            // not JSON: no conditions
        }
        return conditions;
    }

    private static HttpConfig withoutToken(ObjectMapper mapper, HttpConfig config) {
        HttpConfig copy = mapper.convertValue(config, HttpConfig.class);
        copy.setJwt("");
        return copy;
    }

    private static <T> List<T> cards(ObjectMapper mapper, List<ObjectNode> nodes, Class<T> type) throws JsonProcessingException {
        List<T> cards = new ArrayList<>();
        for (ObjectNode node : nodes) cards.add(mapper.treeToValue(node, type));
        return cards;
    }

    private static <T> List<T> list(ObjectMapper mapper, JsonNode array, Class<T> type) throws JsonProcessingException {
        List<T> list = new ArrayList<>();
        if (array.isArray()) for (JsonNode item : array) list.add(mapper.treeToValue(item, type));
        return list;
    }
}
