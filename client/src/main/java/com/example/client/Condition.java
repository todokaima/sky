package com.example.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * A small condition language, checked in Java against the previous card's result. Used by the
 * diamond cards and by conditions on links. Examples:
 * <pre>
 *   status == 200 && data.state == "done"
 *   items.length > 0 || exists data.id
 *   body contains "ok" and not (status >= 500)
 *   vars.token matches "^ey"
 * </pre>
 * Names: the previous result's own fields (status, ok, body, output, exit, json; a Kafka card's topic, key, offset,
 * partition, timestamp, headers), vars.x (or just x)
 * are variables from earlier extractors, anything else is a path into the JSON response body
 * ("data.items[0].id", "items.length").
 */
public final class Condition {

    private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "exists", "contains", "matches", "true", "false", "null");
    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(?:(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')" +        // 1: string
            "|(-?\\d+(?:\\.\\d+)?)" +                                          // 2: number
            "|(==|!=|>=|<=|&&|\\|\\||[<>!()])" +                               // 3: operator
            "|([A-Za-z_$][\\w$]*(?:\\.[\\w$]+|\\[\\d+])*))");                  // 4: name or path

    private final List<String> tokens = new ArrayList<>();
    private final List<Integer> kinds = new ArrayList<>();
    private final ObjectNode prev;
    private final Map<String, String> vars;
    private int position;

    private Condition(String expression, ObjectNode prev, Map<String, String> vars) {
        this.prev = prev != null ? prev : JsonNodeFactory.instance.objectNode();
        this.vars = vars;
        Matcher m = TOKEN.matcher(expression);
        int at = 0;
        while (at < expression.length()) {
            if (expression.substring(at).isBlank()) {
                break;
            }
            if (!m.find(at) || m.start() != at) {
                throw new IllegalArgumentException("can't read the condition at \"" + expression.substring(at).trim() + "\"");
            }
            for (int group = 1; group <= 4; group++) {
                if (m.group(group) != null) {
                    tokens.add(m.group(group));
                    kinds.add(group);
                }
            }
            at = m.end();
        }
    }

    /** True if the condition holds for this previous result; IllegalArgumentException if it can't be read. */
    public static boolean test(String expression, ObjectNode prev, Map<String, String> vars) {
        Condition condition = new Condition(expression, prev, vars);
        if (condition.tokens.isEmpty()) {
            return true;
        }
        boolean result = condition.or();
        if (condition.position < condition.tokens.size()) {
            throw new IllegalArgumentException("unexpected \"" + condition.tokens.get(condition.position) + "\" in the condition");
        }
        return result;
    }

    private boolean or() {
        boolean result = and();
        while (accept("||") || accept("or")) {
            boolean right = and();
            result = result || right;
        }
        return result;
    }

    private boolean and() {
        boolean result = unary();
        while (accept("&&") || accept("and")) {
            boolean right = unary();
            result = result && right;
        }
        return result;
    }

    private boolean unary() {
        if (accept("!") || accept("not")) {
            return !unary();
        }
        if (accept("exists")) {
            JsonNode value = value();
            return !value.isMissingNode() && !value.isNull();
        }
        if (accept("(")) {
            boolean result = or();
            expect(")");
            return result;
        }
        return comparison();
    }

    private boolean comparison() {
        JsonNode left = value();
        String op = peek();
        if (op == null || !Set.of("==", "!=", ">", ">=", "<", "<=", "contains", "matches").contains(op)) {
            return truthy(left);
        }
        position++;
        JsonNode right = value();
        return switch (op) {
            case "==" -> same(left, right);
            case "!=" -> !same(left, right);
            case "contains" -> contains(left, right);
            case "matches" -> Pattern.compile(text(right)).matcher(text(left)).find();
            default -> {
                int c = compare(left, right);
                yield switch (op) {
                    case ">" -> c > 0;
                    case ">=" -> c >= 0;
                    case "<" -> c < 0;
                    default -> c <= 0;
                };
            }
        };
    }

    private JsonNode value() {
        if (position >= tokens.size()) {
            throw new IllegalArgumentException("the condition ends too early");
        }
        String token = tokens.get(position);
        int kind = kinds.get(position);
        position++;
        return switch (kind) {
            case 1 -> TextNode.valueOf(unquote(token));
            case 2 -> token.contains(".") ? DoubleNode.valueOf(Double.parseDouble(token)) : IntNode.valueOf(Integer.parseInt(token));
            case 4 -> switch (token) {
                case "true" -> BooleanNode.TRUE;
                case "false" -> BooleanNode.FALSE;
                case "null" -> NullNode.instance;
                default -> {
                    if (KEYWORDS.contains(token)) {
                        throw new IllegalArgumentException("expected a value, got \"" + token + "\"");
                    }
                    yield resolve(token);
                }
            };
            default -> throw new IllegalArgumentException("expected a value, got \"" + token + "\"");
        };
    }

    // status / vars.token / token / data.items[0].id
    private JsonNode resolve(String path) {
        List<String> segments = new ArrayList<>(List.of(path.replaceAll("\\[(\\d+)]", ".$1").split("\\.")));
        String first = segments.getFirst();
        JsonNode node;
        if (first.equals("vars") && segments.size() > 1) {
            segments.removeFirst();
            String value = vars.get(segments.removeFirst());
            node = value == null ? MissingNode.getInstance() : TextNode.valueOf(value);
        } else if (prev.has(first)) {
            node = prev.get(segments.removeFirst());
        } else if (!prev.path("json").has(first) && vars.containsKey(first)) {
            node = TextNode.valueOf(vars.get(segments.removeFirst()));
        } else {
            node = prev.path("json");
        }
        for (String segment : segments) {
            if (segment.equals("length") && !node.has("length")) {
                node = IntNode.valueOf(node.isTextual() ? node.asText().length() : node.size());
            } else if (node.isArray() && segment.matches("\\d+")) {
                node = node.path(Integer.parseInt(segment));
            } else {
                node = node.path(segment);
            }
        }
        return node;
    }

    private static boolean truthy(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return false;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return value.asDouble() != 0;
        if (value.isTextual()) return !value.asText().isEmpty() && !value.asText().equalsIgnoreCase("false");
        return value.size() > 0;
    }

    private static boolean same(JsonNode a, JsonNode b) {
        boolean aNone = a.isMissingNode() || a.isNull(), bNone = b.isMissingNode() || b.isNull();
        if (aNone || bNone) return aNone && bNone;
        Double x = number(a), y = number(b);
        if (x != null && y != null) return x.doubleValue() == y.doubleValue();
        return text(a).equals(text(b));
    }

    private static int compare(JsonNode a, JsonNode b) {
        Double x = number(a), y = number(b);
        return x != null && y != null ? Double.compare(x, y) : text(a).compareTo(text(b));
    }

    private static boolean contains(JsonNode a, JsonNode b) {
        if (a.isArray()) {
            for (JsonNode item : a) {
                if (same(item, b)) return true;
            }
            return false;
        }
        return text(a).contains(text(b));
    }

    private static Double number(JsonNode value) {
        if (value.isNumber()) return value.asDouble();
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return "";
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static String unquote(String token) {
        return token.substring(1, token.length() - 1).replaceAll("\\\\(.)", "$1");
    }

    private String peek() {
        return position < tokens.size() ? tokens.get(position) : null;
    }

    private boolean accept(String token) {
        if (token.equals(peek()) && (kinds.get(position) != 1)) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(String token) {
        if (!accept(token)) {
            throw new IllegalArgumentException("expected \"" + token + "\" in the condition");
        }
    }
}
