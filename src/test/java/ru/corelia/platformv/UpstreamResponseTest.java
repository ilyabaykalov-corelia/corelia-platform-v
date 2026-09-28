package ru.corelia.platformv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;

import org.junit.jupiter.api.Test;

import ru.corelia.http.ApiException;
import ru.corelia.transport.UpstreamResponse;

class UpstreamResponseTest {
    @Test
    void rejectsResponseWithOversizedContentLengthBeforeReadingBody() {
        var input = new ByteArrayInputStream(new byte[0]);
        var response = response(input, Map.of("Content-Length", List.of("11")));

        assertThrows(ApiException.class, () -> UpstreamResponse.read(response, 10));
    }

    @Test
    void rejectsChunkedResponseWhenActualBodyExceedsLimit() {
        var response = response(new ByteArrayInputStream(new byte[11]), Map.of());

        assertThrows(ApiException.class, () -> UpstreamResponse.read(response, 10));
    }

    @Test
    void readsResponseWithinLimit() throws Exception {
        var response = response(new ByteArrayInputStream(new byte[] {1, 2, 3}), Map.of());

        assertArrayEquals(new byte[] {1, 2, 3}, UpstreamResponse.read(response, 3).body());
    }

    private static HttpResponse<InputStream> response(
            InputStream body, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return 200; }
            @Override public HttpRequest request() { return HttpRequest.newBuilder(URI.create("https://example.test")).build(); }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(headers, (name, value) -> true); }
            @Override public InputStream body() { return body; }
            @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("https://example.test"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
