package dev.distributedgateway.core;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

public final class AccessLogger {
    private final Consumer<String> lineConsumer;

    public AccessLogger() {
        this(System.out::println);
    }

    public AccessLogger(Consumer<String> lineConsumer) {
        this.lineConsumer = Objects.requireNonNull(lineConsumer);
    }

    public void log(String method, String path, String routeId, int statusCode,
            double durationMs, String upstream, boolean rateLimited, String clientIp) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"timestamp\":\"").append(Instant.now()).append("\",");
        json.append("\"method\":\"").append(escape(method)).append("\",");
        json.append("\"path\":\"").append(escape(path)).append("\",");
        json.append("\"route\":\"").append(routeId != null ? escape(routeId) : "-").append("\",");
        json.append("\"status\":").append(statusCode).append(",");
        json.append("\"durationMs\":").append(String.format(java.util.Locale.ROOT, "%.2f", durationMs)).append(",");
        if (upstream != null) {
            json.append("\"upstream\":\"").append(escape(upstream)).append("\",");
        } else {
            json.append("\"upstream\":null,");
        }
        json.append("\"rateLimited\":").append(rateLimited).append(",");
        json.append("\"clientIp\":\"").append(clientIp != null ? escape(clientIp) : "-").append("\"}");

        lineConsumer.accept(json.toString());
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
