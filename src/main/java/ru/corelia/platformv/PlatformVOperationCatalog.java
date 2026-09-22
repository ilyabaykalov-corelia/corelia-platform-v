package ru.corelia.platformv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ru.corelia.configuration.ConfigurationException;
import ru.corelia.configuration.ConfigurationLoader;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Загружает GraphQL-операции, необходимые только адаптеру Platform V. */
@Component
public final class PlatformVOperationCatalog {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern GRAPHQL_NAME = Pattern.compile("^(?:query|mutation)\\s+([A-Za-z_][A-Za-z0-9_]*)(?=[\\s({])");
    private final Map<String, Operation> operations;

    public PlatformVOperationCatalog(ConfigurationLoader.LoadedConfiguration configuration) {
        operations = load(configuration.packageRoot());
    }

    public Operation require(String name) {
        Operation operation = operations.get(name);
        if (operation == null) throw new ConfigurationException("Не зарегистрирована Platform V операция: " + name);
        return operation;
    }

    public boolean contains(String name) { return operations.containsKey(name); }

    public static Map<String, Operation> load(Path packageRoot) {
        try {
            Path root = packageRoot.toRealPath();
            JsonNode manifest = JSON.readTree(Files.readString(root.resolve("configuration.json")));
            JsonNode sources = manifest.path("sources");
            String relative = requiredText(sources, "operations");
            Path directory = root.resolve(relative).normalize();
            if (!directory.startsWith(root) || !Files.isDirectory(directory) || !directory.toRealPath().startsWith(root))
                throw new ConfigurationException("Platform V operations directory is invalid");
            var result = new LinkedHashMap<String, Operation>();
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(Path::toString)).toList()) {
                    String display = root.relativize(file).toString().replace('\\', '/');
                    JsonNode definition = JSON.readTree(Files.readString(file));
                    if (!definition.isObject()) throw new ConfigurationException(display + ": Platform V operation must be an object");
                    String id = requiredText(definition, "id");
                    if (!NAME.matcher(id).matches()) throw new ConfigurationException(display + ": Invalid Platform V operation identifier");
                    if (!definition.path("multiaggregate").isBoolean()) throw new ConfigurationException(display + ": Missing multiaggregate flag: " + id);
                    String fileName = id.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replace('_', '-').toLowerCase(Locale.ROOT) + ".json";
                    if (!fileName.equals(file.getFileName().toString())) throw new ConfigurationException(display + ": Invalid Platform V operation file name");
                    String operationFile = requiredText(definition, "file");
                    Path graphql = file.getParent().resolve(operationFile).normalize();
                    if (!graphql.startsWith(root) || !Files.isRegularFile(graphql) || !graphql.toRealPath().startsWith(root))
                        throw new ConfigurationException(display + ": GraphQL resource does not exist: " + operationFile);
                    String text = Files.readString(graphql).trim();
                    var name = GRAPHQL_NAME.matcher(text);
                    if (!name.find() || !id.equals(name.group(1))) throw new ConfigurationException(display + ": Operation name mismatch: " + id);
                    if (result.putIfAbsent(id, new Operation(text, definition.path("multiaggregate").asBoolean())) != null)
                        throw new ConfigurationException("Duplicate Platform V operation: " + id);
                }
            }
            if (result.isEmpty()) throw new ConfigurationException("Platform V operations must not be empty");
            return Map.copyOf(result);
        } catch (IOException e) {
            throw new ConfigurationException("Cannot load Platform V operations", e);
        }
    }

    public record Operation(String text, boolean multiaggregate) {}

    private static String requiredText(JsonNode source, String field) {
        JsonNode value = source.path(field);
        if (!value.isTextual() || value.asString().isBlank()) throw new ConfigurationException("Missing required text: " + field);
        return value.asString();
    }
}
