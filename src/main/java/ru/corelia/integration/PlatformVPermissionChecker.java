package ru.corelia.integration;

import static ru.corelia.support.Json.*;
import org.springframework.stereotype.Component;
import ru.corelia.auth.AuthContext;
import ru.corelia.auth.PermissionChecker;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.configuration.ConfigurationException;
import ru.corelia.configuration.ConfigurationLoader;
import ru.corelia.http.ApiException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Read-only preflight using the same ac.json deployed to Platform V; mutations retain platform JWT checks. */
@Component
public final class PlatformVPermissionChecker implements PermissionChecker {
    private final Map<String, Set<String>> permissions;
    @org.springframework.beans.factory.annotation.Autowired
    public PlatformVPermissionChecker(CoreliaConfig environment, ConfigurationLoader.LoadedConfiguration configuration) {
        this(readAccess(environment), configuration);
    }
    public static PlatformVPermissionChecker fromText(String text, ConfigurationLoader.LoadedConfiguration configuration) {
        return new PlatformVPermissionChecker(text, configuration);
    }
    private static String readAccess(CoreliaConfig environment) {
        String override = environment.value("CORELIA_PLATFORM_V_AC_PATH");
        Path file = override.isBlank()
            ? Path.of(environment.value("CORELIA_CONFIG_PATH"), "platform-v-ac.json") : Path.of(override);
        try { return Files.readString(file); }
        catch (IOException e) { throw new ConfigurationException("Cannot read Platform V access control: " + file, e); }
    }
    private PlatformVPermissionChecker(String source, ConfigurationLoader.LoadedConfiguration configuration) {
        JsonNode access;
        try { access = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(source); }
        catch (RuntimeException e) { throw new ConfigurationException("Invalid Platform V access control", e); }
        if (access == null || !access.isObject() || !access.path("roles").isArray() || !access.path("scopes").isArray())
            throw new ConfigurationException("Invalid Platform V access control");
        Set<String> roles = new HashSet<>();
        for (JsonNode role : access.path("roles")) {
            String name = text(role, "name");
            if (name.isEmpty() || !roles.add(name)) throw new ConfigurationException("Invalid or duplicate Platform V role");
        }
        Map<String, Set<String>> scopes = new LinkedHashMap<>();
        for (JsonNode scope : access.path("scopes")) {
            String name = text(scope, "name");
            if (name.isEmpty() || !scope.path("roles").isArray()) throw new ConfigurationException("Invalid Platform V scope");
            Set<String> allowed = new HashSet<>();
            for (JsonNode role : scope.path("roles")) {
                if (!role.isTextual() || !roles.contains(text(role)) || !allowed.add(text(role))) throw new ConfigurationException("Invalid scope role: " + name);
            }
            if (scopes.putIfAbsent(name, Set.copyOf(allowed)) != null) throw new ConfigurationException("Duplicate Platform V scope: " + name);
        }
        for (var type : configuration.documentTypes().all()) {
            JsonNode rules = type.authorization();
            if (!rules.isObject()) throw new ConfigurationException("Missing authorization: " + type.id());
            for (String key : List.of("createPermission", "editPermission"))
                if (!scopes.containsKey(text(rules, key))) throw new ConfigurationException("Unknown permission: " + type.id() + "." + key);
            if (!roles.contains(text(rules, "executorRole"))) throw new ConfigurationException("Unknown executor role: " + type.id());
        }
        permissions = Map.copyOf(scopes);
    }
    @Override public void require(String permission, AuthContext auth) {
        Set<String> allowed = permissions.get(permission);
        if (allowed == null) throw new ConfigurationException("Unknown permission: " + permission);
        if (auth.roles().stream().noneMatch(allowed::contains)) throw new ApiException(403, "Недостаточно прав для выполнения действия");
    }
}
