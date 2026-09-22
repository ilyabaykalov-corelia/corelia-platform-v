package ru.corelia.platformv;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/** Регистрирует Platform V adapter, когда он добавлен в deployment Corelia. */
@AutoConfiguration
@ConditionalOnProperty(name = "corelia.provider", havingValue = "platform-v", matchIfMissing = true)
@ComponentScan(basePackages = "ru.corelia.platformv")
public class PlatformVProviderConfiguration {}
