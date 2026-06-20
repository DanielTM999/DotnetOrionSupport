package dtm.ide.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetFrameworkTest {

    @Test
    void classifiesNetFrameworkMonikers() {
        assertTrue(TargetFramework.isNetFramework("net48"));
        assertTrue(TargetFramework.isNetFramework("net472"));
        assertTrue(TargetFramework.isNetFramework("net40"));
        assertTrue(TargetFramework.isNetFramework("net20"));
        assertTrue(TargetFramework.isNetFramework("v4.7.2"));
        assertFalse(TargetFramework.isNetFramework("net8.0"));
        assertFalse(TargetFramework.isNetFramework("net6.0"));
        assertFalse(TargetFramework.isNetFramework("netcoreapp3.1"));
        assertFalse(TargetFramework.isNetFramework("netstandard2.0"));
    }

    @Test
    void classifiesModernMonikers() {
        assertTrue(TargetFramework.isModern("net8.0"));
        assertTrue(TargetFramework.isModern("net6.0-windows"));
        assertTrue(TargetFramework.isModern("netcoreapp3.1"));
        assertFalse(TargetFramework.isModern("net48"));
        assertFalse(TargetFramework.isModern("v4.7.2"));
        assertEquals(10, TargetFramework.modernMajor("net10.0").orElseThrow());
        assertEquals(6, TargetFramework.modernMajor("net6.0-windows").orElseThrow());
        assertEquals(3, TargetFramework.modernMajor("netcoreapp3.1").orElseThrow());
        assertTrue(TargetFramework.isNetStandard("netstandard2.0"));
        assertFalse(TargetFramework.isRunnableModernOnHost("netstandard2.0", true));
        assertFalse(TargetFramework.isRunnableModernOnHost("net8.0-windows", false));
        assertTrue(TargetFramework.isRunnableModernOnHost("net8.0-windows", true));
        assertFalse(TargetFramework.isRunnableModernOnHost("net8.0-android", true));
    }

    @Test
    void readsSdkStyleSingleTfm(@TempDir Path dir) throws Exception {
        Path csproj = dir.resolve("App.csproj");
        Files.writeString(csproj, """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <OutputType>Exe</OutputType>
                    <TargetFramework>net48</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);
        assertEquals(java.util.List.of("net48"), TargetFramework.readTfms(csproj));
        assertTrue(TargetFramework.isNetFrameworkOnly(dir));
    }

    @Test
    void readsMultiTargetTfms(@TempDir Path dir) throws Exception {
        Path csproj = dir.resolve("Lib.csproj");
        Files.writeString(csproj, """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFrameworks>net48;net8.0;net10.0</TargetFrameworks>
                  </PropertyGroup>
                </Project>
                """);
        assertEquals(java.util.List.of("net48", "net8.0", "net10.0"), TargetFramework.readTfms(csproj));

        assertFalse(TargetFramework.isNetFrameworkOnly(dir));
        assertTrue(TargetFramework.hasRunnableModernTarget(dir));
        assertEquals(10, TargetFramework.requiredModernMajor(dir).orElseThrow());
        assertEquals("net10.0", TargetFramework.selectRunnableModernTfm(dir).orElseThrow());
    }

    @Test
    void selectsRunnableModernTargetForHost() {
        assertEquals("net8.0", TargetFramework.selectRunnableModernTfm(
                java.util.List.of("netstandard2.0", "net48", "net8.0"), true).orElseThrow());
        assertEquals("net8.0-windows", TargetFramework.selectRunnableModernTfm(
                java.util.List.of("net48", "net8.0-windows"), true).orElseThrow());
        assertTrue(TargetFramework.selectRunnableModernTfm(
                java.util.List.of("net48", "net8.0-windows"), false).isEmpty());
        assertTrue(TargetFramework.selectRunnableModernTfm(
                java.util.List.of("netstandard2.0"), true).isEmpty());
    }

    @Test
    void readsLegacyNonSdkProject(@TempDir Path dir) throws Exception {
        Path csproj = dir.resolve("Legacy.csproj");
        Files.writeString(csproj, """
                <?xml version="1.0" encoding="utf-8"?>
                <Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
                  <PropertyGroup>
                    <TargetFrameworkVersion>v4.7.2</TargetFrameworkVersion>
                  </PropertyGroup>
                </Project>
                """);
        assertEquals(java.util.List.of("v4.7.2"), TargetFramework.readTfms(csproj));
        assertTrue(TargetFramework.targetsNetFramework(dir));
        assertTrue(TargetFramework.isNetFrameworkOnly(dir));
    }

    @Test
    void resolvesDebugAssemblyInsideSelectedTargetFramework(@TempDir Path dir) throws Exception {
        Path csproj = dir.resolve("App.csproj");
        Files.writeString(csproj, """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <AssemblyName>CustomApp</AssemblyName>
                    <TargetFrameworks>net48;net10.0</TargetFrameworks>
                  </PropertyGroup>
                </Project>
                """);
        Path net48 = dir.resolve("bin").resolve("Debug").resolve("net48").resolve("CustomApp.dll");
        Path net10 = dir.resolve("bin").resolve("Debug").resolve("net10.0").resolve("CustomApp.dll");
        Files.createDirectories(net48.getParent());
        Files.createDirectories(net10.getParent());
        Files.writeString(net48, "net48");
        Files.writeString(net10, "net10");

        assertEquals(net10, DotnetBuild.resolveDebugTargetDll(dir, csproj, "Debug", "net10.0"));
    }
}
