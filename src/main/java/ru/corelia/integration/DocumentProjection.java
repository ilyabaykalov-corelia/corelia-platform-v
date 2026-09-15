package ru.corelia.integration;

import static ru.corelia.support.Json.*;
import ru.corelia.http.ApiException;
import tools.jackson.databind.JsonNode;

/** Преобразование Document и дочерних реквизитов DataSpace в состояние ядра. */
public final class DocumentProjection {
    private DocumentProjection() {}
    public static JsonNode document(JsonNode row) {
        String type = text(row.path("documentType"), "id");
        JsonNode details = row.path(DocumentTypes.details(type));
        if (!details.isObject() || text(details, "id").isEmpty())
            throw new ApiException(502, "У документа отсутствуют реквизиты его вида");
        var result = copy(row);
        result.remove("pdsContract"); result.remove("kidOps");
        result.put("detailsId", text(details, "id"));
        result.put("status", text(details, "status"));
        var attrs = object();
        for (String field : DocumentTypes.fields(type)) attrs.set(field, details.path(field));
        result.set("attributes", attrs);
        return result;
    }
}
