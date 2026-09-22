package ru.corelia.platformv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class PlatformVConfigurationCompilerTest {
    @TempDir Path target;
    @Test void packagesCustomerOperationsWithoutChangingPermissionsBodies() throws Exception {
        Path source = Path.of("../../sber-npf-corelia-config");
        Path output = target.resolve("release");
        PlatformVConfigurationCompiler.compile(source, output, "0.1.0", Path.of("../../sber-npf-platform-v/ac.json"));
        assertEquals(Files.readString(Path.of("../../sber-npf-platform-v/ac.json")), Files.readString(output.resolve("corelia/platform-v-ac.json")));
        assertEquals(Files.readString(output.resolve("corelia/platform-v-ac.json")), Files.readString(output.resolve("platform-v/ac.json")));
        var operations = ru.corelia.platformv.PlatformVOperationCatalog.load(output.resolve("corelia"));
        assertEquals(
                JsonMapper.builder().build().readTree(Files.readString(source.resolve("configuration.json"))).path("compatibility"),
                JsonMapper.builder().build().readTree(Files.readString(output.resolve("corelia/configuration.json"))).path("compatibility"));
        var compiled = new ru.corelia.configuration.ConfigurationLoader().load(output.resolve("corelia"), "0.1.0");
        for (String type : List.of("PDS_CONTRACT", "KID_OPS")) {
            var storage = compiled.providerBindings().get(type).path("storage");
            assertTrue(storage.isObject(), type);
            for (String operation : List.of("search", "create", "update"))
                assertTrue(operations.containsKey(storage.path("operations").path(operation).asString()), type + "." + operation);
            assertTrue(compiled.providerBindings().get(type).path("workflow").isObject(), type);
        }
        assertConfiguredStatusesExistInDataSpace(compiled, "PDS_CONTRACT", "PdsContractStatus");
        assertConfiguredStatusesExistInDataSpace(compiled, "KID_OPS", "KidOpsStatus");
        var json = JsonMapper.builder().build();
        var permissions = json.readTree(Files.readString(output.resolve("platform-v/graphql-permissions.fragment.json")));
        var original = json.readTree(Files.readString(Path.of("../../sber-npf-platform-v/model.graphql-permissions.json")));
        for (var permission : permissions) {
            String name = permission.path("name").asString();
            assertEquals(operations.get(name).text(), permission.path("body").asString());
            boolean found = false;
            for (var existing : original) if (name.equals(existing.path("name").asString())) {
                assertEquals(existing.path("body").asString(), permission.path("body").asString());
                assertEquals(existing.path("checkForAnyPrivilege"), permission.path("checkForAnyPrivilege"));
                found = true;
            }
            assertTrue(found, name);
        }
        assertEquals(Files.readString(output.resolve("platform-v/graphql-permissions.fragment.json")), Files.readString(output.resolve("tests/allowed-requests.json")));
        assertThrows(ru.corelia.configuration.ConfigurationException.class, () -> PlatformVConfigurationCompiler.compile(source, output, "0.1.0", Path.of("../../sber-npf-platform-v/ac.json")));
        Path again = target.resolve("release-again");
        PlatformVConfigurationCompiler.compile(source, again, "0.1.0", Path.of("../../sber-npf-platform-v/ac.json"));
        assertEquals(Files.readString(output.resolve("manifest.json")), Files.readString(again.resolve("manifest.json")));
    }

    private static void assertConfiguredStatusesExistInDataSpace(
            ru.corelia.configuration.ConfigurationLoader.LoadedConfiguration compiled,
            String type,
            String enumName) throws Exception {
        String model = Files.readString(Path.of("../../sber-npf-platform-v/model.dataspace.xml"));
        Matcher enumBlock = Pattern.compile("<enum name=\"" + enumName + "\">(.*?)</enum>", Pattern.DOTALL).matcher(model);
        assertTrue(enumBlock.find(), enumName);
        Set<String> values = new HashSet<>();
        Matcher value = Pattern.compile("<value name=\"([^\"]+)\"/>").matcher(enumBlock.group(1));
        while (value.find()) values.add(value.group(1));
        var presentation = compiled.documentTypes().require(type).presentation();
        presentation.path("statuses").properties().forEach(status -> assertTrue(values.contains(status.getKey()), type + "." + status.getKey()));
        assertTrue(values.contains(presentation.path("initialStatus").asString()), type + ".initialStatus");
    }
}
