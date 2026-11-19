package com.geno1024.ai.buildcount;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Read-only GETs, token-protected POST that records a build. */
public final class ApiHandler implements HttpHandler {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final CounterStore store;
    private final byte[] token;

    public ApiHandler(CounterStore store, String token) {
        this.store = store;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Reply reply;
        try {
            reply = route(exchange);
        } catch (HttpError e) {
            reply = e.reply;
        } catch (RuntimeException e) {
            e.printStackTrace();
            reply = error(500, "internal error");
        }

        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            if (reply.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", reply.contentType());
            }
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } finally {
            exchange.close();
        }
    }

    private Reply route(HttpExchange exchange) throws IOException {
        String[] raw = exchange.getRequestURI().getPath().split("/");
        List<String> path = new ArrayList<>();
        for (String part : raw) {
            if (!part.isEmpty()) path.add(part);
        }

        if (path.equals(List.of("healthz"))) {
            requireMethod(exchange, "GET");
            return plain(200, "ok");
        }

        if (path.equals(List.of("projects"))) {
            requireMethod(exchange, "GET");
            return json(200, array(store.projects()));
        }

        if (path.size() >= 2 && path.get(0).equals("projects")) {
            String project = segment(path.get(1));

            if (path.size() == 2) {
                requireMethod(exchange, "GET");
                if (!store.hasProject(project)) return error(404, "project not found");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", project);
                body.put("total", store.total(project));
                return json(200, object(body));
            }

            if (path.size() == 3 && path.get(2).equals("modules")) {
                requireMethod(exchange, "GET");
                if (!store.hasProject(project)) return error(404, "project not found");
                return json(200, array(store.modules(project)));
            }

            if (path.size() == 3 && path.get(2).equals("calendar")) {
                requireMethod(exchange, "GET");
                if (!store.hasProject(project)) return error(404, "project not found");
                return json(200, numbers(store.calendar(project)));
            }

            if (path.size() == 4 && path.get(2).equals("modules")) {
                requireMethod(exchange, "GET");
                Long total = store.moduleTotal(project, segment(path.get(3)));
                if (total == null) return error(404, "module not found");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("project", project);
                body.put("module", segment(path.get(3)));
                body.put("total", total);
                return json(200, object(body));
            }

            if (path.size() == 5 && path.get(2).equals("modules") && path.get(4).equals("calendar")) {
                requireMethod(exchange, "GET");
                if (!store.hasProject(project)) return error(404, "project not found");
                return json(200, numbers(store.calendar(project, segment(path.get(3)))));
            }

            if (path.size() == 5 && path.get(2).equals("modules") && path.get(4).equals("builds")) {
                requireMethod(exchange, "POST");
                requireToken(exchange);
                long total = store.increment(project, segment(path.get(3)));
                return plain(201, Long.toString(total));
            }
        }

        return error(404, "not found");
    }

    private void requireToken(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new HttpError(error(401, "unauthorized", Map.of("WWW-Authenticate", "Bearer")));
        }
        byte[] provided = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(provided, token)) {
            throw new HttpError(error(401, "unauthorized", Map.of("WWW-Authenticate", "Bearer")));
        }
    }

    private static void requireMethod(HttpExchange exchange, String expected) {
        if (!exchange.getRequestMethod().equals(expected)) {
            throw new HttpError(error(405, "method not allowed", Map.of("Allow", expected)));
        }
    }

    private static String segment(String value) {
        if (!SEGMENT.matcher(value).matches() || value.equals(".") || value.equals("..")) {
            throw new HttpError(error(400, "invalid path segment"));
        }
        return value;
    }

    private record Reply(int status, String body, String contentType, Map<String, String> headers) {}

    private static Reply plain(int status, String body) {
        return new Reply(status, body, "text/plain; charset=utf-8", Map.of());
    }

    private static Reply json(int status, String body) {
        return new Reply(status, body, "application/json; charset=utf-8", Map.of());
    }

    private static Reply error(int status, String message) {
        return error(status, message, Map.of());
    }

    private static Reply error(int status, String message, Map<String, String> headers) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", message);
        return new Reply(status, object(body), "application/json; charset=utf-8", headers);
    }

    private static String array(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append(quote(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static String object(Map<String, ?> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(quote(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value instanceof Number) {
                out.append(value);
            } else {
                out.append(quote(String.valueOf(value)));
            }
        }
        return out.append('}').toString();
    }

    private static String numbers(Map<String, Long> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(quote(entry.getKey())).append(':').append(entry.getValue());
        }
        return out.append('}').toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private static final class HttpError extends RuntimeException {
        private final Reply reply;

        private HttpError(Reply reply) {
            this.reply = reply;
        }
    }
}
