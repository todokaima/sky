package com.example.client;

import java.net.URI;
import java.util.Map;

/**
 * A database the query cards run against. type picks the client that runs a card's text in a one-shot container:
 *   postgres  SQL with psql         url jdbc:postgresql://host:5432/database   (schema: the search_path)
 *   mysql     SQL with mysql        url jdbc:mysql://host:3306/database        (MySQL and MariaDB; schema: the database)
 *   mongodb   mongosh JavaScript    url mongodb://host:27017/database          (the result is JSON, for the next cards)
 *   redis     redis-cli commands    url redis://host:6379/0                    (one command per line; 0: the db number)
 */
public class DatasourceConfig {

    public static final Map<String, String> CLIENT_IMAGES = Map.of(
            "postgres", "postgres:alpine", "mysql", "mysql:8.4", "mongodb", "mongo:7", "redis", "redis:7-alpine");
    private static final Map<String, Integer> DEFAULT_PORTS = Map.of("postgres", 5432, "mysql", 3306, "mongodb", 27017, "redis", 6379);
    private static final Map<String, String> URL_SHAPES = Map.of(
            "postgres", "jdbc:postgresql://host:port/database", "mysql", "jdbc:mysql://host:port/database",
            "mongodb", "mongodb://host:port/database", "redis", "redis://host:port/0");

    private String type = "postgres";
    private String containerName = "db-script-runner";
    // The client's image; empty (or another type's default) means this type's: postgres:alpine, mysql:8.4, ...
    private String image = "postgres:alpine";
    private String pythonImage = "python:3-alpine";
    // Needs a JDK: scripts are compiled on the fly (java Main.java)
    private String javaImage = "eclipse-temurin:21-jdk-alpine";

    private String url = "jdbc:postgresql://localhost:5432/irm_db";
    private String schema = "irm_uiconfiguration_db";
    private String username = "postgres";
    private String password = "postgres";
    private String driver = "org.postgresql.Driver";
    // Used by query cards that aren't linked to a config
    private boolean defaultConfig;

    // host, port and database are read from the url, e.g. jdbc:postgresql://localhost:2345/testdb
    private URI parsedUrl() {
        String text = url == null ? "" : url.trim().replaceFirst("^jdbc:", "");
        String scheme = text.contains("://") ? text.substring(0, text.indexOf("://")) : "";
        boolean fits = switch (type) {
            case "mysql" -> scheme.equals("mysql") || scheme.equals("mariadb");
            case "mongodb" -> scheme.equals("mongodb");
            case "redis" -> scheme.equals("redis") || scheme.equals("rediss");
            default -> scheme.equals("postgresql") || scheme.equals("postgres");
        };
        if (!fits) {
            throw new IllegalArgumentException("a " + type + " config's url must look like " + URL_SHAPES.get(type) + ", got: " + url);
        }
        return URI.create(text);
    }

    public String host() {
        return parsedUrl().getHost();
    }

    public String port() {
        int port = parsedUrl().getPort();
        return String.valueOf(port == -1 ? DEFAULT_PORTS.get(type) : port);
    }

    public String database() {
        String path = parsedUrl().getPath() == null ? "" : parsedUrl().getPath().replaceFirst("^/", "");
        return path.isEmpty() && "redis".equals(type) ? "0" : path;
    }

    /** The url's query, e.g. "authSource=admin" for MongoDB, or "". */
    public String urlQuery() {
        String query = parsedUrl().getRawQuery();
        return query == null ? "" : query;
    }

    /** The image the client runs in: the one set, unless it's empty or another type's default. */
    public String clientImage() {
        boolean otherDefault = CLIENT_IMAGES.entrySet().stream().anyMatch(e -> !e.getKey().equals(type) && e.getValue().equals(image));
        return image == null || image.isBlank() || otherDefault ? CLIENT_IMAGES.get(type) : image.trim();
    }

    public String toYaml() {
        return String.join("\n",
                "container:",
                "  name: " + containerName,
                "  image: " + image,
                "  python-image: " + pythonImage,
                "  java-image: " + javaImage,
                "spring:",
                "  datasource:",
                "    url: " + url + "?currentSchema=" + schema,
                "    username: " + username,
                "    password: " + password,
                "    driver-class-name: " + driver);
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type != null && CLIENT_IMAGES.containsKey(type) ? type : "postgres"; }

    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getPythonImage() { return pythonImage; }
    public void setPythonImage(String pythonImage) { this.pythonImage = pythonImage; }

    public String getJavaImage() { return javaImage; }
    public void setJavaImage(String javaImage) { this.javaImage = javaImage; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getDriver() { return driver; }
    public void setDriver(String driver) { this.driver = driver; }

    public boolean isDefaultConfig() { return defaultConfig; }
    public void setDefaultConfig(boolean defaultConfig) { this.defaultConfig = defaultConfig; }
}
