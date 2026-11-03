package com.example.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Two ephemeral docker containers (docker run --rm):
 * 1. python or java (script): every card's script: a query card's (its run(...) calls), a Kafka card's, an if
 *    diamond's or a link's check. Each gets the variables so far and the previous card's result, and its set(...)
 *    calls pass variables on to the cards after it.
 * 2. psql: runs the query once per value, with the connection settings passed as PG* env vars.
 *    Each run becomes its own psql -c, labelled with an \echo when there's more than one.
 */
@Component
public class QueryRunner {

    private static final long TIMEOUT_SECONDS = 120;
    private static final String MARKER = "__SCRIPT_RESULT__";
    // Variables also go in as environment variables; bigger ones only through vars / VARS (the docker command line has a limit)
    private static final int MAX_ENV_BYTES = 32 * 1024;
    // Names the container needs for itself, never overwritten by a variable
    private static final Set<String> RESERVED_ENV = Set.of("PATH", "HOME", "HOSTNAME", "JAVA_HOME", "JAVA_VERSION", "LANG", "LANGUAGE", "LC_ALL", "TERM");

    /**
     * What a script is for: RUNS, a query card's (each run(...) is one execution of its query), CHECK, an if diamond's
     * or a link's condition (returns true / false; a single line is the expression to return), SET, a Kafka card's.
     * In every mode the script gets the variables so far and the previous card's result, and set(name, value) passes a
     * variable on to every card after it on that path.
     */
    public enum Mode { RUNS, CHECK, SET }

    record Result(int exitCode, String output) {}

    /** What a script gave back: its run(...) calls, what it set(...), its check's result and what it printed. */
    record Script(List<Map<String, String>> runs, Map<String, String> set, boolean passed, String output) {

        /** What it printed, then a "-> name = value" line per variable it set. */
        String describe() {
            StringBuilder out = new StringBuilder(output);
            set.forEach((name, value) -> out.append(out.isEmpty() ? "" : "\n").append("-> ").append(name).append(" = ").append(value));
            return out.toString();
        }
    }

    // python: the input JSON on the first stdin line, the script after it.
    //   vars (dict), prev (the previous card's result: prev["status"], prev["json"], ...), MESSAGE / TOPIC (Kafka cards),
    //   var(name, default), set(name, value) / set(name=value) / set({...}), run(name=value, ...); vars are env vars too.
    //   set(...) with anything else is still python's set().
    private static final String PYTHON_WRAPPER = """
            import sys, json, os, re, textwrap, builtins
            _in = json.loads(sys.stdin.readline())
            _code = textwrap.dedent(sys.stdin.read()).strip(chr(10))
            vars = dict(_in.get('vars') or {})
            prev = _in.get('prev')
            MESSAGE = _in.get('message') or ''
            TOPIC = _in.get('topic') or ''
            _runs, _set = [], {}
            def _text(v):
                return v if isinstance(v, str) else json.dumps(v) if isinstance(v, (dict, list, bool)) or v is None else str(v)
            def run(**kw):
                _runs.append({k: _text(v) for k, v in kw.items()})
            def set(*args, **kw):
                if not kw and len(args) == 2 and isinstance(args[0], str):
                    kw = {args[0]: args[1]}
                elif not kw and len(args) == 1 and isinstance(args[0], dict):
                    kw = args[0]
                elif args or not kw:
                    return builtins.set(*args)
                for k, v in kw.items():
                    _set[k] = vars[k] = _text(v)
            def var(name, default=None):
                return vars.get(name, default)
            _ns = {'__name__': '__main__', 'run': run, 'set': set, 'var': var, 'vars': vars, 'prev': prev,
                   'MESSAGE': MESSAGE, 'TOPIC': TOPIC}
            _passed = None
            if _in['mode'] == 'check':
                _lines = [l for l in _code.splitlines() if l.strip() and not l.strip().startswith('#')]
                _body = _code
                if len(_lines) == 1 and not re.match('[ ]*return([^A-Za-z0-9_]|$)', _lines[0]):
                    _body = 'return (' + _lines[0].strip() + ')'
                exec(compile('def check(prev, vars):' + chr(10) + textwrap.indent(_body or 'return False', '    '), 'check', 'exec'), _ns)
                _passed = bool(_ns['check'](prev, vars))
            else:
                exec(compile(_code, 'script', 'exec'), _ns)
            print('__MARKER__' + json.dumps({'runs': _runs, 'set': _set, 'passed': _passed}))
            """.replace("__MARKER__", MARKER);

    // java: the script is the body of main (CHECK: of check(), which returns a boolean; a single line is the expression
    // to return), its leading import lines go above the class. The input comes as the base64 last line of the file.
    //   VARS (Map), var(name), PREV (the previous card's result), str/num/bool/has("json.state") to read it,
    //   MESSAGE / TOPIC (Kafka cards), set(name, value), run(name, value, ...); vars are env vars too.
    private static final String JAVA_WRAPPER = """
            import java.util.*;
            import java.util.stream.*;
            //IMPORTS
            public class Main {
                static final char BACKSLASH = 92;
                static String src$;
                static int at$;
                static final Map<String, Object> IN = input();
                static final Map<String, String> VARS = strings(IN.get("vars"));
                static final Object PREV = IN.get("prev");
                static final String MESSAGE = IN.get("message") == null ? "" : String.valueOf(IN.get("message"));
                static final String TOPIC = IN.get("topic") == null ? "" : String.valueOf(IN.get("topic"));
                static final List<Map<String, String>> RUNS = new ArrayList<>();
                static final Map<String, String> SET = new LinkedHashMap<>();

                // ---- the variables so far, and passing new ones on ----
                static String var(String name) { return VARS.get(name); }
                static String var(String name, String fallback) { return VARS.getOrDefault(name, fallback); }
                static void set(String name, Object value) {
                    String text = value instanceof String s ? s : value instanceof Map || value instanceof List ? json(value) : String.valueOf(value);
                    SET.put(name, text);
                    VARS.put(name, text);
                }
                static void set(Map<String, ?> vars) { vars.forEach(Main::set); }
                static void run(Object... pairs) {
                    if (pairs.length % 2 != 0) {
                        throw new IllegalArgumentException("run(...) takes name, value pairs: run(name1, value1, name2, value2, ...)");
                    }
                    Map<String, String> vars = new LinkedHashMap<>();
                    for (int i = 0; i < pairs.length; i += 2) vars.put(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
                    RUNS.add(vars);
                }
                static void run(Map<String, ?> vars) {
                    Map<String, String> copy = new LinkedHashMap<>();
                    vars.forEach((name, value) -> copy.put(name, String.valueOf(value)));
                    RUNS.add(copy);
                }

                // ---- the previous card's result: prev("status"), prev("json.items[0].id"), or just prev("state") for json.state ----
                static Object prev(String path) {
                    Object node = PREV;
                    String[] parts = path.replace('[', '.').replace("]", "").split("[.]");
                    if (parts.length > 0 && node instanceof Map<?, ?> m && !m.containsKey(parts[0])) node = m.get("json");
                    for (String part : parts) {
                        if (part.isEmpty()) continue;
                        if (part.equals("length") && node instanceof List<?> list) node = (long) list.size();
                        else if (part.equals("length") && node instanceof String text) node = (long) text.length();
                        else if (node instanceof Map<?, ?> map) node = map.get(part);
                        else if (node instanceof List<?> list && !part.isEmpty() && part.chars().allMatch(Character::isDigit)
                                 && Integer.parseInt(part) < list.size()) node = list.get(Integer.parseInt(part));
                        else return null;
                    }
                    return node;
                }
                static boolean has(String path) { return prev(path) != null; }
                static String str(String path) {
                    Object value = prev(path);
                    return value == null ? "" : value instanceof Map || value instanceof List ? json(value) : String.valueOf(value);
                }
                static double num(String path) {
                    Object value = prev(path);
                    if (value instanceof Number n) return n.doubleValue();
                    try { return Double.parseDouble(String.valueOf(value).trim()); } catch (Exception e) { return Double.NaN; }
                }
                static boolean bool(String path) {
                    Object value = prev(path);
                    return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value));
                }

                static boolean check() throws Exception {
            //CHECK
                }

                static void body() throws Exception {
            //CODE
                }

                public static void main(String[] args) throws Exception {
                    Boolean passed = null;
                    if ("check".equals(IN.get("mode"))) passed = check(); else body();
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("runs", RUNS);
                    out.put("set", SET);
                    out.put("passed", passed);
                    System.out.println("__MARKER__" + json(out));
                }

                // ---- a small JSON reader and writer, so the script needs no library ----
                static Map<String, Object> input() {
                    try {
                        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/tmp/Main.java"))) {
                            if (line.startsWith("//IN:")) {
                                src$ = new String(Base64.getDecoder().decode(line.substring(5).trim()), java.nio.charset.StandardCharsets.UTF_8);
                                at$ = 0;
                                @SuppressWarnings("unchecked") Map<String, Object> in = (Map<String, Object>) value$();
                                return in;
                            }
                        }
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    return new LinkedHashMap<>();
                }
                static Map<String, String> strings(Object value) {
                    Map<String, String> map = new LinkedHashMap<>();
                    if (value instanceof Map<?, ?> m) m.forEach((k, v) -> map.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                    return map;
                }
                static void ws$() { while (at$ < src$.length() && src$.charAt(at$) <= 32) at$++; }
                static Object value$() {
                    ws$();
                    char c = src$.charAt(at$);
                    if (c == '{') {
                        at$++;
                        Map<String, Object> map = new LinkedHashMap<>();
                        ws$();
                        if (src$.charAt(at$) == '}') { at$++; return map; }
                        while (true) {
                            ws$();
                            String key = string$();
                            ws$();
                            at$++;
                            map.put(key, value$());
                            ws$();
                            if (src$.charAt(at$++) == '}') return map;
                        }
                    }
                    if (c == '[') {
                        at$++;
                        List<Object> list = new ArrayList<>();
                        ws$();
                        if (src$.charAt(at$) == ']') { at$++; return list; }
                        while (true) {
                            list.add(value$());
                            ws$();
                            if (src$.charAt(at$++) == ']') return list;
                        }
                    }
                    if (c == '"') return string$();
                    if (src$.startsWith("true", at$)) { at$ += 4; return Boolean.TRUE; }
                    if (src$.startsWith("false", at$)) { at$ += 5; return Boolean.FALSE; }
                    if (src$.startsWith("null", at$)) { at$ += 4; return null; }
                    int start = at$;
                    while (at$ < src$.length() && "+-0123456789.eE".indexOf(src$.charAt(at$)) >= 0) at$++;
                    String number = src$.substring(start, at$);
                    try { return Long.valueOf(number); } catch (NumberFormatException e) { return Double.valueOf(number); }
                }
                static String string$() {
                    StringBuilder out = new StringBuilder();
                    at$++;
                    while (true) {
                        char c = src$.charAt(at$++);
                        if (c == '"') return out.toString();
                        if (c != BACKSLASH) { out.append(c); continue; }
                        char e = src$.charAt(at$++);
                        switch (e) {
                            case 'n' -> out.append((char) 10);
                            case 't' -> out.append((char) 9);
                            case 'r' -> out.append((char) 13);
                            case 'b' -> out.append((char) 8);
                            case 'f' -> out.append((char) 12);
                            case 'u' -> { out.append((char) Integer.parseInt(src$.substring(at$, at$ + 4), 16)); at$ += 4; }
                            default -> out.append(e);
                        }
                    }
                }
                static String json(Object value) {
                    if (value == null) return "null";
                    if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
                    if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                            .map(e -> json(String.valueOf(e.getKey())) + ":" + json(e.getValue())).collect(Collectors.joining(",", "{", "}"));
                    if (value instanceof Collection<?> list) return list.stream().map(Main::json).collect(Collectors.joining(",", "[", "]"));
                    StringBuilder out = new StringBuilder().append('"');
                    for (char ch : String.valueOf(value).toCharArray()) {
                        if (ch == '"' || ch == BACKSLASH) out.append(BACKSLASH).append(ch);
                        else if (ch < 0x20) out.append(BACKSLASH).append('u').append(String.format("%04x", (int) ch));
                        else out.append(ch);
                    }
                    return out.append('"').toString();
                }
            }
            """.replace("__MARKER__", MARKER);

    private final ObjectMapper mapper;

    public QueryRunner(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Runs a python or java script in a one-shot container with no network. vars are the variables so far (as vars /
     * VARS and as environment variables), prev the previous card's result (or null), extra what else the card gives
     * it (message, topic: also MESSAGE / TOPIC env vars).
     */
    public Script script(String language, String pythonImage, String javaImage, String containerName, Mode mode,
                         Map<String, String> vars, JsonNode prev, Map<String, String> extra, String code)
            throws IOException, InterruptedException {
        boolean java = "java".equals(language);
        ObjectNode input = mapper.createObjectNode();
        input.put("mode", mode.name().toLowerCase());
        input.set("vars", mapper.valueToTree(vars));
        input.set("prev", prev == null ? NullNode.getInstance() : prev);
        extra.forEach(input::put);
        String json = mapper.writeValueAsString(input);

        List<String> command = new ArrayList<>(List.of("docker", "run", "--rm", "-i", "--network=none",
                "--name", uniqueName(containerName, java ? "java" : "py")));
        Map<String, String> env = new LinkedHashMap<>(vars);
        extra.forEach((name, value) -> env.put(name.toUpperCase(), value));
        env.forEach((name, value) -> {
            String pair = name + "=" + value;
            if (!RESERVED_ENV.contains(name) && name.matches("[A-Za-z_]\\w*") && pair.getBytes(StandardCharsets.UTF_8).length <= MAX_ENV_BYTES) {
                command.addAll(List.of("-e", pair));
            }
        });
        Result result;
        if (java) {
            command.addAll(List.of(javaImage, "sh", "-c", "cat > /tmp/Main.java && exec java /tmp/Main.java"));
            String in = javaSource(mode, code) + "\n//IN:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)) + "\n";
            result = exec(command, in);
        } else {
            command.addAll(List.of(pythonImage, "python", "-c", PYTHON_WRAPPER));
            result = exec(command, json + "\n" + code);
        }

        int marker = result.output().lastIndexOf(MARKER);
        if (result.exitCode() != 0 || marker < 0) {
            String hint = java ? "(your code starts at line " + javaCodeLine(mode, code) + " of Main.java)\n" : "";
            throw new IllegalArgumentException((java ? "java" : "python") + " failed (exit " + result.exitCode() + "):\n" + hint + result.output());
        }
        JsonNode out = mapper.readTree(result.output().substring(marker + MARKER.length()).trim());
        List<Map<String, String>> runs = mapper.convertValue(out.path("runs"), new TypeReference<List<Map<String, String>>>() {});
        Map<String, String> set = mapper.convertValue(out.path("set"), new TypeReference<LinkedHashMap<String, String>>() {});
        return new Script(runs, set, out.path("passed").asBoolean(false), result.output().substring(0, marker).strip());
    }

    // The java script inside the wrapper: its leading import lines above the class, the rest in check() or in main
    static String javaSource(Mode mode, String code) {
        List<String> lines = code.lines().toList();
        int split = javaImportCount(lines);
        String body = String.join("\n", lines.subList(split, lines.size()));
        return JAVA_WRAPPER
                .replace("//IMPORTS", String.join("\n", lines.subList(0, split)))
                .replace("//CHECK", mode == Mode.CHECK ? checkBody(body) : "return false;")
                .replace("//CODE", mode == Mode.CHECK ? "" : body);
    }

    // A single line without "return" is the expression to return: status == 200 -> return (status == 200);
    static String checkBody(String body) {
        List<String> lines = body.lines().filter(l -> !l.isBlank() && !l.trim().startsWith("//")).toList();
        if (lines.isEmpty()) {
            return "return false;";
        }
        String only = lines.getFirst().trim();
        if (lines.size() == 1 && !only.matches("return\\b.*")) {
            return "return (" + only.replaceAll(";\\s*$", "") + ");";
        }
        return body;
    }

    private static int javaImportCount(List<String> lines) {
        int split = 0;
        while (split < lines.size() && (lines.get(split).isBlank() || lines.get(split).trim().startsWith("import "))) {
            split++;
        }
        return split;
    }

    // Where the script's first non-import line ends up in Main.java, to read compiler errors by
    private static int javaCodeLine(Mode mode, String code) {
        List<String> lines = code.lines().toList();
        String placeholder = mode == Mode.CHECK ? "//CHECK" : "//CODE";
        String before = JAVA_WRAPPER.substring(0, JAVA_WRAPPER.indexOf(placeholder))
                .replace("//IMPORTS", String.join("\n", lines.subList(0, javaImportCount(lines))))
                .replace("//CHECK", "return false;");
        return (int) before.lines().count() + 1;
    }

    /** What a query card's run gave: its exit code, what to show, and its result as JSON text when the type has one. */
    record Ran(int exitCode, String output, String json) {}

    /**
     * Runs a query card's text once per run, with the client of the config's type (see DatasourceConfig), in a
     * one-shot container. Exit code 0 means every statement succeeded; each client stops at the first error.
     */
    public Ran run(DatasourceConfig c, String schema, List<QueryTemplate.Run> runs) throws IOException, InterruptedException {
        return switch (c.getType()) {
            case "mysql" -> runMysql(c, schema, runs);
            case "mongodb" -> runMongo(c, runs);
            case "redis" -> runRedis(c, runs);
            default -> runPostgres(c, schema, runs);
        };
    }

    // "localhost" must mean this machine, not the container: the host network, or with LOCALHOST_ALIAS (the client
    // itself in a container, e.g. on Docker Desktop, where the host network isn't the machine) that name for it
    private static List<String> clientCommand(DatasourceConfig c, String kind) {
        List<String> command = new ArrayList<>(List.of("docker", "run", "--rm", "-i"));
        command.addAll(LocalhostAlias.isSet() ? List.of("--add-host", "host.docker.internal:host-gateway") : List.of("--network=host"));
        command.addAll(List.of("--name", c.getContainerName() + "-" + kind + "-" + UUID.randomUUID().toString().substring(0, 8)));
        return command;
    }

    private static String header(List<String> command, String secret, int runs, int exitCode) {
        String shown = String.join(" ", command);
        if (secret != null && !secret.isEmpty()) shown = shown.replace(secret, "****");
        shown = shown.replaceAll("(PGPASSWORD|MYSQL_PWD|REDISCLI_AUTH)=\\S*", "$1=****");
        return "$ " + shown + " ... (" + runs + " run" + (runs == 1 ? "" : "s") + ")\n(exit " + exitCode + ")\n\n";
    }

    private static String label(int i, int count, QueryTemplate.Run run) {
        return "-- run " + (i + 1) + "/" + count + " " + run.label();
    }

    private Ran runPostgres(DatasourceConfig c, String schema, List<QueryTemplate.Run> runs) throws IOException, InterruptedException {
        List<String> command = clientCommand(c, "psql");
        command.addAll(List.of(
                "-e", "PGHOST=" + LocalhostAlias.host(c.host()),
                "-e", "PGPORT=" + c.port(),
                "-e", "PGDATABASE=" + c.database(),
                "-e", "PGUSER=" + c.getUsername(),
                "-e", "PGPASSWORD=" + c.getPassword(),
                "-e", "PGOPTIONS=-c search_path=" + schema,
                c.clientImage(), "psql", "-v", "ON_ERROR_STOP=1"));
        List<String> shownCommand = List.copyOf(command);
        for (int i = 0; i < runs.size(); i++) {
            if (runs.size() > 1) {
                command.addAll(List.of("-c", "\\echo '" + label(i, runs.size(), runs.get(i)).replace("'", "") + "'"));
            }
            command.addAll(List.of("-c", runs.get(i).sql()));
        }
        Result result = exec(command, null);
        return new Ran(result.exitCode(), header(shownCommand, null, runs.size(), result.exitCode()) + result.output(), null);
    }

    // MySQL and MariaDB: the statements on stdin, tables out; the schema field picks another database than the url's
    private Ran runMysql(DatasourceConfig c, String schema, List<QueryTemplate.Run> runs) throws IOException, InterruptedException {
        String database = schema == null || schema.isBlank() ? c.database() : schema.trim();
        List<String> command = clientCommand(c, "mysql");
        command.addAll(List.of("-e", "MYSQL_PWD=" + c.getPassword(), c.clientImage(),
                "mysql", "--protocol=TCP", "-h", LocalhostAlias.host(c.host()), "-P", c.port(), "-u", c.getUsername(), "--table"));
        if (!database.isEmpty()) command.add(database);
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            if (runs.size() > 1) {
                script.append("SELECT '").append(label(i, runs.size(), runs.get(i)).replace("'", "")).append("' AS `run`;\n");
            }
            String sql = runs.get(i).sql().strip();
            script.append(sql).append(sql.endsWith(";") ? "" : ";").append('\n');
        }
        Result result = exec(command, script.toString());
        return new Ran(result.exitCode(), header(command, null, runs.size(), result.exitCode()) + result.output(), null);
    }

    // MongoDB: each run's text is a mongosh expression (or statements, the last one's value), printed as JSON
    private Ran runMongo(DatasourceConfig c, List<QueryTemplate.Run> runs) throws IOException, InterruptedException {
        String credentials = c.getUsername() == null || c.getUsername().isBlank() ? ""
                : encode(c.getUsername()) + ":" + encode(c.getPassword()) + "@";
        String query = !c.urlQuery().isEmpty() ? "?" + c.urlQuery() : credentials.isEmpty() ? "" : "?authSource=admin";
        String uri = "mongodb://" + credentials + LocalhostAlias.host(c.host()) + ":" + c.port() + "/" + c.database() + query;
        StringBuilder out = new StringBuilder();
        String json = null;
        int exitCode = 0;
        List<String> shown = null;
        for (int i = 0; i < runs.size() && exitCode == 0; i++) {
            List<String> command = clientCommand(c, "mongo");
            command.addAll(List.of(c.clientImage(), "mongosh", uri, "--quiet", "--json=relaxed", "--eval", runs.get(i).sql()));
            if (shown == null) shown = command.subList(0, command.size() - 1);
            Result result = exec(command, null);
            exitCode = result.exitCode();
            if (runs.size() > 1) out.append(label(i, runs.size(), runs.get(i))).append('\n');
            out.append(result.output().strip()).append('\n');
            json = runs.size() == 1 && exitCode == 0 ? result.output().strip() : null;
        }
        String secret = credentials.isEmpty() ? null : encode(c.getPassword());
        return new Ran(exitCode, header(shown == null ? List.of("mongosh") : shown, secret, runs.size(), exitCode) + out, json);
    }

    // Redis: one command per line, each run's after the other's; the replies, one per line, also as a JSON list
    private Ran runRedis(DatasourceConfig c, List<QueryTemplate.Run> runs) throws IOException, InterruptedException {
        List<String> command = clientCommand(c, "redis");
        if (c.getPassword() != null && !c.getPassword().isEmpty()) command.addAll(List.of("-e", "REDISCLI_AUTH=" + c.getPassword()));
        command.addAll(List.of(c.clientImage(), "redis-cli", "-h", LocalhostAlias.host(c.host()), "-p", c.port(), "-n", c.database()));
        if (c.getUsername() != null && !c.getUsername().isBlank() && !"default".equals(c.getUsername().trim())) {
            command.addAll(List.of("--user", c.getUsername().trim()));
        }
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            if (runs.size() > 1) script.append("ECHO \"").append(label(i, runs.size(), runs.get(i)).replace("\"", "")).append("\"\n");
            runs.get(i).sql().lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(line -> script.append(line).append('\n'));
        }
        Result result = exec(command, script.toString());
        // redis-cli exits 0 even when a command failed; a reply like "ERR ..." or "WRONGTYPE ..." counts as a failure
        boolean failed = result.output().lines().anyMatch(line -> line.matches("(\\(error\\) )?(ERR|WRONGTYPE|NOAUTH|WRONGPASS|NOPERM)\\b.*"));
        int exitCode = result.exitCode() != 0 ? result.exitCode() : failed ? 1 : 0;
        List<String> replies = result.output().lines().toList();
        return new Ran(exitCode, header(command, null, runs.size(), exitCode) + result.output(), mapper.writeValueAsString(replies));
    }

    private static String encode(String text) {
        return java.net.URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private Result exec(List<String> command, String stdin) throws IOException, InterruptedException {
        // Arguments are passed as a list, never through a shell, so nothing typed can break out
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        // Read output while it runs, otherwise a big result fills the pipe and the process blocks
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Failed to read output: " + e.getMessage();
            }
        });

        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("timed out after " + TIMEOUT_SECONDS + "s");
        }
        return new Result(process.exitValue(), output.join());
    }

    // Suffix so two runs never clash on the same container name
    private String uniqueName(DatasourceConfig c, String kind) {
        return uniqueName(c.getContainerName(), kind);
    }

    private String uniqueName(String containerName, String kind) {
        return containerName + "-" + kind + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
