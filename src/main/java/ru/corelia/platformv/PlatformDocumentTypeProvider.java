package ru.corelia.platformv;

import static ru.corelia.support.Json.*;

import java.util.List;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.provider.DocumentTypeProvider;
import ru.corelia.provider.model.AvailableDocumentType;

/** Адаптер реестра разрешённых видов документов Platform V. */
@Component
public final class PlatformDocumentTypeProvider implements DocumentTypeProvider {
    private final DataSpaceClient data;
    public PlatformDocumentTypeProvider(DataSpaceClient data) { this.data = data; }
    @Override public List<AvailableDocumentType> available(AuthContext auth) {
        return list(data.query("refDocumentTypeListGet", object(), auth).path("searchDocumentType").path("elems")).stream()
                .map(value -> new AvailableDocumentType(text(value, "id"), text(value, "name"))).toList();
    }
}
