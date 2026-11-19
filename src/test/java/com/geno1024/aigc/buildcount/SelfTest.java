package com.geno1024.ai.buildcount;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dependency-free self test: run with ./test.sh */
public final class SelfTest {

    private static int checks;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("build-count-test");
        Clock day1 = Clock.fixed(Instant.parse("2026-11-19T02:00:00Z"), CounterStore.DEFAULT_ZONE);
        CounterStore store = new CounterStore(dir, day1);

        check(store.projects().isEmpty(), "no projects initially");
        equal(1L, store.increment("qqfm", "app"), "first increment returns 1");
        equal(2L, store.increment("qqfm", "app"), "second increment returns 2");
        equal(1L, store.increment("qqfm", "git-core"), "other module starts at 1");
        equal(3L, store.total("qqfm"), "project total");
        equal(List.of("app", "git-core"), store.modules("qqfm"), "module list");
        equal(3L, store.calendar("qqfm").get("2026-11-19"), "day total");
        equal(2L, store.calendar("qqfm", "app").get("2026-11-19"), "day module total");
        equal(List.of("qqfm"), store.projects(), "project list");

        Clock day2 = Clock.fixed(Instant.parse("2026-11-20T02:00:00Z"), CounterStore.DEFAULT_ZONE);
        CounterStore nextDay = new CounterStore(dir, day2);
        equal(3L, nextDay.increment("qqfm", "app"), "module total is cumulative across days");
        equal(2, nextDay.calendar("qqfm").size(), "two days in calendar");
        equal(1L, nextDay.calendar("qqfm", "app").get("2026-11-20"), "new day starts at 1");
        equal(4L, nextDay.total("qqfm"), "total keeps growing across days");

        http(dir, day1);
        System.out.println("OK - " + checks + " checks passed");
    }

    private static void http(Path dir, Clock clock) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new ApiHandler(new CounterStore(dir, clock), "secret"));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.start();

        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient client = HttpClient.newHttpClient();
        try {
            HttpResponse<String> health = client.send(
                    request(base + "/healthz", "GET", null), HttpResponse.BodyHandlers.ofString());
            equal(200, health.statusCode(), "health status");
            equal("ok", health.body(), "health body");

            String builds = base + "/projects/qqfm/modules/app/builds";
            equal(401, client.send(request(builds, "POST", null), HttpResponse.BodyHandlers.ofString()).statusCode(),
                    "POST without token");
            equal(401, client.send(request(builds, "POST", "Bearer wrong"), HttpResponse.BodyHandlers.ofString()).statusCode(),
                    "POST with wrong token");
            HttpResponse<String> posted = client.send(
                    request(builds, "POST", "Bearer secret"), HttpResponse.BodyHandlers.ofString());
            equal(201, posted.statusCode(), "POST with token");
            check(posted.body().matches("\\d+"), "POST body is a plain number");

            HttpResponse<String> project = client.send(
                    request(base + "/projects/qqfm", "GET", null), HttpResponse.BodyHandlers.ofString());
            equal(200, project.statusCode(), "GET project");
            check(project.body().contains("\"total\""), "GET project returns JSON total");

            equal(400, client.send(request(base + "/projects/..", "GET", null),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "path traversal rejected");
            equal(405, client.send(request(base + "/healthz", "DELETE", null),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "wrong method rejected");
            equal(404, client.send(request(base + "/nope", "GET", null),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "unknown route");
        } finally {
            server.stop(0);
            executor.shutdown();
        }
    }

    private static HttpRequest request(String url, String method, String auth) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if (auth != null) builder.header("Authorization", auth);
        return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void equal(Object expected, Object actual, String label) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
