package dtm.ide.editor;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DocumentSnapshotsTest {
    @Test
    void oldVersionsStayInvalidAfterUndoAndReopen() {
        var store = new DocumentSnapshots();
        Path file = Path.of("a.cs");
        var first = store.update(file, "a");
        assertSame(first, store.update(file, "a"));
        store.update(file, "b");
        var undo = store.update(file, "a");
        assertFalse(store.isCurrent(first));
        assertTrue(store.isCurrent(undo));
        store.clear();
        var reopened = store.update(file, "a");
        assertFalse(store.isCurrent(undo));
        assertTrue(reopened.version() > undo.version());
    }
}
