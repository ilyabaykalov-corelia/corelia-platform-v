package ru.corelia.platformv;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import java.util.EnumSet;
import ru.corelia.provider.ProviderCapability;
import ru.corelia.provider.ProviderDescriptor;
import ru.corelia.provider.ProviderStartupValidator;
import ru.corelia.config.CoreliaRuntimeConfig;

/** Регистрирует Platform V adapter, когда он добавлен в deployment Corelia. */
@AutoConfiguration
@ConditionalOnProperty(name = "corelia.provider", havingValue = "platform-v", matchIfMissing = true)
@ComponentScan(basePackages = "ru.corelia.platformv")
public class PlatformVProviderConfiguration {
    @Bean ProviderDescriptor platformVProviderDescriptor(@org.springframework.beans.factory.annotation.Qualifier("coreliaRuntimeConfig") CoreliaRuntimeConfig config) {
        ProviderDescriptor descriptor = new ProviderDescriptor() {
            public String id() { return "platform-v"; }
            public java.util.Set<ProviderCapability> capabilities() { return EnumSet.allOf(ProviderCapability.class); }
        };
        ProviderStartupValidator.validate(config.provider(), descriptor);
        return descriptor;
    }
}
