package dtm.ide.build;

import java.util.List;

public record BuildResult(int exitCode, List<BuildDiagnostic> diagnostics) {
    public BuildResult { diagnostics = List.copyOf(diagnostics); }
}
