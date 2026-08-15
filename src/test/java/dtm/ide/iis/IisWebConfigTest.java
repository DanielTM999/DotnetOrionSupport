package dtm.ide.iis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisWebConfigTest {

    private static final String WEB_CONFIG = """
            <?xml version="1.0" encoding="utf-8"?>
            <configuration>
              <location path="." inheritInChildApplications="false">
                <system.webServer>
                  <handlers>
                    <add name="aspNetCore" path="*" verb="*" modules="AspNetCoreModuleV2" resourceType="Unspecified" />
                  </handlers>
                  <aspNetCore processPath="dotnet" arguments=".\\App.dll" stdoutLogEnabled="false"
                              stdoutLogFile=".\\logs\\stdout" hostingModel="inprocess" />
                </system.webServer>
              </location>
            </configuration>
            """;

    private Path webConfig(Path directory, String content) throws Exception {
        Path file = directory.resolve("web.config");
        Files.writeString(file, content);
        return file;
    }

    private Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ASPNETCORE_ENVIRONMENT", "Development");
        env.put("Custom__Value", "42");
        return env;
    }

    @Test
    void injectsEnvironmentVariablesAndEnablesStdoutLog(@TempDir Path directory) throws Exception {
        Path file = webConfig(directory, WEB_CONFIG);

        IisWebConfig.Result result = IisWebConfig.applyAspNetCore(directory, environment(), true);

        assertTrue(result.changed());
        assertTrue(result.stdoutLogEnabled());
        String written = Files.readString(file);
        assertTrue(written.contains("name=\"ASPNETCORE_ENVIRONMENT\""));
        assertTrue(written.contains("value=\"Development\""));
        assertTrue(written.contains("name=\"Custom__Value\""));
        assertTrue(written.contains("stdoutLogEnabled=\"true\""));
    }

    @Test
    void isIdempotentOnSecondApply(@TempDir Path directory) throws Exception {
        webConfig(directory, WEB_CONFIG);

        assertTrue(IisWebConfig.applyAspNetCore(directory, environment(), true).changed());
        IisWebConfig.Result second = IisWebConfig.applyAspNetCore(directory, environment(), true);

        assertFalse(second.changed(), "nada deve mudar quando o ambiente já está aplicado");
    }

    @Test
    void updatesExistingEnvironmentVariableValue(@TempDir Path directory) throws Exception {
        Path file = webConfig(directory, WEB_CONFIG);
        IisWebConfig.applyAspNetCore(directory, Map.of("ASPNETCORE_ENVIRONMENT", "Staging"), false);

        IisWebConfig.Result result = IisWebConfig.applyAspNetCore(directory,
                Map.of("ASPNETCORE_ENVIRONMENT", "Development"), false);

        assertTrue(result.changed());
        String written = Files.readString(file);
        assertTrue(written.contains("value=\"Development\""));
        assertFalse(written.contains("value=\"Staging\""));
    }

    @Test
    void skipsWhenWebConfigAbsent(@TempDir Path directory) {
        IisWebConfig.Result result = IisWebConfig.applyAspNetCore(directory, environment(), true);

        assertFalse(result.changed());
    }

    @Test
    void skipsWhenNoAspNetCoreElement(@TempDir Path directory) throws Exception {
        webConfig(directory, """
                <?xml version="1.0" encoding="utf-8"?>
                <configuration>
                  <system.webServer />
                </configuration>
                """);

        IisWebConfig.Result result = IisWebConfig.applyAspNetCore(directory, environment(), true);

        assertFalse(result.changed());
    }
}
