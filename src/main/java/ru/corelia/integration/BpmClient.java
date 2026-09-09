package ru.corelia.integration;

import static ru.corelia.config.CoreliaConfig.trim;
import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.support.LogJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.*;

/** Адаптер системных BPMX/BPMU API и публичного запуска процессов приложения. */
@Component
public class BpmClient {
    private final CoreliaConfig config;
    private final PlatformHttp http;

    public BpmClient(CoreliaConfig config, PlatformHttp http) {
        this.config = config;
        this.http = http;
    }

    public JsonNode system(String path, JsonNode body, AuthContext auth) {
        String url = trim(config.required("PLATFORM_V_BPMX_BASE_URL", config.bpmx())) + path;
        String method = body == null ? "GET" : "POST";
        long startedAt = System.nanoTime();
        LogJson.info(
                "Вызов системного API BPMX",
                requestDetails(url, body, "method", method));
        try {
            JsonNode result = http.platform(url, method, body, auth);
            LogJson.info(
                    "Системный API BPMX выполнил запрос",
                    responseDetails(url, startedAt, body == null ? null : result));
            return result;
        } catch (ApiException error) {
            LogJson.info("Ошибка системного API BPMX", failureDetails(url, startedAt, error));
            throw error;
        } catch (RuntimeException error) {
            LogJson.info("Ошибка системного API BPMX", failureDetails(url, startedAt, error));
            throw error;
        }
    }

    public JsonNode process(String suffix, JsonNode body, AuthContext auth) {
        String url =
                trim(config.required("PLATFORM_V_BPMX_BASE_URL", config.bpmx()))
                        + "/"
                        + encode(config.tenant())
                        + "/v7/apps/"
                        + encode(config.appId())
                        + suffix
                        + "?includeVariables=true";
        String method = body == null ? "GET" : "POST";
        long startedAt = System.nanoTime();
        LogJson.info(
                body == null ? "Calling Platform V process instance" : "Starting Platform V process",
                object(
                        "url", LogJson.upstreamTarget(url),
                        "mode", "bpmx-app-v7",
                        "tenant", config.tenant(),
                        "appInstanceId", config.appId(),
                        "path", suffix,
                        "username", auth.login(),
                        "roleCount", auth.roles().size()));
        try {
            JsonNode result = http.platform(url, method, body, auth);
            LogJson.info(
                    body == null
                            ? "Platform V process instance response"
                            : "Platform V process start response",
                    object(
                            "url", LogJson.upstreamTarget(url),
                            "durationMs", elapsedMs(startedAt),
                            "response", processSnapshot(result)));
            return result;
        } catch (ApiException error) {
            LogJson.info(
                    "Platform V process failed",
                    failureDetails(url, startedAt, error));
            throw error;
        } catch (RuntimeException error) {
            LogJson.info(
                    "Platform V process failed",
                    failureDetails(url, startedAt, error));
            throw error;
        }
    }

    public JsonNode taskList(String path, JsonNode body, Map<String, ?> query, AuthContext auth) {
        String base = config.required("PLATFORM_V_TASK_LIST_BASE_URL", config.bpmu());
        Map<String, String> headers = auth.taskHeaders();
        ApiException last = null;
        for (String candidate : candidates(base)) {
            String suffix =
                    query.isEmpty()
                            ? ""
                            : "?"
                                    + String.join(
                                            "&",
                                            query.entrySet().stream()
                                                    .map(
                                                            entry ->
                                                                    encode(entry.getKey())
                                                                            + "="
                                                                            + encode(
                                                                                    String.valueOf(
                                                                                            entry
                                                                                                    .getValue())))
                                                    .toList());
            String url = candidate + path + suffix;
            long startedAt = System.nanoTime();
            LogJson.info(
                    "Calling BPMU Task List",
                    object(
                            "url", LogJson.upstreamTarget(url),
                            "username", auth.taskUsername(),
                            "roleCount", auth.roles().size(),
                            "method", body == null ? "GET" : "POST"));
            try {
                JsonNode result = http.platform(
                        url,
                        body == null ? "GET" : "POST",
                        body,
                        auth,
                        headers);
                LogJson.info(
                        "BPMU Task List completed",
                        object(
                                "url", LogJson.upstreamTarget(url),
                                "durationMs", elapsedMs(startedAt)));
                return result;
            } catch (ApiException error) {
                last = error;
                LogJson.info(
                        "BPMU Task List failed",
                        object(
                                "url", LogJson.upstreamTarget(url),
                                "durationMs", elapsedMs(startedAt),
                                "status", error.status(),
                                "message", error.getMessage()));
                if (error.status() != 404
                        && !(error.status() == 502
                                && error.getMessage().contains("редирект на авторизацию")))
                    throw error;
                LogJson.info(
                        "BPMU Task List candidate failed, trying next",
                        object(
                                "url", LogJson.upstreamTarget(url),
                                "status", error.status(),
                                "message", error.getMessage()));
            }
        }
        if (body != null && last != null && last.status() == 404) {
            throw new ApiException(
                    502,
                    "BPMU Task List API не найден по PLATFORM_V_TASK_LIST_BASE_URL. Проверьте"
                            + " внешний route к bpmu-system-api/bpmu-backend");
        }
        throw last == null ? new ApiException(503, "Не настроен маршрут BPMU") : last;
    }

    static List<String> candidates(String base) {
        base = trim(base);
        URI uri = URI.create(base);
        String path = uri.getPath();
        int index = path.toLowerCase(Locale.ROOT).indexOf("/platformv");
        String gateway =
                index < 0
                        ? ""
                        : uri.getScheme()
                                + "://"
                                + uri.getRawAuthority()
                                + path.substring(0, index + 10)
                                + "/api/bpmu";
        boolean browser = path.matches("(?i).*/platformv/[^/]+/api$");
        Set<String> candidates = new LinkedHashSet<>();
        if (!browser) candidates.add(base);
        if (!gateway.isEmpty()) candidates.add(gateway);
        if (!browser && !path.matches("(?i).*/api$") && !path.matches("(?i).*/api/bpmu$"))
            candidates.add(base + "/api");
        return List.copyOf(candidates);
    }

    public static boolean unavailable(ApiException error) {
        return Set.of(400, 404, 405).contains(error.status())
                || (error.status() == 502
                        && error.getMessage().contains("BPMU Task List API не найден"));
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static ObjectNode requestDetails(String url, JsonNode body, String key, Object value) {
        ObjectNode details = object("url", LogJson.upstreamTarget(url), key, value);
        if (body != null) details.setAll(bodyMetadata(body));
        return details;
    }

    private static ObjectNode responseDetails(String url, long startedAt, JsonNode result) {
        ObjectNode details =
                object("url", LogJson.upstreamTarget(url), "durationMs", elapsedMs(startedAt));
        if (result != null) details.set("result", responseSummary(result));
        return details;
    }

    private static ObjectNode failureDetails(String url, long startedAt, RuntimeException error) {
        ObjectNode details =
                object(
                        "url", LogJson.upstreamTarget(url),
                        "durationMs", elapsedMs(startedAt),
                        "message", error.getMessage());
        if (error instanceof ApiException api) details.put("status", api.status());
        return details;
    }

    private static ObjectNode bodyMetadata(JsonNode body) {
        if (body == null || !body.isObject()) return object("bodyType", body == null ? "null" : "value");
        List<String> fields = new ArrayList<>();
        body.propertyNames().forEach(fields::add);
        fields.sort(String::compareTo);
        ObjectNode result = object("fields", fields);
        if (body.path("userTaskIds").isArray()) result.set("userTaskIds", body.path("userTaskIds"));
        if (body.path("parameters").isObject()) {
            List<String> names = new ArrayList<>();
            body.path("parameters").propertyNames().forEach(names::add);
            names.sort(String::compareTo);
            result.set("parameterNames", MAPPER.valueToTree(names));
        }
        return result;
    }

    private static ObjectNode processSnapshot(JsonNode result) {
        if (result == null || !result.isObject()) return object("bodyType", result == null ? "null" : "value");
        ObjectNode snapshot = object();
        for (String field : List.of("id", "state", "status", "isIncident"))
            if (result.has(field)) snapshot.set(field, result.path(field));
        if (result.path("currentActivities").isArray())
            snapshot.put("activityCount", result.path("currentActivities").size());
        if (result.path("globalVariables").isObject())
            snapshot.put("globalVariableCount", result.path("globalVariables").size());
        return snapshot;
    }

    private static ObjectNode responseSummary(JsonNode result) {
        return processSnapshot(result);
    }
}
