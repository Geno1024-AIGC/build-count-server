package com.geno1024.ai.buildcount;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Main {

    public static void main(String[] args) throws Exception {
        String token = System.getenv("BUILD_COUNT_TOKEN");
        if (token == null || token.isEmpty()) {
            System.err.println("BUILD_COUNT_TOKEN must be set to a non-empty value");
            System.exit(1);
        }

        String host = env("BIND", "127.0.0.1");
        int port = Integer.parseInt(env("PORT", "8080"));
        Path dataDir = Paths.get(env("DATA_DIR", "data"));

        CounterStore store = new CounterStore(dataDir);
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", new ApiHandler(store, token));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdown();
        }));

        server.start();
        System.out.println("Listening on " + host + ":" + port
                + " (data: " + dataDir.toAbsolutePath() + ")");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
