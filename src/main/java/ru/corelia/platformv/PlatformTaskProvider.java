package ru.corelia.platformv;

import static ru.corelia.support.Json.*;

import java.util.*;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.cache.UserCache;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.http.ApiException;
import ru.corelia.provider.TaskProvider;
import ru.corelia.provider.model.*;
import ru.corelia.support.ParallelCalls;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Преобразует BPMX/BPMU задачи в canonical модели Corelia. */
@Component
public final class PlatformTaskProvider implements TaskProvider {
    private static final List<String> SCOPES = List.of("EXECUTOR", "MANAGER");
    private final BpmClient bpm; private final ParallelCalls parallel; private final DocumentTypeCatalog types; private final UserCache cache; private final PlatformVConfig config; private final DataSpaceClient data;
    public PlatformTaskProvider(BpmClient bpm, ParallelCalls parallel, DocumentTypeCatalog types, UserCache cache, PlatformVConfig config, DataSpaceClient data) { this.bpm = bpm; this.parallel = parallel; this.types = types; this.cache = cache; this.config = config; this.data = data; }
    @Override public List<WorkflowTask> search(TaskSearchRequest request, AuthContext auth) {
        record Query(String status, String scope) {}
        List<Query> queries = request.statuses().isEmpty() ? SCOPES.stream().map(scope -> new Query("", scope)).toList() : request.statuses().stream().flatMap(status -> SCOPES.stream().map(scope -> new Query(status, scope))).toList();
        Map<String, WorkflowTask> result = new LinkedHashMap<>();
        for (JsonNode response : parallel.map(queries, query -> search(query.status(), query.scope(), auth))) for (JsonNode task : items(response)) result.putIfAbsent(text(task, "id"), task(task, auth));
        return new ArrayList<>(result.values());
    }
    @Override public List<WorkflowTask> findByDocument(String documentId, AuthContext auth) {
        JsonNode filters = object("attributes", object("documentId", object("value", documentId, "exact", true)));
        Map<String, WorkflowTask> result = new LinkedHashMap<>();
        for (JsonNode response : parallel.map(SCOPES, scope -> bpm.taskList("/system/v2/tasks:search", filters, Map.of("attributes", "*", "limit", 100, "offset", 0, "scope", scope), auth)))
            for (JsonNode task : items(response)) { WorkflowTask mapped = task(task, auth); if (documentId.equals(mapped.documentId()) && Set.of("NEW", "ASSIGNED", "STARTED").contains(mapped.status())) result.putIfAbsent(mapped.id(), mapped); }
        return new ArrayList<>(result.values());
    }
    @Override public WorkflowTask task(String id, AuthContext auth) {
        try { return task(bpm.taskList("/system/v1/user-tasks/" + encode(id), null, Map.of(), auth), auth); }
        catch (ApiException error) { if (!BpmClient.unavailable(error)) throw error; }
        return search(new TaskSearchRequest(Set.of("NEW", "ASSIGNED", "STARTED")), auth).stream().filter(task -> id.equals(task.id())).findFirst().orElse(null);
    }
    @Override public void start(String id, AuthContext auth) { successful(bpm.system("/system/v6/usertasks:start", client(id, auth), auth), id); }
    @Override public void complete(String id, Map<String, JsonNode> parameters, AuthContext auth) { var body = client(id, auth); var values = object(); parameters.forEach(values::set); body.set("parameters", values); successful(bpm.system("/system/v6/usertasks:complete", body, auth), id); }
    @Override public String roleLabel(String role, AuthContext auth) {
        if (role.isBlank() || !"true".equals(config.value("PLATFORM_V_ROLE_LABELS_ENABLED"))) return null;
        JsonNode labels = cache.load(auth, "role-labels", config.number("PLATFORM_V_ROLE_LABELS_CACHE_TTL_MS", 300000), () -> labels(auth)); return text(labels, role);
    }
    private JsonNode search(String status, String scope, AuthContext auth) { var filters = object(); if (!status.isEmpty()) filters.set("status", object("value", status)); return bpm.taskList("/system/v2/tasks:search", filters, Map.of("attributes", "*", "limit", 100, "offset", 0, "scope", scope), auth); }
    private WorkflowTask task(JsonNode raw, AuthContext auth) {
        String id = text(raw, "id"), type = attribute(raw, "documentType"), document = attribute(raw, "documentId"); if (type.isEmpty() && !document.isEmpty()) type = documentType(document, auth); JsonNode detail = details(raw, auth); List<WorkflowAction> actions = actions(type, detail);
        return new WorkflowTask(id, document, type, text(raw, "status"), login(raw), name(raw), role(raw), fallback(text(raw, "title"), text(raw, "type")), text(raw, "description"), map(raw.path("attributes")), actions);
    }
    private String documentType(String documentId, AuthContext auth) { return list(data.query("searchDocument", object("cond", "it.documentId == '" + documentId.replace("'", "''") + "'", "offset", 0, "limit", 2), auth).path("searchDocument").path("elems")).stream().filter(value -> documentId.equals(text(value, "documentId"))).map(value -> text(value.path("documentType"), "id")).findFirst().orElse(""); }
    private JsonNode details(JsonNode task, AuthContext auth) {
        if (text(task, "formType").equals("COMPLETIONS") && task.path("completions").path("options").isArray()) return task;
        String id = encode(text(task, "id")); try { return bpm.system("/system/v6/usertasks/" + id, null, auth); } catch (ApiException error) { if (!BpmClient.unavailable(error)) throw error; }
        try { return bpm.taskList("/system/v1/user-tasks/" + id, null, Map.of(), auth); } catch (ApiException error) { if (!BpmClient.unavailable(error)) throw error; } return task;
    }
    private List<WorkflowAction> actions(String type, JsonNode detail) {
        if (type.isEmpty() || !text(detail, "formType").equals("COMPLETIONS")) return List.of(); List<WorkflowAction> result = new ArrayList<>(); int index = 0;
        for (JsonNode option : list(detail.path("completions").path("options"))) { index++; JsonNode parameters = option.path("result"); if (!parameters.isObject() || parameters.isEmpty()) continue; String status = types.status(type, text(parameters, text(types.definition(type).workflow().path("completion"), "statusField"))); if (Objects.equals(status, types.initialStatus(type))) status = ""; String code = status.isEmpty() ? fallback(text(option, "label"), "completion_" + index) : status; result.add(new WorkflowAction(code, fallback(text(option, "label"), code), status, status.isEmpty() ? "success" : types.tone(type, status), map(parameters))); }
        return result;
    }
    private static Map<String, JsonNode> map(JsonNode node) { var result = new LinkedHashMap<String, JsonNode>(); node.properties().forEach(item -> result.put(item.getKey(), item.getValue())); return result; }
    private static List<JsonNode> items(JsonNode response) { for (String key : List.of("items", "content", "data", "tasks", "result")) { JsonNode value = response.path(key); if (value.isArray()) return list(value); if (value.isObject()) { List<JsonNode> nested = items(value); if (!nested.isEmpty()) return nested; } } return response.isArray() ? list(response) : List.of(); }
    private static String attribute(JsonNode task, String key) { return comparable(task.path("attributes").path(key)); }
    private static String login(JsonNode task) { return fallback(first(task, "assignee", "assigneeLogin", "executorLogin", "performerLogin"), text(task.path("executor"), "login")); }
    private static String name(JsonNode task) { return fallback(first(task, "assigneeName", "executorName", "performerName"), login(task)); }
    private static String role(JsonNode task) { return fallback(first(task, "executorRole", "role", "group", "candidateGroup"), text(task.path("executor"), "role")); }
    private static ObjectNode client(String id, AuthContext auth) { return object("clientLogin", auth.login(), "clientName", fallback(auth.fullName(), auth.login()), "userTaskIds", List.of(id)); }
    private static void successful(JsonNode result, String id) { if (list(result.path("operationResults")).stream().anyMatch(value -> id.equals(text(value, "userTaskId")) && "SUCCESS".equals(text(value, "responseType")))) return; if (list(result.path("successIds")).stream().anyMatch(value -> id.equals(text(value)))) return; throw new ApiException(502, "Провайдер не выполнил действие по задаче " + id); }
    private JsonNode labels(AuthContext auth) { for (String path : List.of("/system/v1/groups/roles", "/groups/roles")) try { var result = object(); collect(bpm.taskList(path, null, Map.of(), auth), result); return result; } catch (ApiException error) { if (!Set.of(403, 404, 405).contains(error.status())) throw error; } return object(); }
    private static void collect(JsonNode node, ObjectNode result) { if (node.isArray()) { node.forEach(item -> collect(item, result)); return; } if (!node.isObject()) return; String code = first(node, "name", "code", "role", "value", "id"), label = fallback(first(node, "label", "title", "displayName", "description"), code); if (!code.isEmpty()) result.put(code, label); for (String key : List.of("roles", "items", "content", "data", "result")) collect(node.path(key), result); }
}
