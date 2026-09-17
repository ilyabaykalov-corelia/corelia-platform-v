package ru.corelia.integration;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.support.LogJson;

import tools.jackson.databind.JsonNode;


/** Передает неизмененные GraphQL-операции DataSpace с токеном текущего пользователя. */
@Component
public class DataSpaceClient {
    private final CoreliaConfig config;
    private final PlatformHttp http;
    private static final java.util.regex.Pattern OPERATION =
            java.util.regex.Pattern.compile(
                    "^(?:query|mutation)\\s+([_A-Za-z][_0-9A-Za-z]*)(?=[\\s({])");
    private final java.util.Map<String, ru.corelia.configuration.ConfigurationLoader.Operation> operations;

    public DataSpaceClient(CoreliaConfig config, PlatformHttp http, ru.corelia.configuration.ConfigurationLoader.LoadedConfiguration configuration) {
        this.operations = configuration.operations();
        for (String name : java.util.List.of("searchDocument", "searchDocumentVersion", "searchDocumentCommand", "searchAttachment",
                "initializeDocumentVersion", "commitDocumentNoChange", "commitDocumentFileUpload", "commitDocumentFileReplace",
                "commitDocumentFileDelete", "searchDocumentProcessSettings", "refDocumentTypeListGet")) {
            if (!operations.containsKey(name)) throw new ru.corelia.configuration.ConfigurationException("Missing Platform V operation: " + name);
            if (name.startsWith("commit") && !operations.get(name).multiaggregate()) throw new ru.corelia.configuration.ConfigurationException("Version commits require multiaggregate: " + name);
        }
        this.config = config;
        this.http = http;
    }

    public JsonNode query(String name, JsonNode variables, AuthContext auth) {
        var operation = operations.get(name);
        if (operation == null) throw new IllegalStateException("Не зарегистрирована GraphQL-операция " + name);
        return execute(operation.text(), variables, auth, operation.multiaggregate());
    }

    /** Передаёт текст операции без изменений; пользовательские значения передаются отдельно. */
    public JsonNode execute(String query, JsonNode variables, AuthContext auth) {
        return execute(query, variables, auth, false);
    }

    private JsonNode execute(String query, JsonNode variables, AuthContext auth, boolean multiaggregate) {
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
                            multiaggregate
                                    ? java.util.Map.of("Accept", "application/graphql-response+json, application/json", "X-DSPC-multiaggregate", "true")
                                    : java.util.Map.of("Accept", "application/graphql-response+json, application/json"));
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
            String details = write(response.path("errors"));
            if (details.contains("COMPARE_NOT_EQUAL") || details.contains("AggregateVersionException"))
                throw new ApiException(409, "Документ изменён другим запросом. Обновите карточку.");
            throw new ApiException(502, message);
        }
        if (!response.path("data").isObject())
            throw new ApiException(502, "DataSpace GraphQL вернул ответ без data");
        return response.path("data");
    }
}
