package dtm.ide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorConfigSettingsTest {

    @Test
    void resolvesCSharpIndentationAndNearestOverride(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".editorconfig"), """
                root = true

                [*.{cs,csx}]
                indent_style = space
                indent_size = 2
                """);
        Path sourceDir = Files.createDirectories(root.resolve("src"));
        Files.writeString(sourceDir.resolve(".editorconfig"), """
                [*.cs]
                indent_style = tab
                tab_width = 8
                """);
        Path source = Files.createFile(sourceDir.resolve("Program.cs"));

        EditorConfigSettings.FormatOptions options = EditorConfigSettings.resolve(source, 4, true);

        assertEquals(8, options.tabSize());
        assertFalse(options.insertSpaces());
    }

    @Test
    void keepsDefaultsWhenNoMatchingSectionExists(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".editorconfig"), "[*.java]\nindent_size = 2\n");
        Path source = Files.createFile(root.resolve("Program.cs"));

        EditorConfigSettings.FormatOptions options = EditorConfigSettings.resolve(source, 4, true);

        assertEquals(4, options.tabSize());
        assertTrue(options.insertSpaces());
    }
}
