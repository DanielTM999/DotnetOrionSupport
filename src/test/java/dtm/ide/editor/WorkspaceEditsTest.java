package dtm.ide.editor;

import dtm.ide.lsp.DotnetWorkspaceEdit;
import dtm.stools.component.panels.editor.code.api.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceEditsTest {
    private static TextEdit edit(int from, int to, String text) {
        return new TextEdit(new Range(new Position(0, from), new Position(0, to)), text);
    }
    @Test void rejectsOverlapsAndOutOfBoundsInsteadOfClamping() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceEdits.apply("abc", List.of(edit(0, 2, "x"), edit(1, 3, "y"))));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceEdits.apply("abc", List.of(edit(0, 4, "x"))));
        assertEquals("XYabc", WorkspaceEdits.apply("abc", List.of(edit(0, 0, "X"), edit(0, 0, "Y"))));
    }
    @Test void validatesEveryFileBeforeApplyingWorkspaceChanges() {
        Path a = Path.of("a.cs").toAbsolutePath(), b = Path.of("b.cs").toAbsolutePath();
        var prepared = WorkspaceEdits.prepare(new DotnetWorkspaceEdit(Map.of(a, List.of(edit(0, 1, "A")),
                b, List.of(edit(0, 1, "B")))), file -> "abc");
        assertEquals("Abc", prepared.after().get(a));
        assertThrows(IllegalStateException.class, () -> prepared.validate(file -> file.equals(b) ? "changed" : "abc"));
    }

    @Test void restoresEarlierChangesWhenAWriteFails() {
        Path a = Path.of("a.cs").toAbsolutePath(), b = Path.of("b.cs").toAbsolutePath();
        Map<Path, String> files = new java.util.HashMap<>(Map.of(a, "abc", b, "abc"));
        var prepared = WorkspaceEdits.prepare(new DotnetWorkspaceEdit(Map.of(a, List.of(edit(0, 1, "A")),
                b, List.of(edit(0, 1, "B")))), files::get);
        var firstFailure = new java.util.concurrent.atomic.AtomicBoolean(true);
        assertThrows(IllegalStateException.class, () -> prepared.commit(files::get, (path, text) -> {
            files.put(path, text);
            if (path.equals(b) && firstFailure.getAndSet(false)) throw new IllegalStateException("write failed");
        }));
        assertEquals(Map.of(a, "abc", b, "abc"), files);
    }
}
