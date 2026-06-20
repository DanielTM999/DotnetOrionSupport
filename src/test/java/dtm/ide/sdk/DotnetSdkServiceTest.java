package dtm.ide.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DotnetSdkServiceTest {

    @Test
    void mapsModernTargetFrameworkToSdkVersion(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>net10.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);

        DotnetSdkService service = new DotnetSdkService(null, null);

        assertEquals(DotnetSdkService.DOTNET_10_SDK_VERSION, service.resolveSdkVersion(dir));
    }

    @Test
    void globalJsonVersionOverridesTargetFramework(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("global.json"), """
                {
                  "sdk": {
                    "version": "10.0.100"
                  }
                }
                """);
        Files.writeString(dir.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>net10.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);

        DotnetSdkService service = new DotnetSdkService(null, null);

        assertEquals("10.0.100", service.resolveSdkVersion(dir));
    }
}
