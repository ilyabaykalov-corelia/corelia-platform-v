package ru.corelia.integration;

import static ru.corelia.support.Json.*;
import org.springframework.stereotype.Component;
import ru.corelia.configuration.*;
import ru.corelia.http.ApiException;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** Compatibility facade for metadata-driven public document contracts. */
@Component
public final class DocumentTypes {
    private final DocumentTypeRegistry registry;
    public DocumentTypes(ConfigurationLoader.LoadedConfiguration configuration) {
        registry = configuration.documentTypes();
        for (var type : registry.all()) {
            if (!"platform-v".equals(text(type.storage(), "provider"))) throw new ConfigurationException("Unsupported storage provider: " + type.id());
            for (String role : List.of("search", "update")) {
                String operation = text(type.storage().path("operations"), role);
                if (!configuration.operations().containsKey(operation)) throw new ConfigurationException("Missing storage operation " + type.id() + ": " + role);
                if (role.equals("update") && !configuration.operations().get(operation).multiaggregate()) throw new ConfigurationException("Attribute commits require multiaggregate: " + type.id());
            }
        }
    }
    public DocumentTypeDefinition definition(String type) {
        try { return registry.require(type); }
        catch (ConfigurationException e) { throw new ApiException(400, "Неизвестный вид документа: " + type); }
    }
    public List<String> types() { return registry.all().stream().map(DocumentTypeDefinition::id).toList(); }
    public void requireType(String type) { definition(type); }
    public String name(String type) { return definition(type).title(); }
    public String details(String type) { return text(definition(type).storage(), "details"); }
    public List<String> fields(String type) { return definition(type).schema().fields(); }
    public JsonNode validate(String type, JsonNode attrs, boolean partial) {
        try { return definition(type).validate(attrs, partial); }
        catch (AttributeValidationException e) { throw new ApiException(400, e.getMessage()); }
    }
    public String status(String type, String value) {
        JsonNode presentation = definition(type).presentation();
        for (var entry : presentation.path("aliases").properties()) if (entry.getKey().equalsIgnoreCase(value.trim())) return text(entry.getValue());
        for (var entry : presentation.path("statuses").properties()) if (entry.getKey().equalsIgnoreCase(value.trim()) || text(entry.getValue()).equalsIgnoreCase(value.trim())) return entry.getKey();
        return null;
    }
    public String label(String type, String status) { return fallback(text(definition(type).presentation().path("statuses"), status), status); }
    public String tone(String type, String status) { return fallback(text(definition(type).presentation().path("tones"), status), "success"); }
    public String initialStatus(String type) { return text(definition(type).presentation(), "initialStatus"); }
    public boolean initialAttachmentRequired(String type) { return definition(type).attachments().path("initialRequired").asBoolean(); }
    public JsonNode publicDefinition(String type) {
        requireType(type);
        return list(catalog().path("items")).stream().filter(item -> type.equals(text(item, "code"))).findFirst().orElseThrow();
    }
    public JsonNode catalog() {
        var items = new ArrayList<JsonNode>();
        for (var definition : registry.all()) {
            var fields = object();
            JsonNode schema = definition.schema().definition();
            for (var entry : schema.path("properties").properties()) {
                var field = copy(entry.getValue());
                field.put("label", fallback(text(field, "title"), entry.getKey()));
                if (text(field, "format").equals("date")) field.put("type", "date");
                field.put("required", list(schema.path("required")).stream().anyMatch(v -> text(v).equals(entry.getKey())));
                field.put("writable", true); field.put("searchable", list(definition.ui().path("searchFields")).stream().anyMatch(v -> text(v).equals(entry.getKey())));
                fields.set(entry.getKey(), field);
            }
            items.add(object("code", definition.id(), "name", definition.title(), "schema", schema, "ui", definition.ui(), "fields", fields,
                "statuses", definition.presentation().path("statuses"), "initialAttachmentRequired", initialAttachmentRequired(definition.id()),
                "operations", List.of("read", "search", "create", "update", "attachments")));
        }
        return object("schemaVersion", 1, "items", items, "total", items.size());
    }
}
