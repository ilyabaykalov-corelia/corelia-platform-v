package ru.corelia.platformv;

import java.util.Set;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthIdentityProvider;
import ru.corelia.config.CoreliaAuthConfig;

/** Привязывает identity проверки токена Corelia к настройкам Platform V. */
@Component
public final class PlatformVAuthIdentityProvider implements AuthIdentityProvider {
    private final PlatformVConfig platform;
    private final CoreliaAuthConfig corelia;

    public PlatformVAuthIdentityProvider(PlatformVConfig platform, CoreliaAuthConfig corelia) {
        this.platform = platform;
        this.corelia = corelia;
    }

    @Override public String issuer() {
        String generic = corelia.issuer();
        if (!generic.isBlank()) return generic;
        String configured = platform.value("PLATFORM_V_KEYCLOAK_ISSUER");
        return configured.isBlank() ? PlatformVConfig.trim(platform.value("PLATFORM_V_KEYCLOAK_BASE_URL")) : configured;
    }

    @Override public Set<String> audiences() { return corelia.audiences(); }
}
