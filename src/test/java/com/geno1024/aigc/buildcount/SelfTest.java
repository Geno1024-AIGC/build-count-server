package com.geno1024.ai.buildcount;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
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
        equal(Arrays.asList("app", "git-core"), store.modules("qqfm"), "module list");
        equal(3L, store.calendar("qqfm").get("2026-11-19"), "day total");
        equal(2L, store.calendar("qqfm", "app").get("2026-11-19"), "day module total");
        equal(Arrays.asList("qqfm"), store.projects(), "project list");

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
        try {
            Resp health = request(base + "/healthz", "GET", null);
            equal(200, health.status, "health status");
            equal("ok", health.body, "health body");

            String builds = base + "/projects/qqfm/modules/app/builds";
            equal(401, request(builds, "POST", null).status, "POST without token");
            equal(401, request(builds, "POST", "Bearer wrong").status, "POST with wrong token");
            Resp posted = request(builds, "POST", "Bearer secret");
            equal(201, posted.status, "POST with token");
            check(posted.body.matches("\\d+"), "POST body is a plain number");

            Resp project = request(base + "/projects/qqfm", "GET", null);
            equal(200, project.status, "GET project");
            check(project.body.contains("\"total\""), "GET project returns JSON total");

            equal(400, request(base + "/projects/..", "GET", null).status, "path traversal rejected");
            equal(405, request(base + "/healthz", "DELETE", null).status, "wrong method rejected");
            equal(404, request(base + "/nope", "GET", null).status, "unknown route");
        } finally {
            server.stop(0);
            executor.shutdown();
        }
    }

    private static Resp request(String url, String method, String auth) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (auth != null) connection.setRequestProperty("Authorization", auth);
            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                connection.getOutputStream().close();
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = input == null ? "" : read(input);
            return new Resp(status, body);
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static final class Resp {
        final int status;
        final String body;

        Resp(int status, String body) {
            this.status = status;
            this.body = body;
        }
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
