package dtm.ide.run;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetcoredbgLauncherTest {

    private static final Path NETCOREDBG = Path.of("C:", "tools", "netcoredbg", "netcoredbg.exe");
    private static final Path DOTNET_ROOT = Path.of("C:", "Program Files", "dotnet");
    private static final Path CWD = Path.of("C:", "src", "Api do Cliente");
    private static final Path PID_FILE = Path.of("C:", "temp", "pid.txt");

    @Test
    void scriptStartsNetcoredbgInServerMode() {
        String script = NetcoredbgLauncher.serverScript(NETCOREDBG, DOTNET_ROOT, CWD, 51234, PID_FILE);

        assertTrue(script.contains("'--interpreter=vscode'"), script);
        assertTrue(script.contains("'--server=51234'"), script);
        assertTrue(script.contains(NETCOREDBG.toString()), script);
    }

    @Test
    void scriptEnablesDebugPrivilegeBeforeLaunching() {
        String script = NetcoredbgLauncher.serverScript(NETCOREDBG, DOTNET_ROOT, CWD, 4711, PID_FILE);

        int privilege = script.indexOf("[OrionDebugPrivilege]::Enable('SeDebugPrivilege')");
        int start = script.indexOf("Start-Process -FilePath");
        assertTrue(privilege > 0, script);
        assertTrue(start > privilege, "o privilégio precisa ser habilitado antes do Start-Process");
    }

    @Test
    void hereStringTerminatorStaysAtColumnZero() {
        String script = NetcoredbgLauncher.serverScript(NETCOREDBG, DOTNET_ROOT, CWD, 4711, PID_FILE);

        assertTrue(script.contains("\r\nAdd-Type -TypeDefinition @'\r\n")
                        || script.startsWith("$ErrorActionPreference = 'Stop'\r\nAdd-Type -TypeDefinition @'\r\n"),
                script);
        assertTrue(script.contains("\r\n'@\r\n"), "o terminador do here-string precisa ficar na coluna 0");
    }

    @Test
    void startProcessDoesNotMixWindowStyleWithNoNewWindow() {
        String script = NetcoredbgLauncher.serverScript(NETCOREDBG, DOTNET_ROOT, CWD, 4711, PID_FILE);

        String startLine = script.lines()
                .filter(line -> line.contains("Start-Process -FilePath"))
                .findFirst()
                .orElseThrow();
        assertTrue(startLine.contains("-NoNewWindow"), startLine);
        assertFalse(startLine.contains("-WindowStyle"), "-WindowStyle e -NoNewWindow são mutuamente exclusivos");
    }

    @Test
    void pathsWithSpacesAndQuotesAreQuoted() {
        Path tricky = Path.of("C:", "src", "O'Brien App");
        String script = NetcoredbgLauncher.serverScript(NETCOREDBG, DOTNET_ROOT, tricky, 4711, PID_FILE);

        assertTrue(script.contains("Set-Location -LiteralPath 'C:\\src\\O''Brien App'"), script);
    }
}
