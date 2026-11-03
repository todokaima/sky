package com.example.client;

import java.util.regex.Pattern;

/**
 * What "localhost" in a card means. Unset (the default, and when run from the IDE): this machine, as written. In a
 * container "localhost" is the container itself, so there LOCALHOST_ALIAS=host.docker.internal makes the HTTP cards,
 * the DB configs and the Kafka configs reach the machine instead, the same addresses as in the browser.
 */
public final class LocalhostAlias {

    private static final String ALIAS = System.getenv().getOrDefault("LOCALHOST_ALIAS", "").trim();
    // "localhost" or "127.0.0.1" as a host: after "//" or at the start, and followed by ":", "/", "," or the end
    private static final Pattern LOCALHOST = Pattern.compile("(^|//|,)\\s*(localhost|127\\.0\\.0\\.1)(?=[:/,]|$)");

    private LocalhostAlias() {}

    public static boolean isSet() {
        return !ALIAS.isEmpty();
    }

    /** "localhost" -> "host.docker.internal" when set, anything else as it is. */
    public static String host(String host) {
        boolean isLocal = host != null && (host.trim().equalsIgnoreCase("localhost") || host.trim().equals("127.0.0.1"));
        return isSet() && isLocal ? ALIAS : host;
    }

    /** http://localhost:8081/orders, localhost:9092,localhost:9093 -> the same with the alias, when set. */
    public static String replaceIn(String text) {
        return isSet() && text != null ? LOCALHOST.matcher(text).replaceAll("$1" + ALIAS) : text;
    }
}
