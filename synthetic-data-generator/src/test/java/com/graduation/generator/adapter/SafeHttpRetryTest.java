package com.graduation.generator.adapter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SafeHttpRetryTest {

    @Test
    void retriesTransientGetOnceThenReturnsSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/read", exchange -> {
            int count = calls.incrementAndGet();
            byte[] body = (count == 1 ? "temporary" : "ok").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(count == 1 ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/read")).GET().build();
            HttpResponse<String> response = SafeHttpRetry.send(client, request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("ok", response.body());
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void neverRetriesBusinessWriteEvenForTransientStatus() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/write", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/write"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = SafeHttpRetry.send(client, request, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
