package dtm.ide.editor.condition;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticsContext;
import dtm.stools.component.panels.editor.code.prototype.TextBuffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CSharpConditionDiagnosticsProviderTest {

    private final CSharpConditionDiagnosticsProvider provider = new CSharpConditionDiagnosticsProvider();

    private List<Diagnostic> validate(String condition) {
        return provider.getDiagnostics(new DiagnosticsContext(new TextBuffer(condition)));
    }

    @Test
    void acceptsValidExpressions() {
        assertTrue(validate("x > 10 && lista.Count == 0").isEmpty());
        assertTrue(validate("nome == \"teste\"").isEmpty());
        assertTrue(validate("valores.Any(v => v != null)").isEmpty());
        assertTrue(validate("texto == @\"c:\\temp\"").isEmpty());
        assertTrue(validate("c == 'a'").isEmpty());
        assertTrue(validate("(a + b) * 2 >= limite").isEmpty());
    }

    @Test
    void acceptsBlankCondition() {
        assertTrue(validate("").isEmpty());
        assertTrue(validate("   ").isEmpty());
    }

    @Test
    void reportsSemicolon() {
        List<Diagnostic> diagnostics = validate("x = 1;");
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("';'")));
    }

    @Test
    void reportsAssignment() {
        List<Diagnostic> diagnostics = validate("contador = 5");
        assertEquals(1, diagnostics.size());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.get(0).severity());
        assertTrue(diagnostics.get(0).message().contains("=="));
    }

    @Test
    void reportsCompoundAssignment() {
        assertEquals(1, validate("contador += 1").size());
        assertEquals(1, validate("mask <<= 2").size());
        assertEquals(1, validate("valor ??= outro").size());
    }

    @Test
    void allowsComparisonOperators() {
        assertTrue(validate("a >= 1 && b <= 2 || c != 3").isEmpty());
    }

    @Test
    void reportsUnbalancedDelimiters() {
        assertEquals(1, validate("lista.Count(x => x > 1").size());
        assertEquals(1, validate("a + b)").size());
        assertEquals(1, validate("valores[0").size());
    }

    @Test
    void reportsUnterminatedString() {
        assertEquals(1, validate("nome == \"aberto").size());
        assertEquals(1, validate("texto == @\"sem fim").size());
        assertEquals(1, validate("c == 'x").size());
    }

    @Test
    void warnsWhenOnlyComments() {
        List<Diagnostic> diagnostics = validate("// apenas comentário");
        assertEquals(1, diagnostics.size());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(0).severity());
    }

    @Test
    void reportsUnterminatedBlockComment() {
        assertEquals(1, validate("x > 1 /* aberto").size());
    }
}
