package dtm.ide.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SafeDeleteSupportTest {
    @TempDir Path dir;
    @Test void checksUnsavedUsesAndIgnoresBuildOutputs() throws Exception {
        Path declaration = dir.resolve("Unused.cs");
        String source = "class Unused {}";
        Files.writeString(declaration, source);
        Path generated = Files.createDirectories(dir.resolve("obj")).resolve("Generated.cs");
        Files.writeString(generated, "Unused value;");
        var support = new SafeDeleteSupport();
        var result = support.check(dir, declaration, 0, source.length(), "Unused", Map.of());
        assertTrue(result.complete()); assertFalse(result.possibleUses());
        Path consumer = dir.resolve("Use.cs"); Files.writeString(consumer, "");
        result = support.check(dir, declaration, 0, source.length(), "Unused", Map.of(consumer, "Unused instance;"));
        assertTrue(result.possibleUses());
    }
}
