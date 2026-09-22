package ru.corelia.platformv;

import static ru.corelia.support.Json.*;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.http.ApiException;
import ru.corelia.provider.DocumentStore;
import ru.corelia.provider.model.DocumentSearchRequest;
import ru.corelia.provider.model.DocumentSearchResult;
import ru.corelia.provider.model.DocumentSnapshot;
import tools.jackson.databind.JsonNode;

/** Адаптер Platform V, преобразующий документы DataSpace в канонический снимок Corelia. */
@Component
public final class PlatformDocumentStore implements DocumentStore {
    private final DocumentTypeCatalog types;
    private final DataSpaceClient data;

    public PlatformDocumentStore(DocumentTypeCatalog types, DataSpaceClient data) {
        this.types = types;
        this.data = data;
    }

    @Override
    public DocumentSearchResult search(DocumentSearchRequest request, AuthContext auth) {
        String type = request.typeCode();
        types.requireType(type);
        var result = new ArrayList<DocumentSnapshot>();
        for (int offset = 0; ; ) {
            JsonNode page = data.query(text(types.definition(type).storage().path("operations"), "search"),
                    object("cond", condition("documentType.id", type), "offset", offset, "limit", 500), auth).path("searchDocument");
            List<JsonNode> rows = list(page.path("elems"));
            rows.stream().filter(row -> type.equals(text(row.path("documentType"), "id")))
                    .map(row -> DocumentProjection.document(row, types)).map(this::snapshot).forEach(result::add);
            offset += rows.size();
            if (rows.isEmpty() || offset >= number(page, "count", offset)) break;
        }
        int from = Math.min(Math.max(0, request.offset()), result.size());
        int to = Math.min(result.size(), from + Math.max(1, request.limit()));
        return new DocumentSearchResult(result.subList(from, to), result.size());
    }

    @Override
    public DocumentSnapshot get(String typeCode, String documentId, AuthContext auth) {
        types.requireType(typeCode);
        JsonNode page = data.query(text(types.definition(typeCode).storage().path("operations"), "search"),
                object("cond", condition("documentId", documentId), "offset", 0, "limit", 2), auth).path("searchDocument");
        return list(page.path("elems")).stream().filter(row -> documentId.equals(text(row, "documentId")))
                .filter(row -> typeCode.equals(text(row.path("documentType"), "id")))
                .map(row -> DocumentProjection.document(row, types)).map(this::snapshot).findFirst()
                .orElseThrow(() -> new ApiException(404, "Документ не найден"));
    }

    private DocumentSnapshot snapshot(JsonNode document) {
        var attributes = new LinkedHashMap<String, JsonNode>();
        document.path("attributes").properties().forEach(item -> attributes.put(item.getKey(), item.getValue().deepCopy()));
        String timestamp = text(document, "createdAt");
        Instant createdAt;
        try { createdAt = timestamp.isEmpty() ? null : Instant.parse(timestamp); }
        catch (RuntimeException ignored) { createdAt = null; }
        return new DocumentSnapshot(text(document, "documentId"), text(document.path("documentType"), "id"),
                text(document, "status"), (int) number(document, "version", 0), attributes,
                text(document, "createdBy"), createdAt, text(document, "changeToken"));
    }

    private static String condition(String field, String value) {
        return "it." + field + " == '" + value.replace("'", "''") + "'";
    }
}
