package com.devmanchego.javaguard.analysis.deps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OSV over HTTPS ({@code https://api.osv.dev}): {@code POST /v1/querybatch} for the ids that affect each coordinate, then
 * {@code GET /v1/vulns/<id>} for each distinct id to learn its severity (the batch answer carries ids only). The
 * ecosystem is {@code Maven}; the only data sent are coordinates. One deadline covers the whole lookup
 * ({@code deps.osv.timeoutSeconds}); anything slower, unreachable or malformed is an {@link IOException}, which the
 * caller turns into a note, never a finding.
 */
public final class HttpOsvClient implements OsvClient {

    public static final URI PUBLIC_API = URI.create("https://api.osv.dev");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DETAILS = 25;

    private final URI base;
    private final Duration timeout;
    private final HttpClient http;

    public HttpOsvClient(URI base, Duration timeout) {
        this.base = base;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<Vulnerability> query(List<Dependency> dependencies) throws IOException, InterruptedException {
        if (dependencies.isEmpty()) {
            return List.of();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode batch = post("/v1/querybatch", batchBody(dependencies), left(deadline));
        JsonNode results = batch.path("results");
        Map<String, Dependency> firstDependencyOf = new LinkedHashMap<>();
        Map<String, List<Dependency>> affected = new LinkedHashMap<>();
        for (int i = 0; i < dependencies.size() && i < results.size(); i++) {
            for (JsonNode vuln : results.get(i).path("vulns")) {
                String id = vuln.path("id").asText();
                if (!id.isEmpty()) {
                    firstDependencyOf.putIfAbsent(id, dependencies.get(i));
                    affected.computeIfAbsent(id, k -> new ArrayList<>()).add(dependencies.get(i));
                }
            }
        }
        Set<String> ids = new LinkedHashSet<>(affected.keySet());
        List<String> toDetail = ids.stream().limit(MAX_DETAILS).toList();
        Map<String, CompletableFuture<HttpResponse<String>>> pending = new LinkedHashMap<>();
        for (String id : toDetail) {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/v1/vulns/" + id)).timeout(left(deadline)).GET().build();
            pending.put(id, http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        }
        List<Vulnerability> out = new ArrayList<>();
        for (String id : ids) {
            String summary = "";
            OptionalDouble score = OptionalDouble.empty();
            CompletableFuture<HttpResponse<String>> future = pending.get(id);
            if (future != null) {
                try {
                    HttpResponse<String> response = future.get(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
                    if (response.statusCode() == 200) {
                        JsonNode detail = JSON.readTree(response.body());
                        summary = detail.path("summary").asText("");
                        score = score(detail);
                    }
                } catch (ExecutionException e) {
                    throw new IOException("OSV did not answer for " + id + ": " + e.getCause().getMessage(), e);
                } catch (TimeoutException e) {
                    throw new IOException("OSV did not answer within " + timeout.toSeconds() + " s", e);
                }
            }
            for (Dependency dependency : affected.get(id)) {
                out.add(new Vulnerability(dependency, id, summary, score));
            }
        }
        return out;
    }

    /** CVSS v3 vector first (computed); otherwise the advisory database's own label (GHSA: CRITICAL/HIGH/MODERATE/LOW). */
    static OptionalDouble score(JsonNode detail) {
        for (JsonNode severity : detail.path("severity")) {
            OptionalDouble computed = CvssV3.baseScore(severity.path("score").asText(""));
            if (computed.isPresent()) {
                return computed;
            }
        }
        return switch (detail.path("database_specific").path("severity").asText("").toUpperCase()) {
            case "CRITICAL" -> OptionalDouble.of(9.5);
            case "HIGH" -> OptionalDouble.of(8.0);
            case "MODERATE", "MEDIUM" -> OptionalDouble.of(5.0);
            case "LOW" -> OptionalDouble.of(2.0);
            default -> OptionalDouble.empty();
        };
    }

    private static String batchBody(List<Dependency> dependencies) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode queries = root.putArray("queries");
        for (Dependency dependency : dependencies) {
            ObjectNode query = queries.addObject();
            ObjectNode pkg = query.putObject("package");
            pkg.put("name", dependency.key());
            pkg.put("ecosystem", "Maven");
            query.put("version", dependency.version());
        }
        return JSON.writeValueAsString(root);
    }

    private JsonNode post(String path, String body, Duration timeLeft) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(timeLeft)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("OSV answered HTTP " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    private static Duration left(long deadlineNanos) throws IOException {
        long left = deadlineNanos - System.nanoTime();
        if (left <= 0) {
            throw new IOException("OSV lookup exceeded its time limit");
        }
        return Duration.ofNanos(left);
    }
}
