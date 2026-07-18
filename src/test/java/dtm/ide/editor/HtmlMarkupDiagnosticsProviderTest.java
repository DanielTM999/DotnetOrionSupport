package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlMarkupDiagnosticsProviderTest {

    private final HtmlMarkupDiagnosticsProvider provider = new HtmlMarkupDiagnosticsProvider();

    @Test
    void flagsUnclosedTag() {
        String text = "<h1>Hello</h1>\n<p>Bem-vindo</p>\n<p>teste";
        List<Diagnostic> diagnostics = provider.diagnose(text);

        assertEquals(1, diagnostics.size());
        Diagnostic diagnostic = diagnostics.get(0);
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity());
        assertTrue(diagnostic.message().contains("<p>"), diagnostic::message);
        assertEquals(2, diagnostic.startLine());
    }

    @Test
    void balancedMarkupHasNoDiagnostics() {
        String text = "<div><p>Hello</p><span>x</span></div>";
        assertTrue(provider.diagnose(text).isEmpty());
    }

    @Test
    void voidAndSelfClosingElementsAreNotFlagged() {
        String text = "<div><br><img src=\"x\"><input /></div>";
        assertTrue(provider.diagnose(text).isEmpty());
    }

    @Test
    void flagsStrayClosingTag() {
        String text = "<div>ok</div></span>";
        List<Diagnostic> diagnostics = provider.diagnose(text);

        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).message().contains("</span>"), () -> diagnostics.get(0).message());
    }

    @Test
    void ignoresTagsInsideRazorAndComments() {
        String text = "@{ var x = 1 < 2; }\n@* <div> *@\n<p>ok</p>";
        assertTrue(provider.diagnose(text).isEmpty());
    }

    @Test
    void ignoresLessThanInText() {
        String text = "<p>1 < 2 and a < b</p>";
        assertTrue(provider.diagnose(text).isEmpty());
    }

    @Test
    void markupInsideRazorControlFlowIsBalanced() {
        String text = "@foreach (var i in items)\n{\n    <li>@i</li>\n}";
        assertFalse(provider.diagnose(text).stream().anyMatch(d -> d.message().contains("<li>")));
    }
}
