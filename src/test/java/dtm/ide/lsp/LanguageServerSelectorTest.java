package dtm.ide.lsp;

import dtm.ide.settings.DotnetPluginSettings;
import dtm.ide.settings.LanguageServerMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageServerSelectorTest {

    private static DotnetPluginSettings settings(LanguageServerMode mode) {
        DotnetPluginSettings settings = new DotnetPluginSettings(null);
        settings.setLanguageServerMode(mode);
        return settings;
    }

    @Test
    void autoDefaultsToOmnisharpForModernSdkProject(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFramework>net8.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);
        assertEquals(LspServerKind.OMNISHARP, LanguageServerSelector.select(dir, settings(LanguageServerMode.AUTO)));
    }

    @Test
    void autoPicksOmnisharpForNetFrameworkProject(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFramework>net48</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);
        assertEquals(LspServerKind.OMNISHARP, LanguageServerSelector.select(dir, settings(LanguageServerMode.AUTO)));
    }

    @Test
    void manualOverrideForcesServer(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFramework>net8.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);
        assertEquals(LspServerKind.OMNISHARP, LanguageServerSelector.select(dir, settings(LanguageServerMode.OMNISHARP)));
        assertEquals(LspServerKind.ROSLYN, LanguageServerSelector.select(dir, settings(LanguageServerMode.ROSLYN)));
    }
}
