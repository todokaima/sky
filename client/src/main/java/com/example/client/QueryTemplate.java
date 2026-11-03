package com.example.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands $(varName) in a query, once for each run(...) call of the python script.
 */
public final class QueryTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\((\\w+)\\)");

    public record Run(String label, String sql) {}

    private QueryTemplate() {}

    public static List<Run> expand(List<Map<String, String>> runs, String query) {
        List<Run> result = new ArrayList<>();
        for (Map<String, String> values : runs) {
            result.add(new Run(values.toString(), substitute(query, values)));
        }
        return result;
    }

    /** $(varName) in any text from these values, e.g. a Kafka card's topic from the cards before it. */
    public static String fill(String text, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("unknown variable $(" + m.group(1) + "), the known ones are " + values.keySet());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String substitute(String query, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(query);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("unknown variable $(" + m.group(1) + "), run(...) was called with " + values.keySet());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
