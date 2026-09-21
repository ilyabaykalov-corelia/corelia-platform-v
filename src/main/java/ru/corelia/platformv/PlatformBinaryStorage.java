package ru.corelia.platformv;

import java.io.InputStream;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.integration.FileStorageClient;
import ru.corelia.provider.BinaryStorage;
import ru.corelia.provider.model.BinaryStoreRequest;
import ru.corelia.provider.model.StorageReference;
import ru.corelia.provider.model.StoredFile;

/** Изолирует формат DAM reference в Platform V binary adapter. */
@Component
public final class PlatformBinaryStorage implements BinaryStorage {
    private static final String PREFIX = "platform-v-dam:";
    private final FileStorageClient files;
    public PlatformBinaryStorage(FileStorageClient files) { this.files = files; }
    @Override public StoredFile store(BinaryStoreRequest request, InputStream content, AuthContext auth) {
        String safe = FileStorageClient.safeFileName(request.fileName());
        String path = "documents/" + request.documentId() + "/uploads/" + request.attachmentId()
                + "/" + request.checksum() + "/" + safe;
        files.upload(path, safe, request.contentType(), content, request.size(), auth);
        return new StoredFile(new StorageReference(PREFIX + path), request.checksum(), request.size(), request.contentType());
    }
    @Override public InputStream read(StorageReference reference, AuthContext auth) {
        String value = reference.value(); if (!value.startsWith(PREFIX)) throw new IllegalArgumentException("Storage reference не принадлежит Platform V");
        return files.download(value.substring(PREFIX.length()).replaceFirst("^/+", ""), auth).body();
    }
}
