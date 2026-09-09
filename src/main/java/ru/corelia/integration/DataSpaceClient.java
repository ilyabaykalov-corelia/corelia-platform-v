package ru.corelia.integration;

import static ru.corelia.support.Json.*;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.support.LogJson;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** Передает неизмененные GraphQL-операции DataSpace с токеном текущего пользователя. */
@Component
public class DataSpaceClient {
    private final CoreliaConfig config;
    private final PlatformHttp http;
    private static final java.util.regex.Pattern OPERATION =
            java.util.regex.Pattern.compile(
                    "^(?:query|mutation)\\s+([_A-Za-z][_0-9A-Za-z]*)(?=[\\s({])");
    private final ConcurrentHashMap<String, String> queries = new ConcurrentHashMap<>();

    public DataSpaceClient(CoreliaConfig config, PlatformHttp http) {
        this.config = config;
        this.http = http;
    }

    public JsonNode query(String name, JsonNode variables, AuthContext auth) {
        String query =
                queries.computeIfAbsent(
                        name,
                        key -> {
                            try {
                                return new ClassPathResource("graphql/" + key + ".graphql")
                                        .getContentAsString(StandardCharsets.UTF_8)
                                        .trim();
                            } catch (IOException error) {
                                throw new IllegalStateException(
                                        "Не найден GraphQL-ресурс " + key, error);
                            }
                        });
        return execute(query, variables, auth);
    }

    /** Передаёт текст операции без изменений; пользовательские значения передаются отдельно. */
    public JsonNode execute(String query, JsonNode variables, AuthContext auth) {
        var operation = OPERATION.matcher(query.stripLeading());
        if (!operation.find())
            throw new IllegalArgumentException("GraphQL-запрос должен иметь явное имя операции");
        String url = config.required("PLATFORM_V_DATASPACE_GRAPHQL_URL", config.dataspace());
        long startedAt = System.nanoTime();
        JsonNode response;
        try {
            response =
                    http.platform(
                            url,
                            "POST",
                            object("query", query, "variables", variables),
                            auth,
                            java.util.Map.of(
                                    "Accept",
                                    "application/graphql-response+json, application/json"));
            LogJson.info(
                    "DataSpace GraphQL completed",
                    object(
                            "url", LogJson.upstreamTarget(url),
                            "operation", operation.group(1),
                            "durationMs", (System.nanoTime() - startedAt) / 1_000_000));
        } catch (ApiException error) {
            LogJson.info(
                    "DataSpace GraphQL failed",
                    object(
                            "url", LogJson.upstreamTarget(url),
                            "operation", operation.group(1),
                            "durationMs", (System.nanoTime() - startedAt) / 1_000_000,
                            "status", error.status(),
                            "message", error.getMessage()));
            throw error;
        } catch (RuntimeException error) {
            LogJson.info(
                    "DataSpace GraphQL failed",
                    object(
                            "url", LogJson.upstreamTarget(url),
                            "operation", operation.group(1),
                            "durationMs", (System.nanoTime() - startedAt) / 1_000_000,
                            "message", error.getMessage()));
            throw error;
        }
        if (!list(response.path("errors")).isEmpty()) {
            String message = "DataSpace GraphQL: " + PlatformHttp.errorMessage(response);
            LogJson.info(
                    "DataSpace GraphQL failed",
                    object(
                            "url",
                            LogJson.upstreamTarget(url),
                            "operation",
                            operation.group(1),
                            "durationMs",
                            (System.nanoTime() - startedAt) / 1_000_000,
                            "status",
                            502,
                            "message",
                            message));
            throw new ApiException(502, message);
        }
        if (!response.path("data").isObject())
            throw new ApiException(502, "DataSpace GraphQL вернул ответ без data");
        return response.path("data");
    }
}
