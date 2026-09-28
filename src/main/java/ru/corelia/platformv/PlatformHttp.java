package ru.corelia.platformv;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.observability.CoreliaObservability;
import ru.corelia.observability.TraceContextPropagation;
import ru.corelia.transport.UpstreamResponse;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Единый транспорт: пользовательский токен, ограничение времени, запрет редиректов. */
@Component
public class PlatformHttp {
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final CoreliaObservability observability;
    private final TraceContextPropagation traceContext;
    private final CoreliaConfig config;

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformHttp(
            CoreliaObservability observability, TraceContextPropagation traceContext, CoreliaConfig config) {
        this.observability = observability;
        this.traceContext = traceContext;
        this.config = config;
    }

    public PlatformHttp(CoreliaObservability observability, TraceContextPropagation traceContext) {
        this(observability, traceContext, null);
    }

    public JsonNode platform(String url, String method, JsonNode body, AuthContext auth) {
        return platform(url, method, body, auth, Map.of());
    }

    public JsonNode platform(
            String url, String method, JsonNode body, AuthContext auth, Map<String, String> extra) {
        return platform(url, method, body, auth, extra, "platform-v", "http");
    }

    public JsonNode platform(
            String url,
            String method,
            JsonNode body,
            AuthContext auth,
            Map<String, String> extra,
            String client,
            String operation) {
        Map<String, String> headers = new LinkedHashMap<>(extra);
        headers.put("Authorization", auth.authorization());
        headers.putIfAbsent("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        return json(url, method, body == null ? null : write(body), headers, client, operation);
    }

    public JsonNode json(String url, String method, String body, Map<String, String> headers) {
        return json(url, method, body, headers, "platform-v", "http");
    }

    public JsonNode json(
            String url,
            String method,
            String body,
            Map<String, String> headers,
            String client,
            String operation) {
        byte[] bytes =
                body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var response = raw(url, method, bytes, headers, client, operation);
        if (response.body().length == 0) return object();
        try {
            return MAPPER.readTree(response.body());
        } catch (RuntimeException error) {
            throw new ApiException(
                    502, "Platform V вернул не JSON-ответ. Проверьте URL и авторизацию API");
        }
    }

    public HttpResponse<byte[]> raw(
            String url, String method, byte[] body, Map<String, String> headers) {
        return raw(url, method, body, headers, "platform-v", "http");
    }

    public HttpResponse<byte[]> raw(
            String url,
            String method,
            byte[] body,
            Map<String, String> headers,
            String client,
            String operation) {
        return raw(
                url,
                method,
                body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body),
                headers,
                client,
                operation);
    }

    public HttpResponse<java.io.InputStream> rawStream(
            String url, String method, Map<String, String> headers) {
        return rawStream(url, method, headers, "storage", "get");
    }

    public HttpResponse<java.io.InputStream> rawStream(
            String url,
            String method,
            Map<String, String> headers,
            String client,
            String operation) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .method(method, HttpRequest.BodyPublishers.noBody());
            headers.forEach(builder::header);
            traceContext.inject(builder);
            var response = observability.observe(client + "." + operation, () -> {
                try {
                    return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                } catch (IOException error) {
                    throw new PlatformCallException(error);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new PlatformCallException(error);
                }
            });
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                observability.externalRequest(client, operation, "success");
                return response;
            }
            String raw = new String(
                        UpstreamResponse.read(response, maxResponseBytes()).body(),
                    java.nio.charset.StandardCharsets.UTF_8);
            throw new ApiException(response.statusCode() >= 500 ? 502 : response.statusCode(), fallback(normalizeText(raw), "Platform V API вернул HTTP " + response.statusCode()));
        } catch (ApiException error) {
            observability.externalRequest(client, operation, error.status() == 504 ? "timeout" : "error");
            throw error;
        } catch (PlatformCallException error) {
            if (error.getCause() instanceof HttpTimeoutException) {
                observability.externalRequest(client, operation, "timeout");
                throw new ApiException(504, "Истекло время ожидания ответа Platform V");
            }
            if (error.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new ApiException(503, "Вызов Platform V прерван при остановке Corelia");
            }
            observability.externalRequest(client, operation, "error");
            throw new ApiException(502, "Ошибка вызова Platform V: " + error.getCause().getClass().getSimpleName());
        } catch (HttpTimeoutException error) {
            observability.externalRequest(client, operation, "timeout");
            throw new ApiException(504, "Истекло время ожидания ответа Platform V");
        } catch (IOException | IllegalArgumentException error) {
            observability.externalRequest(client, operation, "error");
            throw new ApiException(502, "Ошибка вызова Platform V: " + error.getClass().getSimpleName());
        }
    }

    public HttpResponse<byte[]> raw(
            String url,
            String method,
            HttpRequest.BodyPublisher body,
            Map<String, String> headers) {
        return raw(url, method, body, headers, "platform-v", "http");
    }

    public HttpResponse<byte[]> raw(
            String url,
            String method,
            HttpRequest.BodyPublisher body,
            Map<String, String> headers,
            String client,
            String operation) {
        try {
            var builder =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .method(method, body);
            headers.forEach(builder::header);
            traceContext.inject(builder);
            var response = observability.observe(client + "." + operation, () -> {
                try {
                    return UpstreamResponse.read(
                            this.client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()),
                            maxResponseBytes());
                } catch (IOException error) {
                    throw new PlatformCallException(error);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new PlatformCallException(error);
                }
            });
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("location").orElse("");
                boolean authRedirect =
                        location.matches("(?is).*(openid-connect/auth|PlatformAuth|login).*");
                throw new ApiException(
                        502,
                        authRedirect
                                ? "Platform V API не принял Keycloak access token и вернул редирект"
                                        + " на авторизацию. Проверьте API route"
                                : "Platform V API вернул редирект");
            }
            if (status < 200 || status >= 300) {
                String raw = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
                String message;
                try {
                    message = errorMessage(parse(raw));
                } catch (RuntimeException error) {
                    message = normalizeText(raw);
                }
                throw new ApiException(
                        status >= 500 ? 502 : status,
                        fallback(message, "Platform V API вернул HTTP " + status));
            }
            observability.externalRequest(client, operation, "success");
            return response;
        } catch (ApiException error) {
            observability.externalRequest(client, operation, error.status() == 504 ? "timeout" : "error");
            throw error;
        } catch (PlatformCallException error) {
            if (error.getCause() instanceof HttpTimeoutException) {
                observability.externalRequest(client, operation, "timeout");
                throw new ApiException(504, "Истекло время ожидания ответа Platform V");
            }
            if (error.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new ApiException(503, "Вызов Platform V прерван при остановке Corelia");
            }
            observability.externalRequest(client, operation, "error");
            throw new ApiException(
                    502,
                    "Ошибка вызова Platform V: " + error.getCause().getClass().getSimpleName());
        } catch (IllegalArgumentException error) {
            observability.externalRequest(client, operation, "error");
            throw new ApiException(
                    502, "Ошибка вызова Platform V: " + error.getClass().getSimpleName());
        }
    }

    private static class PlatformCallException extends RuntimeException {
        PlatformCallException(Exception cause) {
            super(cause);
        }
    }

    private long maxResponseBytes() {
        return config == null ? 10L * 1024 * 1024 : UpstreamResponse.maxResponseBytes(config);
    }

    public static String errorMessage(JsonNode payload) {
        if (payload == null) return "";
        if (payload.isTextual()) return normalizeText(text(payload));
        String message = first(payload, "message", "error_description", "error");
        if (!message.isEmpty()) return message;
        return String.join(
                "; ",
                list(payload.path("errors")).stream()
                        .map(item -> text(item, "message"))
                        .filter(s -> !s.isEmpty())
                        .toList());
    }

    private static String normalizeText(String value) {
        String clean =
                value.replaceAll("(?is)<script.*?</script>|<style.*?</style>|<[^>]+>", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
        return clean.length() > 700 ? clean.substring(0, 700) + "..." : clean;
    }
}
