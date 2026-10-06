package dtm.ide.build;

import dtm.ide.api.project.diagnostics.IdeProblem;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import java.nio.file.Path;

public record BuildDiagnostic(Path file, int line, int column, DiagnosticSeverity severity,
                              String code, String message, String source) {
    public IdeProblem toProblem() {
        return new IdeProblem(file, line, column, severity, message, source, code);
    }
}
