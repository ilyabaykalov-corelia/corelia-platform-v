package ru.corelia.integration;

import static ru.corelia.config.CoreliaConfig.trim;
import static ru.corelia.support.Json.encode;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.support.LogJson;

import java.io.*;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Хранит содержимое вложений в DAM; постоянного файлового хранилища у сервиса вложений нет. */
@Component
public class FileStorageClient {
    private final CoreliaConfig config;
    private final PlatformHttp http;

    public FileStorageClient(CoreliaConfig config, PlatformHttp http) {
        this.config = config;
        this.http = http;
    }

    private String base() {
        String explicit = config.value("PLATFORM_V_FILE_STORAGE_BASE_URL");
        if (!explicit.isEmpty()) return trim(explicit);
        String base = config.bpmx();
        if (!config.dataspace().isEmpty()) {
            URI uri = URI.create(config.dataspace());
            int index = uri.getPath().indexOf("/api/ds/");
            if (index >= 0)
                base =
                        uri.getScheme()
                                + "://"
                                + uri.getRawAuthority()
                                + uri.getPath().substring(0, index);
        }
        return trim(config.required("PLATFORM_V_BPMX_BASE_URL", base))
                + "/dam-360/media-storage-api/external/lcp/v1/"
                + encode(config.tenant())
                + "/"
                + encode(config.appId());
    }

    public void upload(
            String path, String fileName, String contentType, byte[] bytes, AuthContext auth) {
        upload(path, fileName, contentType, new ByteArrayInputStream(bytes), bytes.length, auth);
    }

    public void upload(
            String path,
            String fileName,
            String contentType,
            InputStream content,
            long size,
            AuthContext auth) {
        String boundary = "SberNpf" + UUID.randomUUID().toString().replace("-", "");
        byte[] prefix = bytes(
                "--"
                        + boundary
                        + "\r\nContent-Disposition: form-data; name=\"size\"\r\n\r\n"
                        + size
                        + "\r\n");
        byte[] pathPart = bytes(
                "--"
                        + boundary
                        + "\r\nContent-Disposition: form-data; name=\"path\"\r\n\r\n"
                        + path
                        + "\r\n");
        String escapedName =
                fileName.replace("\r", "%0D").replace("\n", "%0A").replace("\"", "%22");
        byte[] filePart = bytes(
                "--"
                        + boundary
                        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                        + escapedName
                        + "\"\r\nContent-Type: "
                        + contentType.replace("\r", "").replace("\n", "")
                        + "\r\n\r\n");
        byte[] suffix = bytes("\r\n--" + boundary + "--\r\n");
        String url = base() + "/upload/files/";
        LogJson.info(
                "Uploading attachment to Platform V file storage",
                ru.corelia.support.Json.object(
                        "url", LogJson.upstreamTarget(url),
                        "path", path,
                        "fileName", fileName,
                        "contentType", contentType,
                        "size", size,
                        "sizeText", sizeText(size)));
        try {
            http.raw(
                    url,
                    "POST",
                    java.net.http.HttpRequest.BodyPublishers.concat(
                            java.net.http.HttpRequest.BodyPublishers.ofByteArray(prefix),
                            java.net.http.HttpRequest.BodyPublishers.ofByteArray(pathPart),
                            java.net.http.HttpRequest.BodyPublishers.ofByteArray(filePart),
                            java.net.http.HttpRequest.BodyPublishers.ofInputStream(() -> content),
                            java.net.http.HttpRequest.BodyPublishers.ofByteArray(suffix)),
                    Map.of(
                            "Authorization",
                            auth.authorization(),
                            "Accept",
                            "application/json",
                            "Content-Type",
                            "multipart/form-data; boundary=" + boundary));
        } catch (ApiException error) {
            if (error.status() != 413) throw error;
            LogJson.info(
                    "Platform V file storage rejected attachment by size",
                    ru.corelia.support.Json.object(
                            "url", LogJson.upstreamTarget(url),
                            "path", path,
                            "fileName", fileName,
                            "size", size,
                            "sizeText", sizeText(size)));
            throw new ApiException(
                    413,
                    "Файл \""
                            + fileName
                            + "\" превышает лимит загрузки Platform V/nginx для файлового"
                            + " хранилища. Нужно увеличить лимит на маршруте DAM/media-storage или"
                            + " загрузить файл меньшего размера.");
        }
    }

    public HttpResponse<byte[]> download(String path, AuthContext auth) {
        String encoded =
                String.join(
                        "/",
                        Arrays.stream(path.split("/", -1))
                                .map(ru.corelia.support.Json::encode)
                                .toList());
        String url = base() + "/download/" + encoded;
        LogJson.info(
                "Downloading attachment from Platform V file storage",
                ru.corelia.support.Json.object(
                        "url", LogJson.upstreamTarget(url),
                        "path", path));
        return http.raw(
                url,
                "GET",
                new byte[0],
                Map.of("Authorization", auth.authorization(), "Accept", "*/*"));
    }

    public static String safeFileName(String name) {
        String result = name.replaceAll("[\\\\/\\x00]", "_").trim();
        return result.isEmpty() ? "attachment.bin" : result;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024));
    }
}
