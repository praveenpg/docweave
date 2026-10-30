package io.docweave.source.s3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local HTTP endpoint that answers S3 GetObject requests with scripted responses, including faults a real service
 * cannot be made to produce on demand (5xx, truncated bodies, wrong lengths, stalls). It is a test double for transport
 * behavior, not an S3 implementation; real S3-compatible behavior is covered by the S3Mock and SeaweedFS tests.
 */
final class ScriptedS3Server implements AutoCloseable {
    /** One scripted reply. */
    @FunctionalInterface
    interface Reply {
        /**
         * Writes a response.
         * @param exchange exchange
         * @throws IOException on write failure
         */
        void write(HttpExchange exchange) throws IOException;
    }

    /** Server. */
    private final HttpServer server;

    /** Replies consumed in order; the last one repeats. */
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();

    /** Fallback reply after the queue is exhausted. */
    private volatile Reply fallback = exchange -> error(exchange, 500, "InternalError");

    /** Recorded requests: method, path and query, and selected headers. */
    final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();

    /** Requests in flight. */
    final AtomicInteger inFlight = new AtomicInteger();

    /** Highest concurrency observed. */
    final AtomicInteger maxInFlight = new AtomicInteger();

    /**
     * Starts the server on a loopback port.
     * @throws IOException if binding fails
     */
    ScriptedS3Server() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            final int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                final var uri = exchange.getRequestURI();
                requests.add(Map.of("method", exchange.getRequestMethod(), "path", uri.getRawPath(), "query",
                        uri.getRawQuery() == null ? "" : uri.getRawQuery(), "ifMatch",
                        String.valueOf(exchange.getRequestHeaders().getFirst("If-Match")), "authorization",
                        String.valueOf(exchange.getRequestHeaders().getFirst("Authorization") != null)));
                final Reply reply = replies.poll();
                (reply != null ? reply : fallback).write(exchange);
            } catch (final IOException ignored) {
                // The client aborted the exchange.
            } finally {
                inFlight.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
    }

    /**
     * Returns the endpoint URI.
     * @return http endpoint
     */
    URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /**
     * Queues replies.
     * @param values replies in order
     * @return this server
     */
    ScriptedS3Server then(final Reply... values) {
        replies.addAll(List.of(values));
        return this;
    }

    /**
     * Sets the reply used after the queue is exhausted.
     * @param value reply
     * @return this server
     */
    ScriptedS3Server always(final Reply value) {
        fallback = value;
        return this;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * Replies with an object.
     * @param content body
     * @param version version header or null
     * @param entityTag quoted entity tag or null
     * @return reply
     */
    static Reply object(final byte[] content, final String version, final String entityTag) {
        return exchange -> {
            if (version != null) {
                exchange.getResponseHeaders().add("x-amz-version-id", version);
            }
            if (entityTag != null) {
                exchange.getResponseHeaders().add("ETag", entityTag);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, content.length == 0 ? -1 : content.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(content);
            }
        };
    }

    /**
     * Replies with a chunked body (no Content-Length).
     * @param content body
     * @return reply
     */
    static Reply chunked(final byte[] content) {
        return exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(content);
            }
        };
    }

    /**
     * Declares a length but sends only part of the body, then drops the connection.
     * @param declared declared length
     * @param sent bytes actually sent
     * @return reply
     */
    static Reply truncated(final int declared, final int sent) {
        return exchange -> {
            exchange.sendResponseHeaders(200, declared);
            final OutputStream body = exchange.getResponseBody();
            body.write(new byte[sent]);
            body.flush();
            throw new IOException("drop connection");
        };
    }

    /**
     * Sends headers and a few bytes, then stalls until the client gives up.
     * @return reply
     */
    static Reply stall() {
        return exchange -> {
            exchange.sendResponseHeaders(200, 1_000);
            final OutputStream body = exchange.getResponseBody();
            body.write(new byte[10]);
            body.flush();
            try {
                Thread.sleep(60_000);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("stalled");
        };
    }

    /**
     * Replies with an S3 error document.
     * @param exchange exchange
     * @param status HTTP status
     * @param code S3 error code
     * @throws IOException on write failure
     */
    static void error(final HttpExchange exchange, final int status, final String code) throws IOException {
        final byte[] xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code
                            + "</Code><Message>scripted " + code + "</Message><RequestId>r</RequestId></Error>")
                                   .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, xml.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(xml);
        }
    }

    /**
     * Returns an error reply.
     * @param status HTTP status
     * @param code S3 error code
     * @return reply
     */
    static Reply failure(final int status, final String code) {
        return exchange -> error(exchange, status, code);
    }
}
