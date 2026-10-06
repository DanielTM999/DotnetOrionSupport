package dtm.ide;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginManifestVersionTest {
    @Test
    void manifestUsesMavenVersionForEveryPlugin() throws Exception {
        try (var input = getClass().getResourceAsStream("/META-INF/plugin-configs.json")) {
            assertNotNull(input);
            var manifest = new ObjectMapper().readTree(input);
            String version = manifest.path("pluginCollectionVersion").asText();
            assertEquals(System.getProperty("plugin.version"), version);
            assertFalse(version.contains("${"));
            for (var plugin : manifest.path("plugins")) {
                assertEquals(version, plugin.path("version").asText());
            }
        }
    }
}
