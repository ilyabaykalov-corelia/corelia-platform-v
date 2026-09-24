package ru.corelia.platformv;

import static ru.corelia.support.Json.*;

import java.util.*;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.provider.AttachmentCatalog;
import ru.corelia.provider.model.AttachmentMetadata;
import ru.corelia.provider.model.StorageReference;
import tools.jackson.databind.JsonNode;

/** Platform V поиск attachment metadata, изолированный от business service. */
@Component
public final class PlatformAttachmentCatalog implements AttachmentCatalog {
    private final DataSpaceClient data;
    public PlatformAttachmentCatalog(DataSpaceClient data) { this.data = data; }
    @Override public AttachmentMetadata find(String id, AuthContext auth) {
        return all(auth).stream().filter(value -> id.equals(first(value, "attachmentId", "id"))).findFirst().map(this::attachment).orElseThrow(() -> new ApiException(404, "Вложение не найдено"));
    }
    @Override public List<AttachmentMetadata> attachmentVersions(String id, AuthContext auth) {
        AttachmentMetadata current = find(id, auth); return all(auth).stream().map(this::attachment).filter(value -> current.documentId().equals(value.documentId()) && current.logicalId().equals(value.logicalId())).toList();
    }
    private List<JsonNode> all(AuthContext auth) { var result = new ArrayList<JsonNode>(); for (int offset = 0; ; offset += 500) { JsonNode page = data.query("searchAttachment", object("offset", offset, "limit", 500), auth).path("searchAttachment"); List<JsonNode> batch = list(page.path("elems")); result.addAll(batch); if (offset + batch.size() >= number(page, "count", offset + batch.size())) return result; if (batch.isEmpty()) throw new ApiException(502, "Неполная выборка вложений"); } }
    private AttachmentMetadata attachment(JsonNode value) { return new AttachmentMetadata(first(value, "attachmentId", "id"), first(value, "logicalAttachmentId", "attachmentId", "id"), text(value, "documentId"), text(value, "fileName"), text(value, "contentType"), number(value, "size", 0), number(value, "version", 1), !value.path("current").isBoolean() || value.path("current").asBoolean(), PlatformTimestamp.parse(text(value, "uploadedAt")), new StorageReference(text(value, "storageReference"))); }
}
