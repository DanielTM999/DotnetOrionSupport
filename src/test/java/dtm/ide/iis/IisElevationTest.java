package dtm.ide.iis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisElevationTest {

    @Test
    void quotesArgumentsThatNeedIt() {
        assertEquals("simples", IisElevation.quoteForCmd("simples"));
        assertEquals("\"/name:My Pool\"", IisElevation.quoteForCmd("/name:My Pool"));
        assertEquals("\"\"", IisElevation.quoteForCmd(""));
        assertEquals("\"a&b\"", IisElevation.quoteForCmd("a&b"));
        assertEquals("100%%", IisElevation.quoteForCmd("100%"));
    }

    @Test
    void buildsRedirectingBatchScript(@TempDir Path directory) {
        Path output = directory.resolve("out.txt");
        String script = IisElevation.batchScript("C:\\Windows\\system32\\inetsrv\\appcmd.exe",
                List.of("add", "apppool", "/name:My Pool"), output);

        assertTrue(script.contains("chcp 65001"));
        assertTrue(script.contains("\"C:\\Windows\\system32\\inetsrv\\appcmd.exe\" add apppool \"/name:My Pool\""),
                "script deve invocar o executável entre aspas com os argumentos preservados: " + script);
        assertTrue(script.contains("> \"" + output + "\" 2>&1"));
        assertTrue(script.contains("exit /b %ERRORLEVEL%"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void generatedScriptRunsAndCapturesOutputWithAccents(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("out.txt");
        Path script = directory.resolve("run.cmd");
        Files.writeString(script,
                IisElevation.batchScript("cmd.exe", List.of("/c", "echo configuração número possível"), output),
                StandardCharsets.UTF_8);

        IisProcess.Result result = IisProcess.capture(List.of("cmd.exe", "/c", script.toString()), 30);

        assertEquals(0, result.exitCode());
        assertTrue(Files.isRegularFile(output));
        String captured = IisProcess.decode(Files.readAllBytes(output)).strip();
        assertEquals("configuração número possível", captured);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void generatedScriptPropagatesExitCode(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("out.txt");
        Path script = directory.resolve("run.cmd");
        Files.writeString(script,
                IisElevation.batchScript("cmd.exe", List.of("/c", "exit 7"), output),
                StandardCharsets.UTF_8);

        IisProcess.Result result = IisProcess.capture(List.of("cmd.exe", "/c", script.toString()), 30);

        assertEquals(7, result.exitCode());
    }
}
