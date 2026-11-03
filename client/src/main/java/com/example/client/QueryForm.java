package com.example.client;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything typed on the main page: the HTTP request cards (requests[i].*), the python + query
 * cards (blocks[i].*), the if diamonds (decisions[i].*) and the Kafka cards (subscribers[i].*). Cards link to each other through "next",
 * a comma-separated list of node ids: "h0", "h1", ... for requests, "q0", "q1", ... for queries and
 * "d0", "d1", ... for diamonds, "k0", "k1", ... for Kafka cards, "" for none. "when" holds the optional condition of each link,
 * as JSON: {"q1": "status == 200"}.
 */
public class QueryForm implements Serializable {

    private List<Request> requests = new ArrayList<>(List.of(new Request()));
    private List<Block> blocks = new ArrayList<>(List.of(new Block()));
    private List<Decision> decisions = new ArrayList<>();
    private List<Subscriber> subscribers = new ArrayList<>();
    // Most recent successful values extracted by HTTP cards, available to direct query runs.
    private Map<String, String> extractedVariables = new LinkedHashMap<>();
    // HTTP configs travel with a request run so the displayed headers are what gets sent.
    private List<HttpConfig> httpConfigs = new ArrayList<>();

    public List<Request> getRequests() { return requests; }
    public void setRequests(List<Request> requests) { this.requests = requests; }

    public List<Block> getBlocks() { return blocks; }
    public void setBlocks(List<Block> blocks) { this.blocks = blocks; }

    public List<Decision> getDecisions() { return decisions; }
    public void setDecisions(List<Decision> decisions) { this.decisions = decisions; }
    public List<Subscriber> getSubscribers() { return subscribers; }
    public void setSubscribers(List<Subscriber> subscribers) { this.subscribers = subscribers; }

    public Map<String, String> getExtractedVariables() { return extractedVariables; }
    public void setExtractedVariables(Map<String, String> extractedVariables) { this.extractedVariables = extractedVariables; }

    public List<HttpConfig> getHttpConfigs() { return httpConfigs; }
    public void setHttpConfigs(List<HttpConfig> httpConfigs) { this.httpConfigs = httpConfigs; }

    public static class Request implements Serializable {

        private String method = "GET";
        private String url = "";
        private String body = "";
        private String output = "";
        private String extractor = "";
        private String extractorOutput = "";
        private boolean responseHidden;
        private String next = "";
        private String when = "";
        // Position and size on the board: "x,y,width,height" (width/height empty = default)
        private String layout = "";
        // Index of the linked HTTP config, -1 = use the default one
        private int config = -1;

        public int getConfig() { return config; }
        public void setConfig(int config) { this.config = config; }

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }

        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }

        public String getExtractor() { return extractor; }
        public void setExtractor(String extractor) { this.extractor = extractor; }

        public String getExtractorOutput() { return extractorOutput; }
        public void setExtractorOutput(String extractorOutput) { this.extractorOutput = extractorOutput; }

        public boolean isResponseHidden() { return responseHidden; }
        public void setResponseHidden(boolean responseHidden) { this.responseHidden = responseHidden; }

        public String getNext() { return next; }
        public void setNext(String next) { this.next = next; }

        public String getWhen() { return when; }
        public void setWhen(String when) { this.when = when; }

        public String getLayout() { return layout; }
        public void setLayout(String layout) { this.layout = layout; }
    }

    public static class Block implements Serializable {

        // Language of the vars script: "python" or "java"
        private String language = "python";
        private String vars = "";
        private String query = "";
        // Optional, falls back to the schema in the datasource config
        private String schema = "";
        private String output = "";
        // Python box collapsed in the UI
        private boolean pyHidden;
        private String next = "";
        private String when = "";
        // Position and size on the board: "x,y,width,height" (width/height empty = default)
        private String layout = "";
        // Index of the linked datasource config, -1 = use the default one
        private int config = -1;

        public int getConfig() { return config; }
        public void setConfig(int config) { this.config = config; }

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }

        public String getVars() { return vars; }
        public void setVars(String vars) { this.vars = vars; }

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }

        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }

        public boolean isPyHidden() { return pyHidden; }
        public void setPyHidden(boolean pyHidden) { this.pyHidden = pyHidden; }

        public String getNext() { return next; }
        public void setNext(String next) { this.next = next; }

        public String getWhen() { return when; }
        public void setWhen(String when) { this.when = when; }

        public String getLayout() { return layout; }
        public void setLayout(String layout) { this.layout = layout; }

        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }

    }

    /**
     * An if diamond: checks the result of the card before it, then runs its "next" cards when the
     * check is true and its "nextElse" cards when it's false.
     */
    public static class Decision implements Serializable {

        // "condition": an expression checked in Java (see Condition), "python": a script that returns True/False
        private String mode = "condition";
        private String condition = "";
        private String output = "";
        private String next = "";
        private String nextElse = "";
        private String when = "";
        // Position and size on the board: "x,y,width,height" (width/height empty = default)
        private String layout = "";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }

        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }

        public String getNext() { return next; }
        public void setNext(String next) { this.next = next; }

        public String getNextElse() { return nextElse; }
        public void setNextElse(String nextElse) { this.nextElse = nextElse; }

        public String getWhen() { return when; }
        public void setWhen(String when) { this.when = when; }

        public String getLayout() { return layout; }
        public void setLayout(String layout) { this.layout = layout; }
    }

    /**
     * A Kafka card: reads one message of its topic into its box (the newest one, or the next one to
     * arrive), then takes a value out of it by JSON path and runs its java script on it. Both pass
     * variables on to the next cards, like an HTTP card's JSON path.
     */
    public static class Subscriber implements Serializable {
        private String topic = "";
        // "latest": the newest message on the topic, "next": wait for the next one to arrive
        private String mode = "latest";
        // Seconds; empty = 30
        private Integer timeout = 30;
        // Where the message came from (partition, offset, time, key, headers), or why there's none
        private String meta = "";
        // The message's value: the box the JSON path and the java script read
        private String output = "";
        private String extractor = "";
        private String extractorOutput = "";
        private String script = "";
        // "java" or "python"
        private String scriptLanguage = "java";
        private boolean scriptHidden;
        private String scriptOutput = "";
        private String next = "";
        private String when = "";
        // Position and size on the board: "x,y,width,height" (width/height empty = default)
        private String layout = "";
        // Index of the linked Kafka config, -1 = use the default one
        private int config = -1;

        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public Integer getTimeout() { return timeout; }
        public void setTimeout(Integer timeout) { this.timeout = timeout; }
        public String getMeta() { return meta; }
        public void setMeta(String meta) { this.meta = meta; }
        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }
        public String getExtractor() { return extractor; }
        public void setExtractor(String extractor) { this.extractor = extractor; }
        public String getExtractorOutput() { return extractorOutput; }
        public void setExtractorOutput(String extractorOutput) { this.extractorOutput = extractorOutput; }
        public String getScript() { return script; }
        public void setScript(String script) { this.script = script; }
        public String getScriptLanguage() { return scriptLanguage; }
        public void setScriptLanguage(String scriptLanguage) { this.scriptLanguage = "python".equals(scriptLanguage) ? "python" : "java"; }
        public boolean isScriptHidden() { return scriptHidden; }
        public void setScriptHidden(boolean scriptHidden) { this.scriptHidden = scriptHidden; }
        public String getScriptOutput() { return scriptOutput; }
        public void setScriptOutput(String scriptOutput) { this.scriptOutput = scriptOutput; }
        public String getNext() { return next; }
        public void setNext(String next) { this.next = next; }
        public String getWhen() { return when; }
        public void setWhen(String when) { this.when = when; }
        public String getLayout() { return layout; }
        public void setLayout(String layout) { this.layout = layout; }
        public int getConfig() { return config; }
        public void setConfig(int config) { this.config = config; }
    }
}
