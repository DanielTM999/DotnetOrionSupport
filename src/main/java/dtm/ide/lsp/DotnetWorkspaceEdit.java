package dtm.ide.lsp;

import dtm.stools.component.panels.editor.code.api.TextEdit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record DotnetWorkspaceEdit(Map<Path, List<TextEdit>> changes) {

    public boolean isEmpty() {
        return changes == null || changes.isEmpty();
    }

    public List<TextEdit> editsFor(Path file) {
        if (changes == null || file == null) {
            return List.of();
        }
        return changes.getOrDefault(file.toAbsolutePath().normalize(), List.of());
    }

    public int editCount() {
        if (changes == null) {
            return 0;
        }
        int total = 0;
        for (List<TextEdit> edits : changes.values()) {
            total += edits == null ? 0 : edits.size();
        }
        return total;
    }
}
