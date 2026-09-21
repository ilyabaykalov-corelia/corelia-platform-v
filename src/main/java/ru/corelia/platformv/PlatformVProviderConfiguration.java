package ru.corelia.platformv;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Регистрирует Platform V adapter, когда он добавлен в deployment Corelia. */
@AutoConfiguration
@ComponentScan(basePackages = {"ru.corelia.integration", "ru.corelia.platformv"})
public class PlatformVProviderConfiguration {}
