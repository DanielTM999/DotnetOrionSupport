package dtm.ide.iis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.run.LaunchSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisLaunchSettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path writeProject(Path directory, String launchSettings) throws Exception {
        Path projectFile = directory.resolve("WebApp.csproj");
        Files.writeString(projectFile, """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>net8.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);
        if (launchSettings != null) {
            Path properties = directory.resolve("Properties");
            Files.createDirectories(properties);
            Files.writeString(properties.resolve("launchSettings.json"), launchSettings);
        }
        return projectFile;
    }

    @Test
    void readsVisualStudioIisSettings(@TempDir Path directory) throws Exception {
        Path projectFile = writeProject(directory, """
                {
                  "iisSettings": {
                    "windowsAuthentication": true,
                    "anonymousAuthentication": false,
                    "iisExpress": {
                      "applicationUrl": "http://localhost:12345",
                      "sslPort": 44321
                    },
                    "iis": { "applicationUrl": "http://localhost/WebApp" }
                  },
                  "profiles": {
                    "IIS Express": {
                      "commandName": "IISExpress",
                      "launchBrowser": true,
                      "launchUrl": "swagger",
                      "environmentVariables": { "ASPNETCORE_ENVIRONMENT": "Development" }
                    }
                  }
                }
                """);

        IisLaunchSettings.Settings settings = IisLaunchSettings.read(projectFile);

        assertTrue(settings.windowsAuthentication());
        assertFalse(settings.anonymousAuthentication());
        assertEquals("http://localhost:12345", settings.iisExpressApplicationUrl());
        assertEquals(44321, settings.sslPort());
        assertEquals("http://localhost/WebApp", settings.iisApplicationUrl());
    }

    @Test
    void readsIisExpressProfile(@TempDir Path directory) throws Exception {
        Path projectFile = writeProject(directory, """
                {
                  "profiles": {
                    "IIS Express": {
                      "commandName": "IISExpress",
                      "launchBrowser": true,
                      "launchUrl": "swagger"
                    },
                    "http": { "commandName": "Project" }
                  }
                }
                """);

        List<LaunchSettings.Profile> iisProfiles = LaunchSettings.iisProfiles(projectFile);

        assertEquals(1, iisProfiles.size());
        LaunchSettings.Profile profile = iisProfiles.getFirst();
        assertTrue(profile.isIisExpressCommand());
        assertTrue(profile.launchBrowser());
        assertEquals("swagger", profile.launchUrl());
        assertEquals(1, LaunchSettings.runnableProfiles(projectFile).size());
    }

    @Test
    void writePreservesUnknownFields(@TempDir Path directory) throws Exception {
        Path projectFile = writeProject(directory, """
                {
                  "$schema": "https://json.schemastore.org/launchsettings.json",
                  "profiles": {
                    "http": {
                      "commandName": "Project",
                      "dotnetRunMessages": true,
                      "applicationUrl": "http://localhost:5080"
                    }
                  }
                }
                """);

        IisLaunchSettings.write(projectFile,
                new IisLaunchSettings.Settings(false, true, "http://localhost:31000", 44310, ""),
                List.of(new IisLaunchSettings.ProfileSpec("IIS Express", "IISExpress", "",
                        "swagger", true, Map.of("ASPNETCORE_ENVIRONMENT", "Development"))));

        JsonNode root = MAPPER.readTree(Files.readString(IisLaunchSettings.file(projectFile)));

        assertTrue(root.path("profiles").path("http").path("dotnetRunMessages").asBoolean());
        assertEquals("http://localhost:5080",
                root.path("profiles").path("http").path("applicationUrl").asText());
        assertEquals("IISExpress", root.path("profiles").path("IIS Express").path("commandName").asText());
        assertEquals("swagger", root.path("profiles").path("IIS Express").path("launchUrl").asText());
        assertEquals("Development", root.path("profiles").path("IIS Express")
                .path("environmentVariables").path("ASPNETCORE_ENVIRONMENT").asText());
        assertEquals("http://localhost:31000",
                root.path("iisSettings").path("iisExpress").path("applicationUrl").asText());
        assertEquals(44310, root.path("iisSettings").path("iisExpress").path("sslPort").asInt());
    }

    @Test
    void writeCreatesFileWhenMissing(@TempDir Path directory) throws Exception {
        Path projectFile = writeProject(directory, null);

        IisLaunchSettings.write(projectFile, IisLaunchSettings.Settings.defaults("WebApp"),
                List.of(new IisLaunchSettings.ProfileSpec("IIS", "IIS", "http://localhost/WebApp",
                        "", false, Map.of())));

        Path file = IisLaunchSettings.file(projectFile);
        assertTrue(Files.isRegularFile(file));
        JsonNode root = MAPPER.readTree(Files.readString(file));
        assertEquals("IIS", root.path("profiles").path("IIS").path("commandName").asText());
        assertEquals("http://localhost/WebApp",
                root.path("profiles").path("IIS").path("applicationUrl").asText());
    }

    @Test
    void derivesBindingsFromSettings() {
        IisLaunchSettings.Settings settings = new IisLaunchSettings.Settings(
                false, true, "http://localhost:5001", 44399, "");

        List<IisBinding> bindings = IisLaunchSettings.bindingsOf(settings, "WebApp");

        assertEquals(2, bindings.size());
        assertEquals("5001", bindings.get(0).port());
        assertEquals("https", bindings.get(1).protocol());
        assertEquals("44399", bindings.get(1).port());
    }

    @Test
    void fallsBackToStablePortWhenUnset() {
        IisLaunchSettings.Settings settings = new IisLaunchSettings.Settings(false, true, "", 0, "");

        List<IisBinding> first = IisLaunchSettings.bindingsOf(settings, "WebApp");
        List<IisBinding> second = IisLaunchSettings.bindingsOf(settings, "WebApp");

        assertEquals(1, first.size());
        assertEquals(first.getFirst().port(), second.getFirst().port());
        int port = Integer.parseInt(first.getFirst().port());
        assertTrue(port >= 30000 && port < 50000);
    }

    @Test
    void parsesIisTargetFromApplicationUrl() {
        IisLaunchSettings.IisTarget target = IisLaunchSettings.iisTarget(
                new IisLaunchSettings.Settings(false, true, "", 0, "http://localhost/Shop"), "WebApp");

        assertEquals("/Shop", target.applicationPath());
        assertEquals("80", target.binding().port());
        assertEquals("localhost", target.binding().hostName());
    }

    @Test
    void defaultsIisTargetToProjectName() {
        IisLaunchSettings.IisTarget target = IisLaunchSettings.iisTarget(
                new IisLaunchSettings.Settings(false, true, "", 0, ""), "WebApp");

        assertEquals("/WebApp", target.applicationPath());
    }
}
