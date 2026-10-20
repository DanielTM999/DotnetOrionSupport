package dtm.ide.editor;

import dtm.ide.lsp.DotnetWorkspaceEdit;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class WorkspaceEdits {
    public record Prepared(Map<Path, String> before, Map<Path, String> after) {
        public Prepared {
            before = Map.copyOf(before); after = Map.copyOf(after);
            if (!before.keySet().equals(after.keySet())) throw new IllegalArgumentException("Workspace edit file sets differ");
        }
        public void validate(Function<Path, String> read) {
            before.forEach((file, text) -> {
                if (!Objects.equals(text, read.apply(file))) throw new IllegalStateException("File changed while preparing edits: " + file);
            });
        }
        public void commit(Function<Path, String> read, java.util.function.BiConsumer<Path, String> write) {
            validate(read);
            List<Path> applied = new ArrayList<>();
            try {
                for (Path path : after.keySet().stream().sorted().toList()) {
                    applied.add(path);
                    write.accept(path, after.get(path));
                }
            } catch (RuntimeException failure) {
                for (Path path : applied.reversed()) {
                    try { write.accept(path, before.get(path)); }
                    catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
                }
                if (failure.getSuppressed().length > 0)
                    throw new IllegalStateException("Some files could not be restored after a failed edit. Review the affected files.", failure);
                throw failure;
            }
        }
    }
    private record Edit(int start, int end, String text, int index) {}
    private WorkspaceEdits() {}

    public static Prepared prepare(DotnetWorkspaceEdit edit, Function<Path, String> read) {
        Map<Path, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        if (edit.changes() != null) edit.changes().forEach((file, changes) -> {
            Path path = file.toAbsolutePath().normalize();
            String original = read.apply(path);
            if (original == null) throw new IllegalArgumentException("Cannot read " + path);
            before.put(path, original);
            after.put(path, apply(original, changes));
        });
        return new Prepared(before, after);
    }

    public static String apply(String source, List<TextEdit> edits) {
        List<Edit> normalized = new ArrayList<>();
        if (edits == null) return source;
        for (int i = 0; i < edits.size(); i++) {
            var edit = edits.get(i);
            int start = offset(source, edit.range().start()), end = offset(source, edit.range().end());
            if (end < start) throw new IllegalArgumentException("Reversed edit range");
            normalized.add(new Edit(start, end, Objects.requireNonNullElse(edit.newText(), ""), i));
        }
        normalized.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end).thenComparingInt(Edit::index));
        int previousEnd = -1;
        for (var edit : normalized) {
            if (edit.start() < previousEnd) throw new IllegalArgumentException("Overlapping text edits");
            previousEnd = edit.end();
        }
        var result = new StringBuilder(source);
        for (var edit : normalized.reversed()) result.replace(edit.start(), edit.end(), edit.text());
        return result.toString();
    }

    private static int offset(String source, Position position) {
        if (position == null || position.line() < 0 || position.col() < 0) throw new IllegalArgumentException("Invalid edit position");
        int start = 0;
        for (int i = 0; i < position.line(); i++) {
            int newline = source.indexOf('\n', start);
            if (newline < 0) throw new IllegalArgumentException("Edit line exceeds document");
            start = newline + 1;
        }
        int end = source.indexOf('\n', start);
        if (end < 0) end = source.length();
        else if (end > start && source.charAt(end - 1) == '\r') end--;
        if (position.col() > end - start) throw new IllegalArgumentException("Edit column exceeds line");
        return start + position.col();
    }
}
