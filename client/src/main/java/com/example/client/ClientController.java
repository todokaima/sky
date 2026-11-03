package com.example.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ClientController {

    // Saved config form values, kept in the data dir (DATA_DIR, the volume when containerized)
    private final Path configFile;
    private final Path httpConfigFile;
    private final Path kafkaConfigFile;
    // "Save page": all cards, their python, links and outputs, loaded when a new session starts
    private final Path workspaceFile;
    // Named sessions: one file per name, each opened in its own tab
    private final Path sessionsDir;
    // The main page form lives in the session, so it survives reloads and config saves. Each tab works
    // on its own copy, picked by the ?ws= id in its url ("default" when there's none).
    private static final String FORM = "form";
    private static final String EXTRACTED_VARIABLES = "extractedVariables";
    private static final String SESSION_NAME = "sessionName";
    private static final String DEFAULT_WS = "default";
    private static final Pattern WS = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern NAME = Pattern.compile("\\w[\\w .-]{0,59}");
    private static final DateTimeFormatter SAVED_AT = DateTimeFormatter.ofPattern("d MMM, HH:mm");

    private final ObjectMapper mapper;
    private final QueryRunner queryRunner;
    private final HttpRunner httpRunner;
    private final KafkaRunner kafkaRunner;
    private final CaptureTrigger captureTrigger;

    public ClientController(ObjectMapper mapper, QueryRunner queryRunner, HttpRunner httpRunner, KafkaRunner kafkaRunner,
                            CaptureTrigger captureTrigger, @Value("${client.data-dir}") Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.configFile = dataDir.resolve("datasource-config.json");
        this.httpConfigFile = dataDir.resolve("http-config.json");
        this.kafkaConfigFile = dataDir.resolve("kafka-config.json");
        this.workspaceFile = dataDir.resolve("workspace.json");
        this.sessionsDir = Files.createDirectories(dataDir.resolve("sessions"));
        this.mapper = mapper;
        this.queryRunner = queryRunner;
        this.httpRunner = httpRunner;
        this.kafkaRunner = kafkaRunner;
        this.captureTrigger = captureTrigger;
    }

    // The config forms are always filled from the saved files, so they only change on (re)load
    @GetMapping("/client")
    public String page(@RequestParam(defaultValue = DEFAULT_WS) String ws, Model model, HttpSession session) throws IOException {
        ws = ws(ws);
        model.addAttribute("configs", loadConfigs());
        model.addAttribute("httpConfigs", loadHttpConfigs());
        model.addAttribute("kafkaConfigs", loadKafkaConfigs());
        Object form = session.getAttribute(key(FORM, ws));
        model.addAttribute(FORM, form != null ? form : loadWorkspace());
        model.addAttribute("ws", ws);
        model.addAttribute(SESSION_NAME, session.getAttribute(key(SESSION_NAME, ws)));
        model.addAttribute("sessions", savedSessions());
        return "client";
    }

    @PostMapping("/client/config")
    public String saveConfigs(@ModelAttribute Configs.Datasource posted, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                              RedirectAttributes redirect) throws IOException {
        List<DatasourceConfig> configs = Configs.normalize(posted.getConfigs(),
                DatasourceConfig::new, DatasourceConfig::isDefaultConfig, DatasourceConfig::setDefaultConfig);
        mapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), configs);
        redirect.addFlashAttribute("toast", "DB configs saved to " + configFile.toAbsolutePath());
        return redirectTo(ws, "");
    }

    @PostMapping("/client/config/reset")
    public String resetConfigs(@RequestParam(defaultValue = DEFAULT_WS) String ws, RedirectAttributes redirect) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), List.of(new DatasourceConfig()));
        redirect.addFlashAttribute("toast", "DB config reset to local irm_db defaults");
        return redirectTo(ws, "");
    }

    @PostMapping("/client/http-config")
    public String saveHttpConfigs(@ModelAttribute Configs.Http posted, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                                  RedirectAttributes redirect) throws IOException {
        List<HttpConfig> configs = Configs.normalize(posted.getConfigs(),
                HttpConfig::new, HttpConfig::isDefaultConfig, HttpConfig::setDefaultConfig);
        mapper.writerWithDefaultPrettyPrinter().writeValue(httpConfigFile.toFile(), configs);
        redirect.addFlashAttribute("toast", "HTTP configs saved to " + httpConfigFile.toAbsolutePath());
        return redirectTo(ws, "");
    }

    @PostMapping("/client/kafka-config")
    public String saveKafkaConfigs(@ModelAttribute Configs.Kafka posted, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                                   RedirectAttributes redirect) throws IOException {
        List<KafkaConfig> configs = Configs.normalize(posted.getConfigs(),
                KafkaConfig::new, KafkaConfig::isDefaultConfig, KafkaConfig::setDefaultConfig);
        mapper.writerWithDefaultPrettyPrinter().writeValue(kafkaConfigFile.toFile(), configs);
        redirect.addFlashAttribute("toast", "Kafka configs saved to " + kafkaConfigFile.toAbsolutePath());
        return redirectTo(ws, "");
    }

    // "Save page": write the whole page (cards, python, links, outputs) to a file
    @PostMapping(value = "/client", params = "save")
    public String saveWorkspace(@ModelAttribute QueryForm form, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                                HttpSession session, RedirectAttributes redirect) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(workspaceFile.toFile(), form);
        session.setAttribute(key(FORM, ws), form);
        redirect.addFlashAttribute("toast", "Page saved to " + workspaceFile.toAbsolutePath());
        return redirectTo(ws, "");
    }

    private QueryForm loadWorkspace() throws IOException {
        return Files.exists(workspaceFile) ? mapper.readValue(workspaceFile.toFile(), QueryForm.class) : new QueryForm();
    }

    // Save session: the page as it is now, under a name, to open again later
    @PostMapping(value = "/client", params = "saveAs")
    public String saveSession(@ModelAttribute QueryForm form, @RequestParam String saveAs, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                              HttpSession session, RedirectAttributes redirect) throws IOException {
        String name = saveAs.trim();
        session.setAttribute(key(FORM, ws), form);
        if (!NAME.matcher(name).matches()) {
            redirect.addFlashAttribute("toast", "A session name is up to 60 letters, digits, spaces, dots, dashes or underscores");
            return redirectTo(ws, "");
        }
        // The HTTP configs as they are on screen (posted with it), the DB and Kafka ones as saved
        useHttpConfigs(form);
        SessionFile.SavedConfigs configs = new SessionFile.SavedConfigs(form.getHttpConfigs(), loadConfigs(), loadKafkaConfigs());
        Files.writeString(sessionFile(name), SessionFile.write(mapper, form, configs));
        session.setAttribute(key(SESSION_NAME, ws), name);
        redirect.addFlashAttribute("toast", "Session \"" + name + "\" saved");
        return redirectTo(ws, "");
    }

    // Open a saved session: a new workspace for it, so it runs in its own tab next to this one
    @GetMapping("/client/session/open")
    public String openSession(@RequestParam String name, HttpSession session, RedirectAttributes redirect) throws IOException {
        if (!NAME.matcher(name).matches() || !Files.exists(sessionFile(name))) {
            redirect.addFlashAttribute("toast", "There's no saved session \"" + name + "\"");
            return "redirect:/client";
        }
        String ws = newWs();
        SessionFile.Opened opened = SessionFile.read(mapper, Files.readString(sessionFile(name)));
        QueryForm form = opened.form();
        // Its configs become the current ones, so the cards' config links point where they did
        if (opened.configs() != null) {
            writeConfigs(httpConfigFile, opened.configs().http(), HttpConfig::new, HttpConfig::isDefaultConfig, HttpConfig::setDefaultConfig);
            writeConfigs(configFile, opened.configs().db(), DatasourceConfig::new, DatasourceConfig::isDefaultConfig, DatasourceConfig::setDefaultConfig);
            writeConfigs(kafkaConfigFile, opened.configs().kafka(), KafkaConfig::new, KafkaConfig::isDefaultConfig, KafkaConfig::setDefaultConfig);
            redirect.addFlashAttribute("toast", "Session \"" + name + "\" opened, with its configs");
        }
        session.setAttribute(key(FORM, ws), form);
        session.setAttribute(key(EXTRACTED_VARIABLES, ws), new HashMap<>(form.getExtractedVariables()));
        session.setAttribute(key(SESSION_NAME, ws), name);
        return redirectTo(ws, "");
    }

    // Load a session file (picked on the page): checked, saved under its name, then the page opens it like a saved one
    @PostMapping(value = "/client/session/import", consumes = "application/json")
    @ResponseBody
    public Map<String, String> importSession(@RequestParam String name, @RequestBody String json) throws IOException {
        String cleaned = name.trim().replaceFirst("(?i)\\.json$", "").replaceAll("[^\\w .-]", "_");
        if (!NAME.matcher(cleaned).matches()) {
            return Map.of("error", "A session name is up to 60 letters, digits, spaces, dots, dashes or underscores");
        }
        try {
            SessionFile.read(mapper, json);   // throws if it isn't a session
        } catch (Exception e) {
            return Map.of("error", "Not a session file: " + e.getMessage());
        }
        Files.writeString(sessionFile(cleaned), json);
        return Map.of("name", cleaned);
    }

    // Duplicate: the page as it is now, in a new workspace, which the page then opens in a new tab
    @PostMapping(value = "/client", params = "duplicate")
    public String duplicate(@ModelAttribute QueryForm form, @RequestParam(defaultValue = DEFAULT_WS) String ws, HttpSession session) {
        session.setAttribute(key(FORM, ws), form);
        String copy = newWs();
        session.setAttribute(key(FORM, copy), form);
        session.setAttribute(key(EXTRACTED_VARIABLES, copy), new HashMap<>(form.getExtractedVariables()));
        if (session.getAttribute(key(SESSION_NAME, ws)) instanceof String name) {
            session.setAttribute(key(SESSION_NAME, copy), name + " copy");
        }
        return redirectTo(copy, "");
    }

    private <T> void writeConfigs(Path file, List<T> configs, java.util.function.Supplier<T> fresh,
                                  java.util.function.Predicate<T> isDefault, Configs.DefaultSetter<T> setDefault) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), Configs.normalize(configs, fresh, isDefault, setDefault));
    }

    record SavedSession(String name, String saved) {}

    // Saved sessions, most recently saved first
    private List<SavedSession> savedSessions() throws IOException {
        try (Stream<Path> files = Files.list(sessionsDir)) {
            List<Path> list = new ArrayList<>(files.filter(f -> f.getFileName().toString().endsWith(".json")).toList());
            list.sort(Comparator.comparing((Path f) -> f.toFile().lastModified()).reversed());
            List<SavedSession> sessions = new ArrayList<>();
            for (Path file : list) {
                String name = file.getFileName().toString().replaceFirst("\\.json$", "");
                String saved = SAVED_AT.format(Files.getLastModifiedTime(file).toInstant().atZone(ZoneId.systemDefault()));
                sessions.add(new SavedSession(name, saved));
            }
            return sessions;
        }
    }

    // The name is checked against NAME first, so it can't reach outside the sessions folder
    private Path sessionFile(String name) {
        return sessionsDir.resolve(name + ".json");
    }

    private static String ws(String ws) {
        return ws != null && WS.matcher(ws).matches() ? ws : DEFAULT_WS;
    }

    private static String newWs() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // Session attribute of one workspace; the default one keeps the plain name
    private static String key(String name, String ws) {
        ws = ws(ws);
        return ws.equals(DEFAULT_WS) ? name : name + ":" + ws;
    }

    private static String redirectTo(String ws, String anchor) {
        ws = ws(ws);
        return "redirect:/client" + (ws.equals(DEFAULT_WS) ? "" : "?ws=" + ws) + anchor;
    }

    // Run on query card i
    @PostMapping(value = "/client", params = "run")
    public String run(@ModelAttribute QueryForm form, @RequestParam int run, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                      HttpSession session) throws IOException {
        mergeExtractedVariables(form, session, ws);
        useHttpConfigs(form);
        captured("Run on Query " + (run + 1), () -> runChain(form, "q" + run));
        storeExtractedVariables(form, session, ws);
        session.setAttribute(key(FORM, ws), form);
        return redirectTo(ws, "#block-" + run);
    }

    // Send on HTTP card i. scapy-minimal starts capturing first, so the chain's traffic is drawn in NetCanvas
    @PostMapping(value = "/client", params = "send")
    public String send(@ModelAttribute QueryForm form, @RequestParam int send, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                       HttpSession session) throws IOException {
        mergeExtractedVariables(form, session, ws);
        useHttpConfigs(form);
        captured("Send on HTTP " + (send + 1), () -> runChain(form, "h" + send));
        storeExtractedVariables(form, session, ws);
        session.setAttribute(key(FORM, ws), form);
        return redirectTo(ws, "#http-" + send);
    }

    // Consume on Kafka card i
    @PostMapping(value = "/client", params = "consume")
    public String consume(@ModelAttribute QueryForm form, @RequestParam int consume, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                          HttpSession session) throws IOException {
        mergeExtractedVariables(form, session, ws);
        useHttpConfigs(form);
        captured("Consume on Kafka " + (consume + 1), () -> runChain(form, "k" + consume));
        storeExtractedVariables(form, session, ws);
        session.setAttribute(key(FORM, ws), form);
        return redirectTo(ws, "#kafka-" + consume);
    }

    // The sniffer captures (and NetCanvas draws) what this action causes: from just before the chain to just after it
    private void captured(String action, Runnable chain) {
        captureTrigger.startCapture(action);
        try {
            chain.run();
        } finally {
            captureTrigger.stopCapture();
        }
    }

    // Extract on Kafka card i: its JSON path again, on the message already in its box
    @PostMapping(value = "/client", params = "extractMessage")
    public String extractMessage(@ModelAttribute QueryForm form, @RequestParam int extractMessage, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                                 HttpSession session) {
        mergeExtractedVariables(form, session, ws);
        if (extractMessage >= 0 && extractMessage < form.getSubscribers().size()) {
            QueryForm.Subscriber subscriber = form.getSubscribers().get(extractMessage);
            Extraction extraction = extract(subscriber.getOutput(), subscriber.getExtractor());
            subscriber.setExtractorOutput(extraction.value());
            if (extraction.ok() && !subscriber.getExtractor().isBlank()) {
                form.getExtractedVariables().put(extractorName(subscriber.getExtractor()), extraction.value());
            }
        }
        storeExtractedVariables(form, session, ws);
        session.setAttribute(key(FORM, ws), form);
        return redirectTo(ws, "#kafka-" + extractMessage);
    }

    // Run on a Kafka card's java: the script again, on the message already in its box
    @PostMapping(value = "/client", params = "script")
    public String script(@ModelAttribute QueryForm form, @RequestParam int script, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                         HttpSession session) {
        mergeExtractedVariables(form, session, ws);
        if (script >= 0 && script < form.getSubscribers().size()) {
            QueryForm.Subscriber subscriber = form.getSubscribers().get(script);
            Map<String, String> passed = new LinkedHashMap<>();
            try {
                KafkaConfig config = Configs.pick(loadKafkaConfigs(), subscriber.getConfig(), KafkaConfig::isDefaultConfig);
                String topic = QueryTemplate.fill(subscriber.getTopic().trim(), form.getExtractedVariables());
                runScript(subscriber, config, topic, form.getExtractedVariables(), null, passed);
            } catch (Exception e) {
                subscriber.setScriptOutput(describe(e));
            }
            form.getExtractedVariables().putAll(passed);
        }
        storeExtractedVariables(form, session, ws);
        session.setAttribute(key(FORM, ws), form);
        return redirectTo(ws, "#kafka-" + script);
    }

    @PostMapping(value = "/client", params = "extract")
    public String extract(@ModelAttribute QueryForm form, @RequestParam int extract, @RequestParam(defaultValue = DEFAULT_WS) String ws,
                          HttpSession session) {
        mergeExtractedVariables(form, session, ws);
        if (extract >= 0 && extract < form.getRequests().size()) {
            QueryForm.Request request = form.getRequests().get(extract);
            Extraction extraction = extractOutput(request.getOutput(), request.getExtractor());
            request.setExtractorOutput(extraction.value());
            if (extraction.ok()) {
                form.getExtractedVariables().put(extractorName(request.getExtractor()), extraction.value());
            }
        }
        storeExtractedVariables(form, session, ws);
        session.setAttribute(key(FORM, ws), form);
        return redirectTo(ws, "#http-" + extract);
    }

    // result: what the next cards' conditions and if diamonds check ("status", "json", ...), null on failure
    record Step(boolean ok, String output, ObjectNode result) {
        Step(boolean ok, String output) {
            this(ok, output, null);
        }
    }

    // A value read from a JSON response; when not ok, value is the error to show
    record Extraction(boolean ok, String value) {}

    // HTTP cards use the configs as they are on screen (posted with Send/Run), the saved ones otherwise
    private void useHttpConfigs(QueryForm form) throws IOException {
        form.setHttpConfigs(form.getHttpConfigs().isEmpty() ? loadHttpConfigs() : Configs.normalize(form.getHttpConfigs(),
                HttpConfig::new, HttpConfig::isDefaultConfig, HttpConfig::setDefaultConfig));
    }

    /**
     * Runs the card with this node id ("h0" = HTTP card 1, "q2" = query card 3, "d0" = if 1), then
     * each of its "next" cards in order, each continuing down its own branch. A failure stops only its
     * own branch, a link with a condition is only followed when the condition holds, and a card
     * reached by two branches runs once.
     */
    private void runChain(QueryForm form, String start) {
        runFrom(form, start, null, null, new HashMap<>(form.getExtractedVariables()), form.getHttpConfigs(), new HashSet<>());
    }

    private void runFrom(QueryForm form, String id, String previous, ObjectNode prev, Map<String, String> variables,
                         List<HttpConfig> httpConfigs, Set<String> done) {
        if (id == null || !id.matches("[hqdk]\\d{1,4}") || !done.add(id)) {
            return;
        }
        int index = Integer.parseInt(id.substring(1));
        Step step;
        String next;
        String when;
        Consumer<String> note;
        Map<String, String> nextVariables = variables;
        if (id.startsWith("h") && index < form.getRequests().size()) {
            QueryForm.Request request = form.getRequests().get(index);
            step = send(request, httpConfigs, variables);
            request.setOutput(chained(previous) + step.output());
            next = request.getNext();
            when = request.getWhen();
            note = text -> request.setOutput(request.getOutput() + text);
            if (step.ok() && !request.getExtractor().isBlank()) {
                nextVariables = new HashMap<>(variables);
                String name = extractorName(request.getExtractor());
                nextVariables.put(name, request.getExtractorOutput());
                form.getExtractedVariables().put(name, request.getExtractorOutput());
            }
        } else if (id.startsWith("q") && index < form.getBlocks().size()) {
            QueryForm.Block block = form.getBlocks().get(index);
            Map<String, String> passed = new LinkedHashMap<>();
            step = runBlock(block, variables, prev, passed);
            block.setOutput(chained(previous) + step.output());
            next = block.getNext();
            when = block.getWhen();
            note = text -> block.setOutput(block.getOutput() + text);
            nextVariables = passOn(form, variables, step.ok() ? passed : Map.of());
        } else if (id.startsWith("k") && index < form.getSubscribers().size()) {
            QueryForm.Subscriber subscriber = form.getSubscribers().get(index);
            Map<String, String> passed = new LinkedHashMap<>();
            step = consume(subscriber, variables, prev, passed);
            subscriber.setMeta(chained(previous) + step.output());
            next = subscriber.getNext();
            when = subscriber.getWhen();
            note = text -> subscriber.setMeta(subscriber.getMeta() + text);
            nextVariables = passOn(form, variables, step.ok() ? passed : Map.of());
        } else if (id.startsWith("d") && index < form.getDecisions().size()) {
            QueryForm.Decision decision = form.getDecisions().get(index);
            Map<String, String> set = new LinkedHashMap<>();
            Boolean passed = check(decision, prev, variables, set);
            decision.setOutput(chained(previous) + decision.getOutput());
            if (passed == null) {
                return;  // the check itself failed, its output says why
            }
            // The cards after a diamond see the result from before it
            step = new Step(true, decision.getOutput(), prev);
            next = passed ? decision.getNext() : decision.getNextElse();
            when = decision.getWhen();
            note = text -> decision.setOutput(decision.getOutput() + text);
            nextVariables = passOn(form, variables, set);
        } else {
            return;
        }
        if (!step.ok()) {
            return;
        }
        Map<String, String> conditions = linkConditions(when);
        // "next" is a comma-separated list of node ids
        for (String link : next.split(",")) {
            String target = link.trim();
            if (target.isEmpty()) {
                continue;
            }
            String condition = conditions.getOrDefault(target, "");
            Map<String, String> linkVariables = nextVariables;
            if (!condition.isBlank()) {
                try {
                    QueryRunner.Script linkCheck = checkLink(condition, step.result(), nextVariables);
                    if (!linkCheck.passed()) {
                        note.accept("\n-> skipped " + nodeName(target) + ": " + condition.trim() + " is false"
                                + (linkCheck.output().isEmpty() ? "" : "\n" + linkCheck.output()));
                        continue;
                    }
                    linkVariables = passOn(form, nextVariables, linkCheck.set());
                } catch (Exception e) {
                    note.accept("\n-> skipped " + nodeName(target) + ": " + describe(e));
                    continue;
                }
            }
            runFrom(form, target, id, step.result(), linkVariables, httpConfigs, done);
        }
    }

    // An if diamond: true / false, or null when the check couldn't run (the output says why). Its expression is
    // checked here; python and java run in a container and can set(...) variables for the branch it takes.
    private Boolean check(QueryForm.Decision decision, ObjectNode prev, Map<String, String> variables, Map<String, String> set) {
        String condition = decision.getCondition();
        try {
            String language = decision.getMode();
            if ("python".equals(language) || "java".equals(language)) {
                QueryRunner.Script check = checkScript(language, condition, prev, variables);
                set.putAll(check.set());
                String said = check.describe();
                decision.setOutput((check.passed() ? "true" : "false") + (said.isEmpty() ? "" : "\n" + said));
                return check.passed();
            }
            boolean passed = Condition.test(condition, prev, variables);
            decision.setOutput((passed ? "true: " : "false: ") + (condition.isBlank() ? "(no condition)" : condition.trim()));
            return passed;
        } catch (Exception e) {
            decision.setOutput(describe(e));
            return null;
        }
    }

    // A link's condition: an expression ("status == 200"), or "python: ..." / "java: ..." for a script check
    private QueryRunner.Script checkLink(String condition, ObjectNode result, Map<String, String> variables) throws Exception {
        String text = condition.trim();
        for (String language : List.of("python", "java")) {
            if (text.regionMatches(true, 0, language + ":", 0, language.length() + 1)) {
                return checkScript(language, text.substring(language.length() + 1).trim(), result, variables);
            }
        }
        return new QueryRunner.Script(List.of(), Map.of(), Condition.test(text, result, variables), "");
    }

    // Diamonds' and links' scripts run with the default DB config's images
    private QueryRunner.Script checkScript(String language, String code, ObjectNode prev, Map<String, String> variables) throws Exception {
        DatasourceConfig config = Configs.pick(loadConfigs(), -1, DatasourceConfig::isDefaultConfig);
        return queryRunner.script(language, config.getPythonImage(), config.getJavaImage(), config.getContainerName(),
                QueryRunner.Mode.CHECK, variables, prev, Map.of(), code);
    }

    // The variables for the cards after this one: the ones so far plus what it set; kept for the page's own runs too
    private static Map<String, String> passOn(QueryForm form, Map<String, String> variables, Map<String, String> set) {
        if (set.isEmpty()) {
            return variables;
        }
        Map<String, String> next = new LinkedHashMap<>(variables);
        next.putAll(set);
        form.getExtractedVariables().putAll(set);
        return next;
    }

    // The "when" JSON of a card: node id -> condition of the link to it
    private Map<String, String> linkConditions(String when) {
        if (when == null || when.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> conditions = new HashMap<>();
            mapper.readTree(when).properties().forEach(entry -> conditions.put(entry.getKey(), entry.getValue().asText()));
            return conditions;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // What the next cards get to check: status, ok, body, output, json (the parsed body, or null)
    private ObjectNode httpResult(HttpRunner.Response response) {
        ObjectNode result = mapper.createObjectNode();
        result.put("ok", response.status() < 400);
        result.put("status", response.status());
        result.put("body", response.body());
        result.put("output", response.output());
        JsonNode json;
        try {
            json = mapper.readTree(response.body());
        } catch (Exception e) {
            json = null;
        }
        result.set("json", json == null || json.isMissingNode() ? NullNode.getInstance() : json);
        return result;
    }

    // What the next cards get to check: ok, exit, output, and json: MongoDB's result, Redis's replies (a list), else null
    private ObjectNode queryResult(QueryRunner.Ran run) {
        ObjectNode result = mapper.createObjectNode();
        result.put("ok", run.exitCode() == 0);
        result.put("exit", run.exitCode());
        result.put("output", run.output());
        JsonNode json = null;
        try {
            json = run.json() == null ? null : mapper.readTree(run.json());
        } catch (Exception e) {
            json = null;   // e.g. mongosh printed more than the result
        }
        result.set("json", json == null || json.isMissingNode() ? NullNode.getInstance() : json);
        return result;
    }

    // The variable an extractor fills: its last field name, so "data[0].processingId" gives "processingId"
    private static String extractorName(String path) {
        List<String> segments = segments(path);
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (!segments.get(i).matches("\\d+")) {
                return segments.get(i);
            }
        }
        return segments.getLast();
    }

    // "data.items[0].id" or "data.items.0.id" -> [data, items, 0, id]
    private static List<String> segments(String path) {
        String dotted = path.trim().replaceAll("\\[(\\d+)]", ".$1");
        if (dotted.startsWith(".")) {
            dotted = dotted.substring(1);
        }
        List<String> segments = List.of(dotted.split("\\.", -1));
        if (segments.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("path contains an empty segment");
        }
        return segments;
    }

    private void mergeExtractedVariables(QueryForm form, HttpSession session, String ws) {
        Object stored = session.getAttribute(key(EXTRACTED_VARIABLES, ws));
        if (stored instanceof Map<?, ?> values) {
            values.forEach((key, value) -> form.getExtractedVariables().put(String.valueOf(key), String.valueOf(value)));
        }
    }

    private void storeExtractedVariables(QueryForm form, HttpSession session, String ws) {
        session.setAttribute(key(EXTRACTED_VARIABLES, ws), new HashMap<>(form.getExtractedVariables()));
    }

    private static String chained(String previous) {
        return previous == null ? "" : "(chained after " + nodeName(previous) + ")\n";
    }

    // "h0" -> "HTTP 1", "q2" -> "Query 3", "d0" -> "If 1", "k1" -> "Kafka 2"
    private static String nodeName(String id) {
        if (!id.matches("[hqdk]\\d{1,4}")) {
            return id;
        }
        String kind = switch (id.charAt(0)) {
            case 'h' -> "HTTP ";
            case 'q' -> "Query ";
            case 'k' -> "Kafka ";
            default -> "If ";
        };
        return kind + (Integer.parseInt(id.substring(1)) + 1);
    }

    // One HTTP card, with the saved headers; a 4xx/5xx status counts as a failure
    // $(name) in the url, the body and the headers' values (e.g. a token an earlier card set) is filled in first
    private Step send(QueryForm.Request request, List<HttpConfig> httpConfigs, Map<String, String> variables) {
        if (request.getUrl().isBlank()) {
            return new Step(false, "No url");
        }
        try {
            HttpConfig config = Configs.pick(httpConfigs, request.getConfig(), HttpConfig::isDefaultConfig);
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, String> header : config.headerMap().entrySet()) {
                headers.put(header.getKey(), QueryTemplate.fill(header.getValue(), variables));
            }
            HttpRunner.Response response = httpRunner.send(headers, request.getMethod(),
                    QueryTemplate.fill(request.getUrl(), variables), QueryTemplate.fill(request.getBody(), variables));
            Extraction extraction = extract(response.body(), request.getExtractor());
            request.setExtractorOutput(extraction.value());
            if (response.status() < 400 && !extraction.ok()) {
                // A missing value would end up in the next cards' $(...), so the branch stops here
                return new Step(false, response.output() + "\n\nStopped: " + extraction.value());
            }
            return new Step(response.status() < 400, response.output(), httpResult(response));
        } catch (Exception e) {
            return new Step(false, "Request " + request.getMethod() + " " + request.getUrl().trim() + " failed:\n" + describe(e));
        }
    }

    // Extract one JSON value by dot path, for example "processingId", "data.processingId" or "items[0].id"
    private Extraction extract(String body, String path) {
        if (path == null || path.isBlank()) {
            return new Extraction(true, "");
        }
        try {
            StringBuilder pointer = new StringBuilder();
            for (String segment : segments(path)) {
                pointer.append('/').append(segment.replace("~", "~0").replace("/", "~1"));
            }
            JsonNode value = mapper.readTree(body).at(pointer.toString());
            if (value.isMissingNode()) {
                return new Extraction(false, "No value at " + path.trim());
            }
            return new Extraction(true, value.isValueNode() ? value.asText() : mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (Exception e) {
            return new Extraction(false, "Extractor error: " + e.getMessage());
        }
    }

    private Extraction extractOutput(String output, String path) {
        int body = output.indexOf("\n\n");
        return body < 0 ? new Extraction(false, "Extractor error: this card has no HTTP response body yet") : extract(output.substring(body + 2), path);
    }

    // One query card: its script first (it gets the variables so far and the previous card's result; each run(...) is one
    // execution of the query, set(...) passes a variable on), then a one-shot psql job with the saved config
    private Step runBlock(QueryForm.Block block, Map<String, String> inheritedVariables, ObjectNode prev, Map<String, String> passed) {
        if (block.getQuery().isBlank()) {
            return new Step(false, "Nothing to run");
        }
        String said = "";
        try {
            DatasourceConfig config = Configs.pick(loadConfigs(), block.getConfig(), DatasourceConfig::isDefaultConfig);
            String schema = block.getSchema().isBlank() ? config.getSchema() : block.getSchema().trim();
            List<Map<String, String>> runs = List.of(Map.of());
            if (!block.getVars().isBlank()) {
                QueryRunner.Script script = queryRunner.script(block.getLanguage(), config.getPythonImage(), config.getJavaImage(),
                        config.getContainerName(), QueryRunner.Mode.RUNS, inheritedVariables, prev, Map.of(), block.getVars());
                if (script.runs().isEmpty() && script.set().isEmpty()) {
                    throw new IllegalArgumentException("the script never called run(...) or set(...), so there is nothing to execute");
                }
                passed.putAll(script.set());
                if (!script.runs().isEmpty()) runs = script.runs();
                said = script.describe();
            }
            List<Map<String, String>> values = new ArrayList<>();
            for (Map<String, String> run : runs) {
                Map<String, String> merged = new HashMap<>(inheritedVariables);
                merged.putAll(passed);
                merged.putAll(run);
                values.add(merged);
            }
            QueryRunner.Ran result = queryRunner.run(config, schema, QueryTemplate.expand(values, block.getQuery()));
            String output = (said.isEmpty() ? "" : said + "\n\n") + result.output();
            return new Step(result.exitCode() == 0, output, queryResult(result));
        } catch (Exception e) {
            return new Step(false, (said.isEmpty() ? "" : said + "\n\n") + describe(e));
        }
    }

    /**
     * One Kafka card: the topic's newest message (or, in "next" mode, the next one to arrive) into its
     * box, then its JSON path and its java script on it. The variables they give go into passed.
     */
    private Step consume(QueryForm.Subscriber subscriber, Map<String, String> variables, ObjectNode prev, Map<String, String> passed) {
        subscriber.setOutput("");
        subscriber.setExtractorOutput("");
        subscriber.setScriptOutput("");
        if (subscriber.getTopic().isBlank()) {
            return new Step(false, "No topic");
        }
        String topic = subscriber.getTopic().trim();
        boolean next = "next".equals(subscriber.getMode());
        int timeout = subscriber.getTimeout() == null ? 30 : subscriber.getTimeout();
        KafkaConfig config;
        KafkaRunner.Message message;
        try {
            topic = QueryTemplate.fill(topic, variables);
            config = Configs.pick(loadKafkaConfigs(), subscriber.getConfig(), KafkaConfig::isDefaultConfig);
            Optional<KafkaRunner.Message> read = kafkaRunner.read(config, topic, next, timeout);
            if (read.isEmpty()) {
                return new Step(false, next ? "No new message on " + topic + " within " + timeout + "s" : "There are no messages on " + topic);
            }
            message = read.get();
        } catch (Exception e) {
            return new Step(false, "Reading " + topic + " failed:\n" + describe(e));
        }
        subscriber.setOutput(pretty(message.value()));

        Extraction extraction = extract(subscriber.getOutput(), subscriber.getExtractor());
        subscriber.setExtractorOutput(extraction.value());
        if (!extraction.ok()) {
            // A missing value would end up in the next cards' $(...), so the branch stops here
            return new Step(false, message.describe() + "\n\nStopped: " + extraction.value());
        }
        if (!subscriber.getExtractor().isBlank()) {
            passed.put(extractorName(subscriber.getExtractor()), extraction.value());
        }
        if (!runScript(subscriber, config, topic, variables, prev, passed)) {
            return new Step(false, message.describe() + "\n\nStopped: the script failed");
        }
        return new Step(true, message.describe(), kafkaResult(message, subscriber.getOutput()));
    }

    /**
     * A Kafka card's script (python or java), if it has one: MESSAGE (its box), TOPIC, the variables so far and the
     * previous card's result come in; what it set(...)s goes into passed. False if it failed (its output says why).
     */
    private boolean runScript(QueryForm.Subscriber subscriber, KafkaConfig config, String topic,
                              Map<String, String> variables, ObjectNode prev, Map<String, String> passed) {
        subscriber.setScriptOutput("");
        if (subscriber.getScript().isBlank()) {
            return true;
        }
        Map<String, String> vars = new LinkedHashMap<>(variables);
        vars.putAll(passed);
        try {
            QueryRunner.Script script = queryRunner.script(subscriber.getScriptLanguage(), config.getPythonImage(), config.getJavaImage(),
                    config.getContainerName(), QueryRunner.Mode.SET, vars, prev,
                    Map.of("message", subscriber.getOutput(), "topic", topic), subscriber.getScript());
            String said = script.describe();
            subscriber.setScriptOutput(said.isEmpty() ? "(done, nothing printed or set)" : said);
            passed.putAll(script.set());
            return true;
        } catch (Exception e) {
            subscriber.setScriptOutput(describe(e));
            return false;
        }
    }

    // What the next cards get to check: ok, body (the value), output (the box), json (the parsed value,
    // or null) and where it came from: topic, partition, offset, timestamp, key, headers
    private ObjectNode kafkaResult(KafkaRunner.Message message, String shown) {
        ObjectNode result = mapper.createObjectNode();
        result.put("ok", true);
        result.put("body", message.value());
        result.put("output", shown);
        result.put("topic", message.topic());
        result.put("partition", message.partition());
        result.put("offset", message.offset());
        result.put("timestamp", message.timestamp());
        result.put("key", message.key());
        result.set("headers", mapper.valueToTree(message.headers()));
        JsonNode json;
        try {
            json = message.value() == null ? null : mapper.readTree(message.value());
        } catch (Exception e) {
            json = null;
        }
        result.set("json", json == null || json.isMissingNode() ? NullNode.getInstance() : json);
        return result;
    }

    // A JSON value indented, so the box is readable; anything else as it is
    private String pretty(String value) {
        if (value == null) {
            return "";
        }
        try {
            JsonNode json = mapper.readTree(value);
            return json.isContainerNode() ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json) : value;
        } catch (Exception e) {
            return value;
        }
    }

    private static String describe(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return "Error: " + e.getMessage();
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
        String message = e.getMessage();
        return "Failed: " + e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private List<DatasourceConfig> loadConfigs() throws IOException {
        return Configs.normalize(load(configFile, DatasourceConfig.class),
                DatasourceConfig::new, DatasourceConfig::isDefaultConfig, DatasourceConfig::setDefaultConfig);
    }

    private List<HttpConfig> loadHttpConfigs() throws IOException {
        List<HttpConfig> saved = load(httpConfigFile, HttpConfig.class);
        saved.forEach(HttpConfig::liftFormedHeaders);   // older files: Accept etc. were lines of the free text
        return Configs.normalize(saved,
                HttpConfig::new, HttpConfig::isDefaultConfig, HttpConfig::setDefaultConfig);
    }

    private List<KafkaConfig> loadKafkaConfigs() throws IOException {
        return Configs.normalize(load(kafkaConfigFile, KafkaConfig.class),
                KafkaConfig::new, KafkaConfig::isDefaultConfig, KafkaConfig::setDefaultConfig);
    }

    // A saved list of configs; older files hold a single config object, which becomes a list of one
    private <T> List<T> load(Path file, Class<T> type) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        JsonNode json = mapper.readTree(file.toFile());
        if (!json.isArray()) {
            return List.of(mapper.treeToValue(json, type));
        }
        List<T> configs = new ArrayList<>();
        for (JsonNode item : json) {
            configs.add(mapper.treeToValue(item, type));
        }
        return configs;
    }
}
