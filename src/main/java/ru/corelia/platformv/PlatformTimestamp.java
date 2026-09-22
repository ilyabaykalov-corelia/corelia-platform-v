package ru.corelia.platformv;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Преобразует временные значения DataSpace в канонический момент времени. */
final class PlatformTimestamp {
    private PlatformTimestamp() {}

    static Instant parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (RuntimeException ignoredOffset) {
                try {
                    // Corelia передаёт в поля LOCALDATETIME значения Instant в UTC.
                    return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
                } catch (RuntimeException ignoredLocal) {
                    return null;
                }
            }
        }
    }

    /** Представление UTC для полей DataSpace типа LOCALDATETIME. */
    static String localDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC).toString();
    }
}
