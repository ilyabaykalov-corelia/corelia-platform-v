package ru.corelia.platformv;

import static ru.corelia.support.Json.*;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.http.ApiException;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.provider.model.*;
import tools.jackson.databind.JsonNode;

/** Read-side адаптер версий и вложений Platform V. */
@Component
public final class PlatformDocumentVersionStore implements DocumentVersionStore {
    private final DataSpaceClient data;
    private final PlatformDocumentStore documents;
    private final DocumentTypeCatalog types;
    private final PlatformVDocumentBindings bindings;
    public PlatformDocumentVersionStore(DataSpaceClient data, PlatformDocumentStore documents, DocumentTypeCatalog types, PlatformVDocumentBindings bindings) {
        this.data = data; this.documents = documents; this.types = types; this.bindings = bindings;
    }

    @Override public String documentType(String documentId, AuthContext auth) {
        return search("searchDocument", "documentId", documentId, auth).stream().filter(value -> documentId.equals(text(value, "documentId")))
                .map(value -> text(value.path("documentType"), "id")).filter(value -> !value.isEmpty()).findFirst()
                .orElseThrow(() -> new ApiException(404, "Документ не найден"));
    }

    @Override public List<DocumentVersion> documentVersions(String documentId, AuthContext auth) {
        return search("searchDocumentVersion", "documentId", documentId, auth).stream()
                .filter(value -> documentId.equals(text(value, "documentId"))).map(this::version).toList();
    }

    @Override public List<AttachmentMetadata> attachments(String documentId, AuthContext auth) {
        return search("searchAttachment", "documentId", documentId, auth).stream()
                .filter(value -> documentId.equals(text(value, "documentId"))).map(this::attachment).toList();
    }

    @Override public DocumentVersionState state(String type, String documentId, AuthContext auth) {
        List<DocumentVersion> versions = documentVersions(documentId, auth);
        DocumentVersion current = versions.stream().max(Comparator.comparingInt(DocumentVersion::number)).orElse(null);
        return new DocumentVersionState(documents.get(type, documentId, auth), current, versions, attachments(documentId, auth));
    }

    @Override public IdempotencyReceipt receipt(String key, AuthContext auth) {
        return search("searchDocumentCommand", "commandKey", key, auth).stream().filter(value -> key.equals(text(value, "commandKey"))).findFirst()
                .map(value -> new IdempotencyReceipt(text(value, "requestHash"), parse(text(value, "response")))).orElse(null);
    }

    @Override public void commit(DocumentMutation mutation, AuthContext auth) {
        if (receipt(mutation.idempotencyKey(), auth) != null) return;
        JsonNode document = rawDocument(mutation.documentType(), mutation.documentId(), auth);
        int currentVersion = document.path("version").asInt();
        String currentToken = nullableText(document, "changeToken");
        if (currentVersion != mutation.expectedVersion()
                || (mutation.expectedChangeToken() != null
                && !Objects.equals(mutation.expectedChangeToken(), currentToken)))
            throw new ApiException(409, "Документ был изменен конкурентно");
        String token = text(mutation.response(), "changeToken"); if (token.isEmpty()) token = UUID.randomUUID().toString();
        var update = object("id", text(document, "id"), "version", mutation.createdVersion() == null ? mutation.expectedVersion() : mutation.createdVersion().number(),
                "changeToken", token);
        var compare = object("changeToken", document.hasNonNull("changeToken") ? document.path("changeToken") : null);
        if (mutation.createdVersion() != null && mutation.expectedVersion() == 0) {
            data.query("initializeDocumentVersion", object("id", text(document, "id"), "token", text(update, "changeToken"), "compare", compare,
                    "version", version(mutation.createdVersion(), text(document, "id"))), auth);
            return;
        }
        var vars = object("document", update, "compare", compare, "command", object("document", text(document, "id"), "commandKey", mutation.idempotencyKey(),
                "requestHash", mutation.requestHash(), "response", write(mutation.response())));
        String operation;
        if (mutation.createdVersion() != null) {
            if (mutation.closedVersion() == null || mutation.createdAttachment() != null || mutation.retiredAttachment() != null)
                throw new IllegalArgumentException("Некорректная транзакция атрибутов");
            String type = mutation.documentType(); operation = text(bindings.storage(type).path("operations"), "update");
            var details = mappedAttributes(type, mutation.attributes()); details.put("id", text(document, "detailsId")); vars.set("details", details);
            vars.set("detailsCompare", mappedAttributes(type, attributes(document.path("attributes")))); vars.set("version", version(mutation.createdVersion(), text(document, "id")));
            vars.set("previous", object("id", mutation.closedVersion().id(), "closedAt", timestamp(mutation.closedVersion().closedAt())));
        } else if (mutation.closedVersion() != null) {
            vars.set("previous", version(mutation.closedVersion()));
            if (mutation.createdAttachment() != null) {
                vars.set("file", attachment(mutation.createdAttachment())); operation = mutation.retiredAttachment() == null ? "commitDocumentFileUpload" : "commitDocumentFileReplace";
            } else {
                if (mutation.retiredAttachment() == null) throw new IllegalArgumentException("Отсутствует удаляемое вложение");
                operation = "commitDocumentFileDelete";
            }
            if (mutation.retiredAttachment() != null) vars.set("retired", object("id", rawAttachmentId(mutation.documentId(), mutation.retiredAttachment().id(), auth), "current", false));
        } else {
            if (mutation.createdAttachment() != null || mutation.retiredAttachment() != null) throw new IllegalArgumentException("Вложение требует манифест версии");
            operation = "commitDocumentNoChange";
        }
        data.query(operation, vars, auth);
    }

    private JsonNode rawDocument(String type, String documentId, AuthContext auth) {
        String operation = text(bindings.storage(type).path("operations"), "search");
        return list(data.query(operation, object("cond", "it.documentId == '" + documentId.replace("'", "''") + "'", "offset", 0, "limit", 2), auth)
                .path("searchDocument").path("elems")).stream().filter(value -> documentId.equals(text(value, "documentId")))
                .filter(value -> type.equals(text(value.path("documentType"), "id"))).map(value -> DocumentProjection.document(value, types, bindings))
                .findFirst().orElseThrow(() -> new ApiException(404, "Документ не найден"));
    }
    private String rawAttachmentId(String documentId, String attachmentId, AuthContext auth) {
        return search("searchAttachment", "documentId", documentId, auth).stream()
                .filter(value -> attachmentId.equals(first(value, "attachmentId", "id"))).map(value -> text(value, "id")).filter(value -> !value.isEmpty())
                .findFirst().orElseThrow(() -> new ApiException(502, "Не найдены метаданные вложения"));
    }

    private tools.jackson.databind.node.ObjectNode mappedAttributes(String type, Map<String, JsonNode> attributes) {
        var result = object(); JsonNode mapping = bindings.storage(type).path("fields");
        attributes.forEach((field, value) -> result.set(text(mapping, field), value)); return result;
    }
    private static Map<String, JsonNode> attributes(JsonNode value) {
        var result = new LinkedHashMap<String, JsonNode>(); value.properties().forEach(item -> result.put(item.getKey(), item.getValue())); return result;
    }
    private static tools.jackson.databind.node.ObjectNode version(DocumentVersion value) {
        var result = object("documentId", value.documentId(), "version", value.number(), "schemaVersion", value.schemaVersion(),
                "attributes", write(attributes(value.attributes())), "attachments", write(value.attachments().stream().map(PlatformDocumentVersionStore::attachment).toList()),
                "createdBy", value.createdBy(), "createdAt", timestamp(value.createdAt()));
        if (!value.id().isEmpty()) result.put("id", value.id()); if (value.closedAt() != null) result.put("closedAt", timestamp(value.closedAt())); return result;
    }
    private static tools.jackson.databind.node.ObjectNode version(DocumentVersion value, String document) {
        return version(value).put("document", document);
    }
    private static tools.jackson.databind.node.ObjectNode attachment(AttachmentMetadata value) {
        var result = object("attachmentId", value.id(), "logicalAttachmentId", value.logicalId(), "documentId", value.documentId(),
                "fileName", value.fileName(), "contentType", value.contentType(), "size", value.size(), "version", value.version(),
                "current", value.current(), "storageReference", value.storageReference().value());
        if (value.uploadedAt() != null) result.put("uploadedAt", timestamp(value.uploadedAt())); return result;
    }
    private static tools.jackson.databind.node.ObjectNode attributes(Map<String, JsonNode> values) { var result = object(); values.forEach(result::set); return result; }
    private static String timestamp(Instant value) { return value == null ? "" : value.toString(); }

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
        return new DocumentVersion(text(value, "id"), text(value, "documentId"), (int) number(value, "version", 0),
                (int) number(value, "schemaVersion", 0), mapped, text(value, "status"), instant(text(value, "createdAt")),
                text(value, "createdBy"), instant(text(value, "closedAt")), manifest(value));
    }

    private AttachmentMetadata attachment(JsonNode value) {
        return new AttachmentMetadata(first(value, "attachmentId", "id"), first(value, "logicalAttachmentId", "attachmentId", "id"),
                text(value, "documentId"), text(value, "fileName"), text(value, "contentType"), number(value, "size", 0),
                number(value, "version", 1), !value.path("current").isBoolean() || value.path("current").asBoolean(), instant(text(value, "uploadedAt")),
                new StorageReference(text(value, "storageReference")));
    }

    private List<AttachmentMetadata> manifest(JsonNode value) {
        String encoded = text(value, "attachments"); if (encoded.isEmpty()) return List.of();
        try { return list(parse(encoded)).stream().map(this::attachment).toList(); }
        catch (RuntimeException error) { throw new ApiException(502, "Некорректный манифест вложений документа"); }
    }

    private static Instant instant(String value) { try { return value.isBlank() ? null : Instant.parse(value); } catch (RuntimeException ignored) { return null; } }
    private static String nullableText(JsonNode node, String field) { return node.hasNonNull(field) ? text(node, field) : null; }
}
