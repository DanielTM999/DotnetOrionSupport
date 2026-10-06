package dtm.ide.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class BuildDiagnosticParserTest {
    @Test
    void parsesCompilerAndNugetAndDeduplicatesSummary(@TempDir Path root) {
        var parser = new BuildDiagnosticParser(root);
        parser.accept("1>folder with spaces/Program.cs(12,7): error CS1002: ; expected [App.csproj]");
        parser.accept("folder with spaces/Program.cs(12,7): error CS1002: ; expected [App.csproj]");
        parser.accept("App.csproj : warning NU1903: Package has a vulnerability");
        parser.accept("MSBUILD : error MSB1009: Project file does not exist.");
        parser.accept("Build FAILED.");
        var results = parser.diagnostics();
        assertEquals(3, results.size());
        assertEquals(root.resolve("folder with spaces/Program.cs"), results.getFirst().file());
        assertEquals(12, results.getFirst().line());
        assertEquals(7, results.getFirst().column());
        assertEquals("; expected", results.getFirst().message());
        assertEquals("NuGet", results.get(1).source());
        assertNull(results.get(2).file());
    }

    @Test
    void capturesSplitUtf8WithoutChangingTerminalOutput(@TempDir Path root) throws Exception {
        byte[] bytes = "Program.cs(1,2): error CS1002: expressão inválida".getBytes(StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();
        var capture = new BuildOutputCapture(output, root);
        for (byte value : bytes) capture.write(value & 255);
        var result = capture.finish(1);
        assertArrayEquals(bytes, output.toByteArray());
        assertEquals("expressão inválida", result.diagnostics().getFirst().message());
    }
}
