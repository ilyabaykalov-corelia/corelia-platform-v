package ru.corelia.integration;

import static ru.corelia.support.Json.*;
import ru.corelia.document.KidOps;
import ru.corelia.http.ApiException;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** Реестр поддерживаемых видов и их контрактов. */
public final class DocumentTypes {
    public static final List<String> TYPES = List.of(PdsContract.TYPE, KidOps.TYPE);
    private DocumentTypes() {}
    public static void requireType(String type) { if (!TYPES.contains(type)) throw new ApiException(400, "Неизвестный вид документа: " + type); }
    public static String name(String type) { requireType(type); return type.equals(KidOps.TYPE) ? KidOps.NAME : PdsContract.NAME; }
    public static String details(String type) { requireType(type); return type.equals(KidOps.TYPE) ? "kidOps" : "pdsContract"; }
    public static List<String> fields(String type) { requireType(type); return type.equals(KidOps.TYPE) ? KidOps.FIELDS : PdsContract.FIELDS; }
    public static JsonNode validate(String type, JsonNode attrs, boolean partial) { requireType(type); return type.equals(KidOps.TYPE) ? KidOps.validate(attrs, partial) : PdsContract.validateAttributes(attrs, partial); }
    public static String status(String type, String value) {
        requireType(type);
        if (!type.equals(KidOps.TYPE)) return PdsContract.status(value);
        return KidOps.STATUSES.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(value.trim()) || e.getValue().equalsIgnoreCase(value.trim())).map(Map.Entry::getKey).findFirst().orElse(null);
    }
    public static String label(String type, String status) { return type.equals(KidOps.TYPE) ? KidOps.STATUSES.getOrDefault(status, status) : PdsContract.label(status); }
    public static JsonNode catalog() {
        var items = new ArrayList<JsonNode>(list(PdsContract.catalog().path("items")));
        var fields = object();
        String[] labels = {"Номер договора", "Дата договора", "Год подписания", "Фамилия", "Имя", "Отчество", "СНИЛС"};
        int[] lengths = {64, 10, 4, 40, 255, 256, 14};
        for (int i = 0; i < KidOps.FIELDS.size(); i++) {
            String key = KidOps.FIELDS.get(i);
            fields.set(key, object("label", labels[i], "type", key.equals("signingYear") ? "integer" : key.equals("contractDate") ? "date" : "string", "required", !key.equals("middleName"), "maxLength", lengths[i], "writable", true, "searchable", true));
        }
        items.add(object("code", KidOps.TYPE, "name", KidOps.NAME, "fields", fields, "statuses", KidOps.STATUSES, "initialAttachmentRequired", true,
                "operations", List.of("read", "search", "create", "update", "attachments")));
        return object("schemaVersion", 1, "items", items, "total", items.size());
    }
}
