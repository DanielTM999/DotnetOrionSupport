package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetRunSupportTest {

    private static RunConfigurationData iisConfig(String type, Path projectFile) {
        return RunConfigurationData.builder()
                .type(type)
                .properties(Map.of(DotnetRunSupport.PROP_PROJECT, projectFile.toString()))
                .build();
    }

    private static Path webProject(Path directory, String tfm) throws Exception {
        Path file = directory.resolve("Site.csproj");
        Files.writeString(file, """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup><TargetFramework>%s</TargetFramework></PropertyGroup>
                </Project>
                """.formatted(tfm));
        return file;
    }

    @Test
    void allowsDebugForAspNetCoreOnFullIis(@TempDir Path directory) throws Exception {
        Path projectFile = webProject(directory, "net8.0");

        assertTrue(DotnetRunSupport.supportsDebug(
                iisConfig(DotnetRunSupport.TYPE_IIS, projectFile), directory));
        assertTrue(DotnetRunSupport.supportsDebug(
                iisConfig(DotnetRunSupport.TYPE_IIS_EXPRESS, projectFile), directory));
    }

    @Test
    void blocksDebugForClassicAspNet(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("web.config"), "<configuration/>");
        Path projectFile = directory.resolve("Legacy.csproj");
        Files.writeString(projectFile, """
                <Project ToolsVersion="15.0">
                  <PropertyGroup><TargetFrameworkVersion>v4.8</TargetFrameworkVersion></PropertyGroup>
                </Project>
                """);

        assertFalse(DotnetRunSupport.supportsDebug(
                iisConfig(DotnetRunSupport.TYPE_IIS, projectFile), directory));
    }

    @Test
    void blocksDebugForBuildAndTestConfigurations(@TempDir Path directory) throws Exception {
        Path projectFile = webProject(directory, "net8.0");

        assertFalse(DotnetRunSupport.supportsDebug(
                iisConfig(DotnetRunSupport.TYPE_BUILD, projectFile), directory));
        assertFalse(DotnetRunSupport.supportsDebug(
                iisConfig(DotnetRunSupport.TYPE_TEST, projectFile), directory));
    }

    @Test
    void enumeratesLaunchProfilesPerProjectInMultiProjectSolution(@TempDir Path directory) throws Exception {
        Path projA = consoleProjectWithProfiles(directory, "Alpha", "http", "https");
        Path projB = consoleProjectWithProfiles(directory, "Beta", "dev");

        DotnetRunSupport support = new DotnetRunSupport();
        support.bindProject(directory);
        Collection<RunConfigurationData> configs = support.staticRunConfigurations();

        assertTrue(hasRunProfile(configs, projA, "http"),
                "Deveria expor o profile 'http' do projeto Alpha");
        assertTrue(hasRunProfile(configs, projA, "https"),
                "Deveria expor o profile 'https' do projeto Alpha");
        assertTrue(hasRunProfile(configs, projB, "dev"),
                "Deveria expor o profile 'dev' do projeto Beta");
    }

    private static boolean hasRunProfile(Collection<RunConfigurationData> configs, Path projectFile, String profile) {
        String expectedProject = projectFile.toAbsolutePath().normalize().toString();
        return configs.stream().anyMatch(config -> {
            if (!DotnetRunSupport.TYPE_RUN.equals(config.getType()) || config.getProperties() == null) {
                return false;
            }
            Object project = config.getProperties().get(DotnetRunSupport.PROP_PROJECT);
            Object launch = config.getProperties().get(DotnetRunSupport.PROP_LAUNCH_PROFILE);
            return project != null
                    && Path.of(project.toString()).toAbsolutePath().normalize().toString().equals(expectedProject)
                    && profile.equals(launch);
        });
    }

    private static Path consoleProjectWithProfiles(Path root, String name, String... profiles) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Path projectFile = dir.resolve(name + ".csproj");
        Files.writeString(projectFile, """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <OutputType>Exe</OutputType>
                    <TargetFramework>net8.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);
        Path props = dir.resolve("Properties");
        Files.createDirectories(props);
        StringBuilder json = new StringBuilder("{\n  \"profiles\": {\n");
        for (int i = 0; i < profiles.length; i++) {
            json.append("    \"").append(profiles[i]).append("\": { \"commandName\": \"Project\" }");
            json.append(i < profiles.length - 1 ? ",\n" : "\n");
        }
        json.append("  }\n}\n");
        Files.writeString(props.resolve("launchSettings.json"), json.toString());
        return projectFile;
    }
}
