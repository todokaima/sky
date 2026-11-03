package com.example.client;

import java.util.LinkedHashMap;
import java.util.Map;

/** A Kafka cluster that Kafka cards read from (read only, see KafkaRunner). */
public class KafkaConfig {

    // host:port of one or more brokers, comma-separated
    private String bootstrapServers = "localhost:9092";
    // One "key=value" per line, passed to the consumer as they are: security, SASL, SSL, timeouts...
    private String properties = """
            security.protocol=PLAINTEXT
            # security.protocol=SASL_SSL
            # sasl.mechanism=PLAIN
            # sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="user" password="secret";""";
    // The cards' scripts run in one-shot containers of these images (java Main.java / python)
    private String containerName = "kafka-script-runner";
    private String javaImage = "eclipse-temurin:21-jdk-alpine";
    private String pythonImage = "python:3-alpine";
    // Used by Kafka cards that aren't linked to a config
    private boolean defaultConfig;

    public Map<String, String> propertyMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : properties.split("\\R")) {
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("property line must look like 'name=value', got: " + line);
            }
            map.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
        }
        return map;
    }

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
    public String getProperties() { return properties; }
    public void setProperties(String properties) { this.properties = properties; }
    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }
    public String getJavaImage() { return javaImage; }
    public void setJavaImage(String javaImage) { this.javaImage = javaImage; }
    public String getPythonImage() { return pythonImage; }
    public void setPythonImage(String pythonImage) { this.pythonImage = pythonImage; }
    public boolean isDefaultConfig() { return defaultConfig; }
    public void setDefaultConfig(boolean defaultConfig) { this.defaultConfig = defaultConfig; }
}
