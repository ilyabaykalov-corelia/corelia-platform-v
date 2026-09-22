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
        for (JsonNode operation : storage.path("operations")) {
            if (!operation.isTextual() || !operations.contains(operation.asString()))
                throw new ConfigurationException("Неизвестная Platform V операция: " + type);
        }
        if (!binding.path("workflow").isObject()) throw new ConfigurationException("Некорректный Platform V workflow binding: " + type);
        return binding;
    }
}
