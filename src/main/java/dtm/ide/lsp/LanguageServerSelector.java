package dtm.ide.lsp;

import dtm.ide.run.TargetFramework;
import dtm.ide.settings.DotnetPluginSettings;
import dtm.ide.settings.LanguageServerMode;

import java.nio.file.Path;

public final class LanguageServerSelector {

    private LanguageServerSelector() {
    }

    public static LspServerKind select(Path projectRoot, DotnetPluginSettings settings) {
        LanguageServerMode mode = settings == null ? LanguageServerMode.AUTO : settings.getLanguageServerMode();
        return switch (mode) {
            case OMNISHARP -> LspServerKind.OMNISHARP;
            case ROSLYN -> LspServerKind.ROSLYN;
            case AUTO -> autoSelect(projectRoot);
        };
    }

    private static LspServerKind autoSelect(Path projectRoot) {
        if (TargetFramework.requiresLegacyLanguageServer(projectRoot)) {
            return LspServerKind.OMNISHARP;
        }
        return PREFER_ROSLYN_FOR_MODERN ? LspServerKind.ROSLYN : LspServerKind.OMNISHARP;
    }

    private static final boolean PREFER_ROSLYN_FOR_MODERN = false;
}
