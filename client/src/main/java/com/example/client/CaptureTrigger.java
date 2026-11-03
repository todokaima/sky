package com.example.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tells scapy-netgraph (../scapy-netgraph) to capture while a chain runs: Send, Run and Consume start a capture before
 * it (named after the action, e.g. "Send on HTTP 1") and stop it a second after it ends, so NetCanvas draws what that
 * action caused, and only that. The chain runs anyway when the sniffer isn't up.
 */
@Component
public class CaptureTrigger {

    private static final Logger log = LoggerFactory.getLogger(CaptureTrigger.class);
    // It reads the services' /v3/api-docs first, up to 3s each
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final String captureUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private String lastError;

    public CaptureTrigger(@Value("${client.capture-url}") String captureUrl) {
        this.captureUrl = LocalhostAlias.replaceIn(captureUrl.trim());
    }

    // Responses that are still on their way when the chain ends get a second to arrive
    private static final String STOP_BODY = "{\"graceSeconds\": 1}";

    /** A capture named after what started it, e.g. "Send on HTTP 1". */
    public void startCapture(String by) {
        post(captureUrl, "{\"by\": \"" + by.replaceAll("[\"\\\\]", "") + "\"}");
    }

    public void stopCapture() {
        post(captureUrl.replaceAll("/+$", "") + "/stop", STOP_BODY);
    }

    private void post(String url, String body) {
        if (captureUrl.isEmpty() || captureUrl.equals("none")) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            lastError = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Said once, not on every Send, while it stays down
            String error = e.toString();   // a refused connection has no message
            if (!error.equals(lastError)) {
                log.warn("No capture: {} ({})", url, error);
            }
            lastError = error;
        }
    }
}
