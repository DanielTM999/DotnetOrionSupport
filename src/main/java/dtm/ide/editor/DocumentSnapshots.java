package dtm.ide.editor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class DocumentSnapshots {
    public record Snapshot(Path file, String text, long version) {}

    private final Map<Path, Snapshot> documents = new HashMap<>();
    private long revision;

    public synchronized Snapshot update(Path path, String text) {
        Path file = path.toAbsolutePath().normalize();
        String content = text == null ? "" : text;
        Snapshot current = documents.get(file);
        if (current != null && Objects.equals(current.text(), content)) return current;
        Snapshot snapshot = new Snapshot(file, content, ++revision);
        documents.put(file, snapshot);
        return snapshot;
    }

    public synchronized boolean isCurrent(Snapshot snapshot) {
        return snapshot != null && snapshot.equals(documents.get(snapshot.file()));
    }

    public synchronized void remove(Path file) {
        if (file != null) documents.remove(file.toAbsolutePath().normalize());
    }

    public synchronized void clear() {
        documents.clear();
    }
}
