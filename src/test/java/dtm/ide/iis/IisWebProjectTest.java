package dtm.ide.iis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisWebProjectTest {

    private Path project(Path directory, String name, String content) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void detectsWebSdkProject(@TempDir Path directory) throws Exception {
        Path file = project(directory, "Api.csproj", """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup>
                </Project>
                """);

        assertTrue(IisWebProject.isWebProject(file));
        assertTrue(IisWebProject.isAspNetCore(file));
        assertFalse(IisWebProject.isClassicAspNet(file));
        assertTrue(IisWebProject.requiresPublish(file));
    }

    @Test
    void detectsClassicAspNetProject(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("web.config"), "<configuration/>");
        Path file = project(directory, "Legacy.csproj", """
                <Project ToolsVersion="15.0">
                  <PropertyGroup><TargetFrameworkVersion>v4.8</TargetFrameworkVersion></PropertyGroup>
                </Project>
                """);

        assertTrue(IisWebProject.isWebProject(file));
        assertTrue(IisWebProject.isClassicAspNet(file));
        assertFalse(IisWebProject.isAspNetCore(file));
        assertFalse(IisWebProject.requiresPublish(file));
        assertEquals(directory.toAbsolutePath().normalize(), IisWebProject.contentRoot(file, "Debug"));
    }

    @Test
    void ignoresNonWebProject(@TempDir Path directory) throws Exception {
        Path file = project(directory, "Lib.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup>
                </Project>
                """);

        assertFalse(IisWebProject.isWebProject(file));
    }

    @Test
    void detectsWwwrootAsWebProject(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("wwwroot"));
        Path file = project(directory, "Site.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup>
                </Project>
                """);

        assertTrue(IisWebProject.isWebProject(file));
    }

    @Test
    void readsHostingModel(@TempDir Path directory) throws Exception {
        Path inProcess = project(directory, "InProcess.csproj", """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup>
                </Project>
                """);
        Path outOfProcess = project(directory, "OutOfProcess.csproj", """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>net8.0</TargetFramework>
                    <AspNetCoreHostingModel>OutOfProcess</AspNetCoreHostingModel>
                  </PropertyGroup>
                </Project>
                """);

        assertEquals(IisWebProject.HostingModel.IN_PROCESS, IisWebProject.hostingModel(inProcess));
        assertEquals(IisWebProject.HostingModel.OUT_OF_PROCESS, IisWebProject.hostingModel(outOfProcess));
    }

    @Test
    void resolvesAssemblyNameAndPublishDirectory(@TempDir Path directory) throws Exception {
        Path file = project(directory, "Api.csproj", """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>net8.0</TargetFramework>
                    <AssemblyName>Custom.Api</AssemblyName>
                  </PropertyGroup>
                </Project>
                """);

        assertEquals("Custom.Api", IisWebProject.assemblyName(file));
        assertEquals("Api", IisWebProject.projectName(file));
        assertEquals(directory.resolve("bin").resolve("orion-iis").resolve("Release")
                        .toAbsolutePath().normalize(),
                IisWebProject.publishDirectory(file, "Release"));
    }
}
