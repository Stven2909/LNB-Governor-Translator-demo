package com.pagaduriasintetica.worker.agent.tool;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MOCK: WS de tickets simulado, solo para pruebas locales.
 * Carlos aun NO ha entregado el WS real; este stub imita el contrato propuesto
 * (POST /api/v1/tickets/lookup) y no debe usarse en produccion.
 */
final class MockTicketWsServer implements AutoCloseable {

    static final String LOOKUP_PATH = "/api/v1/tickets/lookup";

    private final HttpServer server;
    private final ExecutorService executor;
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile Duration delay = Duration.ZERO;
    private volatile String lastRequestBody;
    private volatile String lastRequestId;

    MockTicketWsServer() throws IOException {
        // MOCK: puerto efimero para no chocar con servicios locales.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext(LOOKUP_PATH, exchange -> {
            try {
                lastRequestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
                lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                if (!delay.isZero()) {
                    // MOCK: simula un WS lento para probar el timeout del adaptador.
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, payload.length);
                exchange.getResponseBody().write(payload);
            } catch (IOException ignored) {
                // MOCK: el cliente cerro la conexion (por ejemplo, al dispararse el timeout).
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    MockTicketWsServer respond(int status, String body) {
        this.status = status;
        this.body = body;
        return this;
    }

    MockTicketWsServer delay(Duration delay) {
        this.delay = delay;
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String lastRequestBody() {
        return lastRequestBody;
    }

    String lastRequestId() {
        return lastRequestId;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
