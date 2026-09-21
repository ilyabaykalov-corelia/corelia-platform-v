package ru.corelia.platformv;

import static ru.corelia.support.Json.*;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.integration.DataSpaceClient;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.provider.model.*;
import tools.jackson.databind.JsonNode;

/** Read-side адаптер версий и вложений Platform V. */
@Component
public final class PlatformDocumentVersionStore implements DocumentVersionStore {
    private final DataSpaceClient data;
    private final PlatformDocumentStore documents;
    public PlatformDocumentVersionStore(DataSpaceClient data, PlatformDocumentStore documents) { this.data = data; this.documents = documents; }

    @Override public List<DocumentVersion> versions(String documentId, AuthContext auth) {
        return search("searchDocumentVersion", "documentId", documentId, auth).stream().map(this::version).toList();
    }

    @Override public List<AttachmentMetadata> attachments(String documentId, AuthContext auth) {
        return search("searchAttachment", "documentId", documentId, auth).stream().map(this::attachment).toList();
    }

    @Override public DocumentVersionState state(String type, String documentId, AuthContext auth) {
        List<DocumentVersion> versions = versions(documentId, auth);
        DocumentVersion current = versions.stream().max(Comparator.comparingInt(DocumentVersion::number))
                .orElseThrow(() -> new ApiException(502, "Для документа отсутствуют версии"));
        return new DocumentVersionState(documents.get(type, documentId, auth), current, versions, attachments(documentId, auth));
    }

    @Override public IdempotencyReceipt receipt(String key, AuthContext auth) {
        return search("searchDocumentCommand", "commandKey", key, auth).stream().findFirst()
                .map(value -> new IdempotencyReceipt(text(value, "requestHash"), parse(text(value, "response")))).orElse(null);
    }

    @Override public void commit(DocumentMutation mutation, AuthContext auth) {
        throw new UnsupportedOperationException("Мутации версий будут подключены вместе с переводом DocumentVersionService на SPI");
    }

    private List<JsonNode> search(String operation, String field, String value, AuthContext auth) {
        var result = new ArrayList<JsonNode>();
        for (int offset = 0; ; ) {
            JsonNode page = data.query(operation, object("cond", "it." + field + " == '" + value.replace("'", "''") + "'", "offset", offset, "limit", 500), auth).path(operation);
            List<JsonNode> batch = list(page.path("elems")); result.addAll(batch); offset += batch.size();
            if (offset >= number(page, "count", offset)) return result;
            if (batch.isEmpty()) throw new ApiException(502, "Неполная выборка Platform V");
        }
    }

    private DocumentVersion version(JsonNode value) {
        JsonNode attributes = value.path("attributes");
        if (attributes.isTextual()) attributes = parse(text(attributes));
        var mapped = new LinkedHashMap<String, JsonNode>();
        attributes.properties().forEach(entry -> mapped.put(entry.getKey(), entry.getValue().deepCopy()));
        return new DocumentVersion(text(value, "id"), text(value, "documentId"), (int) number(value, "version", 0), mapped,
                text(value, "status"), instant(text(value, "createdAt")), text(value, "createdBy"));
    }

    private AttachmentMetadata attachment(JsonNode value) {
        return new AttachmentMetadata(first(value, "attachmentId", "id"), first(value, "logicalAttachmentId", "attachmentId", "id"),
                text(value, "documentId"), text(value, "fileName"), text(value, "contentType"), number(value, "size", 0),
                number(value, "version", 1), !value.path("current").isBoolean() || value.path("current").asBoolean(), instant(text(value, "uploadedAt")),
                new StorageReference(text(value, "storageReference")));
    }

    private static Instant instant(String value) { try { return value.isBlank() ? null : Instant.parse(value); } catch (RuntimeException ignored) { return null; } }
}
