package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
