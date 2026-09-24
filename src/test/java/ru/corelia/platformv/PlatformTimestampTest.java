package ru.corelia.platformv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlatformTimestampTest {
    @Test void acceptsCanonicalUtcTimestamp() {
        assertEquals(Instant.parse("2026-09-22T12:34:56.789Z"), PlatformTimestamp.parse("2026-09-22T12:34:56.789Z"));
    }

    @Test void acceptsTimestampWithOffset() {
        assertEquals(Instant.parse("2026-09-22T09:34:56Z"), PlatformTimestamp.parse("2026-09-22T12:34:56+03:00"));
    }

    @Test void interpretsDataspaceLocalDateTimeAsUtc() {
        assertEquals(Instant.parse("2026-09-22T12:34:56.789Z"), PlatformTimestamp.parse("2026-09-22T12:34:56.789"));
    }

    @Test void writesUtcInstantAsDataspaceLocalDateTime() {
        assertEquals("2026-09-22T12:34:56.789", PlatformTimestamp.localDateTime(Instant.parse("2026-09-22T12:34:56.789123456Z")));
    }

    @Test void keepsMissingAndInvalidValuesAbsent() {
        assertNull(PlatformTimestamp.parse(""));
        assertNull(PlatformTimestamp.parse("not-a-timestamp"));
    }
}
