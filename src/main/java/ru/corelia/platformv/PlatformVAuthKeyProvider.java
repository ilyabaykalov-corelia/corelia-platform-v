package ru.corelia.platformv;

import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthKeyProvider;

/** Предоставляет Corelia JWKS URL из конфигурации Platform V adapter-а. */
@Component
public final class PlatformVAuthKeyProvider implements AuthKeyProvider {
    private final PlatformVConfig config;

    public PlatformVAuthKeyProvider(PlatformVConfig config) { this.config = config; }

    @Override public String jwksUrl() {
        return PlatformVConfig.required("PLATFORM_V_KEYCLOAK_JWKS_URL", config.keycloak("JWKS"));
    }
}
