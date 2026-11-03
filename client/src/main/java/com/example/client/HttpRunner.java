package com.example.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Sends the request of an HTTP card, server side, so the browser's CORS rules don't apply. */
@Component
public class HttpRunner {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    record Response(int status, String output, String body) {}

    public Response send(Map<String, String> headers, String method, String url, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(LocalhostAlias.replaceIn(url.trim())))
                .timeout(TIMEOUT)
                .method(method, body.isBlank()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);

        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());

        StringBuilder out = new StringBuilder()
                .append(method).append(' ').append(response.uri()).append('\n')
                .append("HTTP ").append(response.statusCode()).append('\n');
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            out.append(header.getKey()).append(": ").append(String.join(", ", header.getValue())).append('\n');
        }
        return new Response(response.statusCode(), out.append('\n').append(response.body()).toString(), response.body());
    }
}
