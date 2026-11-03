package com.netcanvas;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * NetCanvas: a small graph of "tabs" (nodes) and "messages" (edges), each carrying free-form metadata.
 * Serves the page (src/main/resources/static/index.html) at "/" and a JSON API under "/api".
 * Data lives in memory; restarting clears it. Settings come from application.properties (netcanvas.*).
 *
 *   GET    /api/config           → {title, pollMs, types:[{name, color}], physics:{…}, island:{position, width, height, leaderLines}}
 *   GET    /api/graph            → {nodes, edges}
 *   DELETE /api/graph            clear everything
 *
 *   POST   /api/nodes            {label, type?, metadata?, x?, y?}   (no x/y → the page places it)
 *   GET    /api/nodes/{id}
 *   PATCH  /api/nodes/{id}       any of {label, type, metadata, x, y}   (metadata is replaced)
 *   DELETE /api/nodes/{id}       also removes its edges
 *
 *   POST   /api/edges            {from, to, label?, metadata?}   from/to = node id or label
 *   GET    /api/edges/{id}
 *   PATCH  /api/edges/{id}       any of {label, metadata}
 *   DELETE /api/edges/{id}
 *   DELETE /api/edges            clear all edges
 *
 *   GET    /api/{nodes|edges}/{id}/metadata
 *   PUT    /api/{nodes|edges}/{id}/metadata   {"name":"teo"}           replace the whole object
 *   PATCH  /api/{nodes|edges}/{id}/metadata   {"age":30,"name":null}   merge; null removes a key
 *
 *   POST   /api/edges/{id}/messages   <any JSON>                 append a message to a link
 *   POST   /api/messages              {from, to, request?, response?, id?} or {from, to, data}   by the two nodes;
 *                                     a request alone waits, a later response alone completes it (pairing)
 *   POST   /api/edges/{id}/exchange   {request?, response?, id?}   same pairing, by edge id
 *   GET    /api/edges/{id}/messages   → recent messages, oldest first (the last one is the latest)
 *   DELETE /api/edges/{id}/messages   clear the history
 *
 *   GET    /api/apidocs              → {"8085": <that service's /v3/api-docs>, …}   pasted on the page; scapy-minimal
 *                                     reads them when a capture starts and draws only the requests to those endpoints
 *   PUT    /api/apidocs/{port}       <a /v3/api-docs JSON>   the service on that port
 *   POST   /api/apidocs/fetch        {"url": "http://localhost:8085/v3/api-docs"} → that api-docs (not stored; localhost
 *                                     there means netcanvas.apidocs-host)
 *   DELETE /api/apidocs/{port}
 */
@SpringBootApplication
@RestController
@RequestMapping("/api")
@CrossOrigin
public class NetCanvasApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetCanvasApplication.class, args);
    }

    public static class Node {
        public String id, label, type;
        public Map<String, Object> metadata = new LinkedHashMap<>();
        public Integer x, y; // null until placed
    }

    public static class Edge {
        public String id, from, to, label = "";
        public Map<String, Object> metadata = new LinkedHashMap<>();
        public List<Message> messages = new ArrayList<>(); // oldest first, capped at netcanvas.message-history
    }

    /** Something sent over a link. data is any JSON (text, number, object…). */
    public static class Message {
        public String at;   // ISO-8601 timestamp, set by the server
        public Object data;
    }

    @Value("${netcanvas.title:NetCanvas}")
    private String title;

    /** Comma-separated "Name=#color" pairs; the first one is the default type. */
    @Value("${netcanvas.types:Default=#38bdf8,Service=#a78bfa,Data=#34d399,External=#f59e0b}")
    private List<String> types;

    @Value("${netcanvas.poll-ms:2000}")
    private int pollMs;

    // Where a tab's detail "island" opens (right | left | top | bottom | near) and its default size
    @Value("${netcanvas.island.position:right}")
    private String islandPosition;
    @Value("${netcanvas.island.width:340}")
    private int islandWidth;
    @Value("${netcanvas.island.height:420}")
    private int islandHeight;
    @Value("${netcanvas.island.leader-lines:true}")
    private boolean islandLeaders;

    /** How many messages each link keeps (older ones are dropped). */
    @Value("${netcanvas.message-history:20}")
    private int messageHistory;

    @Value("${netcanvas.physics.charge:-500}")
    private int charge;
    @Value("${netcanvas.physics.distance:200}")
    private int distance;
    @Value("${netcanvas.physics.collide:110}")
    private int collide;

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private int seq = 1;
    // The pasted /v3/api-docs, by the service's port. Not part of the graph: a reset keeps them for the next capture
    private final Map<String, Map<String, Object>> apiDocs = new LinkedHashMap<>();
    // Where "Add" with just a port fetches /v3/api-docs from
    @Value("${netcanvas.apidocs-host:localhost}")
    private String apiDocsHost;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @GetMapping("/config")
    public Map<String, Object> config() {
        List<Map<String, String>> list = types.stream().map(t -> {
            String[] kv = t.split("=", 2);
            return Map.of("name", kv[0].trim(), "color", kv.length > 1 ? kv[1].trim() : "#94a3b8");
        }).toList();
        return Map.of("title", title, "pollMs", pollMs, "types", list,
                "physics", Map.of("charge", charge, "distance", distance, "collide", collide),
                "island", Map.of("position", islandPosition, "width", islandWidth, "height", islandHeight, "leaderLines", islandLeaders));
    }

    @GetMapping("/graph")
    public synchronized Map<String, Object> graph() {
        return Map.of("nodes", new ArrayList<>(nodes.values()), "edges", new ArrayList<>(edges.values()));
    }

    @DeleteMapping("/graph")
    public synchronized void clearGraph() {
        nodes.clear();
        edges.clear();
    }

    // ── API docs: what a capture draws ──

    @GetMapping("/apidocs")
    public synchronized Map<String, Map<String, Object>> apiDocs() {
        return new LinkedHashMap<>(apiDocs);
    }

    @PutMapping("/apidocs/{port}")
    public synchronized Map<String, Object> putApiDocs(@PathVariable String port, @RequestBody Map<String, Object> docs) {
        if (!port.matches("\\d{1,5}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the port must be a number, got " + port);
        }
        if (!(docs.get("paths") instanceof Map)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not an OpenAPI description: it has no \"paths\"");
        }
        apiDocs.put(port, docs);
        return docs;
    }

    /**
     * Fetches an api-docs URL typed on the page, e.g. {"url": "http://localhost:8085/v3/api-docs"}, and returns it (the
     * page then stores it by port). Server side, so no CORS. "localhost" in it means netcanvas.apidocs-host, the host
     * when NetCanvas runs in a container.
     */
    @PostMapping("/apidocs/fetch")
    public Map<String, Object> fetchApiDocs(@RequestBody Map<String, String> body) {
        URI uri;
        try {
            uri = URI.create(Objects.requireNonNullElse(body.get("url"), "").trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a URL: " + body.get("url"));
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()) || uri.getHost() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not an http(s) URL: " + body.get("url"));
        }
        if (uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1")) {
            uri = URI.create(uri.toString().replaceFirst("//(localhost|127\\.0\\.0\\.1)", "//" + apiDocsHost));
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, uri + " answered " + response.statusCode());
            }
            Map<String, Object> docs = jsonMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            if (!(docs.get("paths") instanceof Map)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, uri + " isn't an OpenAPI description: no \"paths\"");
            }
            return docs;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "can't get " + uri + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted");
        }
    }

    @DeleteMapping("/apidocs/{port}")
    public synchronized void deleteApiDocs(@PathVariable String port) {
        apiDocs.remove(port);
    }

    // ── Nodes ──

    @PostMapping("/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized Node addNode(@RequestBody Node n) {
        n.id = "n" + seq++;
        if (n.type == null || n.type.isBlank()) n.type = types.get(0).split("=", 2)[0].trim();
        if (n.label == null || n.label.isBlank()) n.label = n.type + " " + n.id.substring(1);
        if (n.metadata == null) n.metadata = new LinkedHashMap<>();
        nodes.put(n.id, n);
        return n;
    }

    @GetMapping("/nodes/{id}")
    public synchronized Node getNode(@PathVariable String id) {
        return findNode(id);
    }

    @PatchMapping("/nodes/{id}")
    @SuppressWarnings("unchecked")
    public synchronized Node updateNode(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Node n = findNode(id);
        if (body.containsKey("label")) n.label = String.valueOf(body.get("label"));
        if (body.containsKey("type")) n.type = String.valueOf(body.get("type"));
        if (body.get("metadata") instanceof Map<?, ?> m) n.metadata = new LinkedHashMap<>((Map<String, Object>) m);
        if (body.containsKey("x")) n.x = body.get("x") instanceof Number x ? x.intValue() : null;
        if (body.containsKey("y")) n.y = body.get("y") instanceof Number y ? y.intValue() : null;
        return n;
    }

    @DeleteMapping("/nodes/{id}")
    public synchronized void deleteNode(@PathVariable String id) {
        Node n = findNode(id);
        nodes.remove(n.id);
        edges.values().removeIf(e -> e.from.equals(n.id) || e.to.equals(n.id));
    }

    // ── Edges ──

    @PostMapping("/edges")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized Edge addEdge(@RequestBody Edge e) {
        Node a = findNode(e.from), b = findNode(e.to);
        if (a == b) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot link a node to itself");
        boolean exists = edges.values().stream().anyMatch(x ->
                (x.from.equals(a.id) && x.to.equals(b.id)) || (x.from.equals(b.id) && x.to.equals(a.id)));
        if (exists) throw new ResponseStatusException(HttpStatus.CONFLICT, a.label + " and " + b.label + " are already linked");
        e.id = "e" + seq++;
        e.from = a.id;
        e.to = b.id;
        if (e.label == null) e.label = "";
        if (e.metadata == null) e.metadata = new LinkedHashMap<>();
        e.messages = new ArrayList<>();
        edges.put(e.id, e);
        return e;
    }

    @GetMapping("/edges/{id}")
    public synchronized Edge getEdge(@PathVariable String id) {
        return findEdge(id);
    }

    @PatchMapping("/edges/{id}")
    @SuppressWarnings("unchecked")
    public synchronized Edge updateEdge(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Edge e = findEdge(id);
        if (body.containsKey("label")) e.label = String.valueOf(body.get("label"));
        if (body.get("metadata") instanceof Map<?, ?> m) e.metadata = new LinkedHashMap<>((Map<String, Object>) m);
        return e;
    }

    @DeleteMapping("/edges")
    public synchronized void clearEdges() {
        edges.clear();
    }

    @DeleteMapping("/edges/{id}")
    public synchronized void deleteEdge(@PathVariable String id) {
        edges.remove(findEdge(id).id);
    }

    // ── Metadata (JSON object) for nodes and edges: GET, PUT = replace, PATCH = merge (null removes a key) ──

    @GetMapping("/nodes/{id}/metadata")
    public synchronized Map<String, Object> getNodeMeta(@PathVariable String id) {
        return findNode(id).metadata;
    }

    @PutMapping("/nodes/{id}/metadata")
    public synchronized Map<String, Object> putNodeMeta(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Node n = findNode(id);
        n.metadata = new LinkedHashMap<>(body);
        return n.metadata;
    }

    @PatchMapping("/nodes/{id}/metadata")
    public synchronized Map<String, Object> patchNodeMeta(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return merge(findNode(id).metadata, body);
    }

    @GetMapping("/edges/{id}/metadata")
    public synchronized Map<String, Object> getEdgeMeta(@PathVariable String id) {
        return findEdge(id).metadata;
    }

    @PutMapping("/edges/{id}/metadata")
    public synchronized Map<String, Object> putEdgeMeta(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Edge e = findEdge(id);
        e.metadata = new LinkedHashMap<>(body);
        return e.metadata;
    }

    @PatchMapping("/edges/{id}/metadata")
    public synchronized Map<String, Object> patchEdgeMeta(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return merge(findEdge(id).metadata, body);
    }

    // ── Messages on links ──

    @PostMapping("/edges/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized Message postMessage(@PathVariable String id, @RequestBody Object data) {
        return addMessage(findEdge(id), data);
    }

    /** For senders that know node names (e.g. ports), not edge ids. The link is found in either direction. */
    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized Message postMessageBetween(@RequestBody Map<String, Object> body) {
        Node a = findNode(String.valueOf(body.get("from"))), b = findNode(String.valueOf(body.get("to")));
        Edge e = edges.values().stream()
                .filter(x -> (x.from.equals(a.id) && x.to.equals(b.id)) || (x.from.equals(b.id) && x.to.equals(a.id)))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no link between " + a.label + " and " + b.label));
        return record(e, body);
    }

    /** Same request/response pairing as /api/messages, for senders that know the edge id. */
    @PostMapping("/edges/{id}/exchange")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized Message postExchange(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return record(findEdge(id), body);
    }

    /**
     * Captures arrive as separate events, so requests and responses are paired here:
     *  - {"request": …}             starts a new exchange that waits for its response
     *  - {"response": …}            completes the newest waiting exchange on this link
     *                               (or the one with the same "id", if an id is given)
     *  - {"request": …, "response": …}  a complete exchange in one go
     *  - {"data": …}                any other message, stored as-is
     */
    @SuppressWarnings("unchecked")
    private Message record(Edge e, Map<String, Object> body) {
        if (body.containsKey("data")) return addMessage(e, body.get("data"));
        boolean hasReq = body.containsKey("request"), hasRes = body.containsKey("response");
        Object cid = body.get("id");

        if (hasRes && !hasReq) {
            for (int i = e.messages.size() - 1; i >= 0; i--) {
                Message m = e.messages.get(i);
                if (m.data instanceof Map<?, ?> d && d.containsKey("request") && !d.containsKey("response")
                        && (cid == null || Objects.equals(cid, d.get("id")))) {
                    ((Map<String, Object>) d).put("response", body.get("response"));
                    m.at = Instant.now().toString();        // latest activity → it becomes "the latest"
                    e.messages.remove(i);
                    e.messages.add(m);
                    return m;
                }
            }
        }
        Map<String, Object> rr = new LinkedHashMap<>();
        if (cid != null) rr.put("id", cid);
        if (hasReq) rr.put("request", body.get("request"));
        if (hasRes) rr.put("response", body.get("response"));   // a response with no waiting request is kept on its own
        return addMessage(e, rr);
    }

    @GetMapping("/edges/{id}/messages")
    public synchronized List<Message> getMessages(@PathVariable String id) {
        return findEdge(id).messages;
    }

    @DeleteMapping("/edges/{id}/messages")
    public synchronized void clearMessages(@PathVariable String id) {
        findEdge(id).messages.clear();
    }

    private Message addMessage(Edge e, Object data) {
        Message m = new Message();
        m.at = Instant.now().toString();
        m.data = data;
        e.messages.add(m);
        while (e.messages.size() > messageHistory) e.messages.remove(0);
        return m;
    }

    private static Map<String, Object> merge(Map<String, Object> target, Map<String, Object> changes) {
        changes.forEach((k, v) -> {
            if (v == null) target.remove(k);
            else target.put(k, v);
        });
        return target;
    }

    /** Look up by id first, then by label (case-insensitive) so curl users can write {"from":"Auth"}. */
    private Node findNode(String ref) {
        if (ref != null && nodes.containsKey(ref)) return nodes.get(ref);
        return nodes.values().stream().filter(n -> n.label.equalsIgnoreCase(ref)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "node " + ref + " not found"));
    }

    private Edge findEdge(String id) {
        Edge e = edges.get(id);
        if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "edge " + id + " not found");
        return e;
    }
}
