package ru.corelia.integration;

import static ru.corelia.support.Json.*;

import ru.corelia.http.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Контракт обмена для существующей модели ПДС в Platform V. Проверяет формат входных атрибутов;
 * права, переходы статусов и бизнес-правила исполняет платформа.
 */
public final class PdsContract {
    public static final String TYPE = "PDS_CONTRACT";
    public static final String NAME = "Договор ПДС";
    public static final String INITIAL_STATUS = "CREATED";
    public static final List<String> FIELDS = List.of("contractDate", "contractNumber", "snils");
    private static final Map<String, String> LABELS =
            Map.of(
                    "CREATED", "Создан",
                    "IN_WORK", "В работе",
                    "ON_APPROVAL", "На согласовании",
                    "NEEDS_REVISION", "Отправлено на доработку",
                    "APPROVED", "Согласован",
                    "REJECTED", "Отклонен");

    private PdsContract() {}

    public static void requireType(String type) {
        if (!TYPE.equals(type)) throw new ApiException(400, "Неизвестный тип документа: " + type);
    }

    /** Приводит отображаемое значение к коду, не определяя допустимость перехода. */
    public static String status(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("на доработке")) return "NEEDS_REVISION";
        if (normalized.equals("отклонён")) return "REJECTED";
        return LABELS.entrySet().stream()
                .filter(
                        e ->
                                e.getKey().equalsIgnoreCase(normalized)
                                        || e.getValue().equalsIgnoreCase(normalized))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    public static String normalizeStatus(String value) {
        String result = status(value);
        return result == null ? INITIAL_STATUS : result;
    }

    public static String label(String status) {
        return LABELS.getOrDefault(status, status);
    }

    public static String tone(String status) {
        return switch (status) {
            case "NEEDS_REVISION" -> "warning";
            case "REJECTED" -> "error";
            default -> "success";
        };
    }

    /** PATCH проверяет только переданные поля; обязательное поле нельзя обнулить. */
    public static ObjectNode validateAttributes(JsonNode attributes, boolean partial) {
        if (!attributes.isObject())
            throw new ApiException(400, "Поле attributes должно быть объектом");
        for (String name : attributes.propertyNames())
            if (!FIELDS.contains(name)) throw new ApiException(400, "Неизвестный атрибут: " + name);
        ObjectNode result = object();
        for (String field : FIELDS) {
            JsonNode value = attributes.path(field);
            if (value.isMissingNode() && partial) continue;
            if (value.isMissingNode()
                    || value.isNull()
                    || value.isTextual() && text(value).isEmpty())
                throw new ApiException(400, "Не заполнен обязательный атрибут: " + field);
            if (!value.isTextual()) throw new ApiException(400, "Неверный тип атрибута: " + field);
            String raw = text(value);
            if (field.equals("contractNumber") && raw.length() > 64)
                throw new ApiException(400, "Превышена длина атрибута: " + field);
            if (field.equals("contractDate") && !raw.matches("^\\d{4}-\\d{2}-\\d{2}$")
                    || field.equals("snils") && !raw.matches("^\\d{3}-\\d{3}-\\d{3} \\d{2}$"))
                throw new ApiException(400, "Неверный формат атрибута: " + field);
            result.put(field, raw);
        }
        return result;
    }

    /** Метаданные поддерживаемого API для отображения формы клиентом. */
    public static JsonNode catalog() {
        ObjectNode date = field("Дата договора", "date");
        date.put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
        ObjectNode number = field("Номер договора", "string");
        number.put("maxLength", 64);
        ObjectNode snils = field("СНИЛС", "string");
        snils.put("pattern", "^\\d{3}-\\d{3}-\\d{3} \\d{2}$");
        return object(
                "schemaVersion",
                1,
                "total",
                1,
                "items",
                List.of(
                        object(
                                "code",
                                TYPE,
                                "name",
                                NAME,
                                "fields",
                                object(
                                        "contractDate",
                                        date,
                                        "contractNumber",
                                        number,
                                        "snils",
                                        snils),
                                "operations",
                                List.of("read", "search", "create", "update", "attachments"),
                                "statuses",
                                LABELS)));
    }

    private static ObjectNode field(String label, String type) {
        return object(
                "label",
                label,
                "type",
                type,
                "required",
                true,
                "searchable",
                true,
                "writable",
                true);
    }
}
