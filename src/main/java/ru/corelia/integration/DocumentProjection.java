package ru.corelia.integration;

import static ru.corelia.support.Json.*;
import ru.corelia.http.ApiException;
import tools.jackson.databind.JsonNode;

/** Преобразование Document и дочерних реквизитов DataSpace в состояние ядра. */
public final class DocumentProjection {
    private DocumentProjection() {}
    public static JsonNode document(JsonNode row, DocumentTypes types) {
        String type = text(row.path("documentType"), "id");
        JsonNode details = row.path(types.details(type));
        if (!details.isObject() || text(details, "id").isEmpty())
            throw new ApiException(502, "У документа отсутствуют реквизиты его вида");
        var result = copy(row);
        for (String registered : types.types()) result.remove(types.details(registered));
        result.put("detailsId", text(details, "id"));
        result.put("status", text(details, "status"));
        var attrs = object();
        for (String field : types.fields(type)) attrs.set(field, details.path(text(types.definition(type).storage().path("fields"), field)));
        result.set("attributes", attrs);
        return result;
    }
}
