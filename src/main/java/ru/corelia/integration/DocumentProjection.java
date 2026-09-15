package ru.corelia.integration;

import static ru.corelia.support.Json.*;
import ru.corelia.http.ApiException;
import tools.jackson.databind.JsonNode;

/** Преобразование Document и дочерних реквизитов DataSpace в состояние ядра. */
public final class DocumentProjection {
    private DocumentProjection() {}
    public static JsonNode pds(JsonNode row) {
        JsonNode details = row.path("pdsContract");
        if (!details.isObject() || text(details, "id").isEmpty())
            throw new ApiException(502, "У документа отсутствуют реквизиты ПДС");
        var result = copy(row);
        result.remove("pdsContract");
        result.put("detailsId", text(details, "id"));
        result.put("status", text(details, "status"));
        var attrs = object();
        for (String field : PdsContract.FIELDS) attrs.set(field, details.path(field));
        result.set("attributes", attrs);
        return result;
    }
}
