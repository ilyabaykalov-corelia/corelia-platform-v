package ru.corelia.platformv;

import org.springframework.stereotype.Component;
import ru.corelia.configuration.ConfigurationException;
import ru.corelia.configuration.ConfigurationLoader;
import tools.jackson.databind.JsonNode;

/** Проверяет и предоставляет Platform V binding, отделённый от определения документа Corelia. */
@Component
public final class PlatformVDocumentBindings {
    private final ConfigurationLoader.LoadedConfiguration configuration;
    private final PlatformVOperationCatalog operations;

    public PlatformVDocumentBindings(ConfigurationLoader.LoadedConfiguration configuration, PlatformVOperationCatalog operations) {
        this.configuration = configuration;
        this.operations = operations;
        configuration.documentTypes().all().forEach(type -> binding(type.id()));
    }

    public JsonNode storage(String type) { return binding(type).path("storage"); }
    public JsonNode workflow(String type) { return binding(type).path("workflow"); }

    private JsonNode binding(String type) {
        JsonNode binding = configuration.providerBindings().get(type);
        if (binding == null || !binding.path("storage").isObject()) throw new ConfigurationException("Не задан Platform V binding: " + type);
        JsonNode storage = binding.path("storage");
        if (!"platform-v".equals(storage.path("provider").asString()) || !storage.path("entity").isTextual()
                || !storage.path("details").isTextual() || !storage.path("operations").isObject()
                || !storage.path("fields").isObject()) throw new ConfigurationException("Некорректный Platform V storage binding: " + type);
        for (String name : java.util.List.of("search", "update")) {
            JsonNode operation = storage.path("operations").path(name);
            if (!operation.isTextual() || operation.asString().isBlank() || !operations.contains(operation.asString()))
                throw new ConfigurationException("Не задана Platform V операция " + name + ": " + type);
        }
        JsonNode workflow = binding.path("workflow");
        if (!workflow.isObject()) throw new ConfigurationException("Некорректный Platform V workflow binding: " + type);
        if (!"configuration".equals(workflow.path("creationSource").asString())
                || !workflow.path("creationAction").isTextual()
                || !workflow.path("actions").isObject()
                || !workflow.path("processes").isObject())
            throw new ConfigurationException("Не задан workflow process binding: " + type);
        String action = workflow.path("creationAction").asString();
        String process = workflow.path("actions").path(action).asString(action);
        if (process.isBlank() || !workflow.path("processes").path(process).isTextual()
                || workflow.path("processes").path(process).asString().isBlank())
            throw new ConfigurationException("Не задан workflow process: " + type);
        return binding;
    }
}
