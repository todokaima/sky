package com.example.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The headers sent with every request of the HTTP cards linked to this config. The common ones are fields, as in the
 * DB configs (Accept, Content-Type and the bearer token: paste just the JWT); anything else goes in the free text,
 * one "Name: value" per line. A filled-in field wins over the same header in the free text.
 */
public class HttpConfig {

    private String accept = "application/json";
    private String contentType = "application/json";
    // Just the token; sent as "Authorization: Bearer <jwt>". Spaces, line breaks, quotes and a pasted "Bearer " go
    private String jwt = "";
    // Any other headers, one "Name: value" per line ("#" comments them out)
    private String headers = "";
    // Used by HTTP cards that aren't linked to a config
    private boolean defaultConfig;

    public Map<String, String> headerMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : headers.split("\\R")) {
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("header line must look like 'Name: value', got: " + line);
            }
            map.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        putField(map, "Accept", accept);
        putField(map, "Content-Type", contentType);
        String token = cleanJwt(jwt);
        if (!token.isEmpty()) putField(map, "Authorization", "Bearer " + token);
        return map;
    }

    // A filled-in field replaces the same header from the free text, whatever its case
    private static void putField(Map<String, String> map, String name, String value) {
        if (value == null || value.isBlank()) return;
        map.keySet().removeIf(name::equalsIgnoreCase);
        map.put(name, value.trim());
    }

    /** "Bearer eyJ...\n  .abc" -> "eyJ....abc": only the token, whatever came along with the paste. */
    public static String cleanJwt(String pasted) {
        if (pasted == null) return "";
        String token = pasted.strip().replaceAll("^\"|\"$", "").strip();
        if (token.regionMatches(true, 0, "Bearer", 0, 6)) token = token.substring(6);
        return token.replaceAll("[\\s\"]", "");
    }

    /**
     * Configs saved before the fields existed keep everything in the free text: Accept, Content-Type and an
     * "Authorization: Bearer ..." line move into their fields, the rest stays.
     */
    public void liftFormedHeaders() {
        List<String> kept = new ArrayList<>();
        for (String line : headers.split("\\R", -1)) {
            String trimmed = line.trim();
            int colon = trimmed.indexOf(':');
            String name = colon > 0 && !trimmed.startsWith("#") ? trimmed.substring(0, colon).trim() : "";
            String value = colon > 0 ? trimmed.substring(colon + 1).trim() : "";
            if (name.equalsIgnoreCase("Accept")) accept = value;
            else if (name.equalsIgnoreCase("Content-Type")) contentType = value;
            else if (name.equalsIgnoreCase("Authorization") && value.regionMatches(true, 0, "Bearer ", 0, 7)) jwt = cleanJwt(value);
            else kept.add(line);
        }
        headers = String.join("\n", kept).strip();
    }

    public String getAccept() { return accept; }
    public void setAccept(String accept) { this.accept = accept == null ? "" : accept; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType == null ? "" : contentType; }

    public String getJwt() { return jwt; }
    public void setJwt(String jwt) { this.jwt = cleanJwt(jwt); }

    public String getHeaders() { return headers; }
    public void setHeaders(String headers) { this.headers = headers == null ? "" : headers; }

    public boolean isDefaultConfig() { return defaultConfig; }
    public void setDefaultConfig(boolean defaultConfig) { this.defaultConfig = defaultConfig; }
}
