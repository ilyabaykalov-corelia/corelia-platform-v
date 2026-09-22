package ru.corelia.platformv;

import static ru.corelia.support.Json.*;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.http.ApiException;
import ru.corelia.integration.BpmClient;
import ru.corelia.integration.DataSpaceClient;
import ru.corelia.provider.WorkflowProvider;
import ru.corelia.provider.model.ProcessInstance;
import ru.corelia.provider.model.AttachmentMetadata;
import ru.corelia.provider.model.WorkflowContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Platform V реализация запуска и чтения процессов Corelia. */
@Component
public final class PlatformWorkflowProvider implements WorkflowProvider {
    private final BpmClient bpm; private final DataSpaceClient data; private final PlatformVConfig config; private final DocumentTypeCatalog types;
    public PlatformWorkflowProvider(BpmClient bpm, DataSpaceClient data, PlatformVConfig config, DocumentTypeCatalog types) { this.bpm = bpm; this.data = data; this.config = config; this.types = types; }
    @Override public ProcessInstance start(WorkflowContext context, AuthContext auth) {
        ObjectNode payload = object(); context.attributes().forEach(payload::set);
        payload.put("tenant", config.tenant()).put("appInstanceId", config.appId()).put("documentId", context.documentId()).put("documentType", context.documentType())
                .put("createdBy", context.createdBy()).put("createdAt", Instant.now().toString());
        if (context.initialAttachment() != null) {
            AttachmentMetadata file = context.initialAttachment();
            payload.put("initial_attachmentId", file.id()).put("initial_fileName", file.fileName()).put("initial_contentType", file.contentType())
                    .put("initial_size", file.size()).put("initial_storageReference", file.storageReference().value());
            if (file.uploadedAt() != null) payload.put("initial_uploadedAt", file.uploadedAt().toString());
            payload.put("creationKey", context.creationKey()).put("creationHash", context.creationHash());
        }
        ObjectNode external = object("documentId", context.documentId(), "documentType", context.documentType(), "tenant", config.tenant(), "appInstanceId", config.appId());
        for (JsonNode field : list(types.definition(context.documentType()).workflow().path("externalFields"))) external.set(text(field), context.attributes().get(text(field)));
        JsonNode result = bpm.process("/processes/" + encode(processId(context.documentType(), auth)) + ":start", object("businessKey", context.externalBusinessKey(), "payload", payload, "externalIds", external), auth);
        incident(result); return new ProcessInstance(text(result, "id"), context.documentId(), text(result, "state"));
    }
    @Override public ProcessInstance process(String id, AuthContext auth) {
        JsonNode result = bpm.process("/instances/" + encode(id), null, auth); incident(result); return new ProcessInstance(text(result, "id"), text(result, "businessKey"), text(result, "state"));
    }
    private String processId(String type, AuthContext auth) {
        JsonNode workflow = types.definition(type).workflow();
        if (text(workflow, "creationSource").equals("configuration")) return text(workflow.path("processes"), text(workflow.path("actions"), text(workflow, "creationAction")));
        for (int offset = 0; ; offset += 500) {
            JsonNode page = data.query("searchDocumentProcessSettings", object("offset", offset, "limit", 500), auth).path("searchDocumentProcessSettings");
            for (JsonNode row : list(page.path("elems"))) if (row.path("enabled").asBoolean() && type.equals(text(row.path("documentType"), "id"))) {
                String id = text(row, "processId"); if (!id.isEmpty()) return id;
            }
            if (offset + 500 >= number(page, "count", offset + 500)) break;
        }
        throw new ApiException(400, "Для вида документа " + type + " не настроен активный процесс создания");
    }
    private static void incident(JsonNode value) {
        JsonNode activity = list(value.path("currentActivities")).stream().filter(item -> item.path("isIncident").asBoolean()).findFirst().orElse(object());
        if (value.path("isIncident").asBoolean() || !activity.isEmpty())
            throw new ApiException(502, "Процесс не выполнился и упал на " + fallback(first(activity, "definitionId", "name"), "неизвестной активности"));
    }
}
