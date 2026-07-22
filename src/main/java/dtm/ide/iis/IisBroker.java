package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class IisBroker {

    public enum Tool {
        APP_CMD,
        IIS_RESET,
        ICACLS
    }

    private static final long LAUNCH_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 120;
    private static final long BROKER_LIFETIME_HOURS = 8;
    private static final long HEARTBEAT_STALE_MS = 15_000;
    static final String HEARTBEAT_FILE = "alive.txt";

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private static volatile Path workDirectory;
    private static volatile long brokerPid;
    private static volatile long lastHeartbeat;

    private IisBroker() {
    }

    public static synchronized boolean available() {
        return IisEnvironment.current().manageable();
    }

    public static boolean isRunning() {
        Path directory = workDirectory;
        if (directory == null) {
            return false;
        }
        Long modified = heartbeatAt(directory);
        if (modified != null) {
            lastHeartbeat = Math.max(lastHeartbeat, modified);
        }
        return lastHeartbeat > 0 && System.currentTimeMillis() - lastHeartbeat < HEARTBEAT_STALE_MS;
    }

    static boolean heartbeatFresh(Path directory) {
        Long modified = heartbeatAt(directory);
        return modified != null && System.currentTimeMillis() - modified < HEARTBEAT_STALE_MS;
    }

    private static Long heartbeatAt(Path directory) {
        if (directory == null) {
            return null;
        }
        Path beat = directory.resolve(HEARTBEAT_FILE);
        try {
            return Files.isRegularFile(beat) ? Files.getLastModifiedTime(beat).toMillis() : null;
        } catch (Exception e) {
            log.debug("Heartbeat do assistente indisponível no momento: {}", e.getMessage());
            return null;
        }
    }

    public static IisProcess.Result run(Tool tool, List<String> arguments, long timeoutSeconds) {
        try {
            if (!ensureRunning()) {
                return new IisProcess.Result(-1,
                        "Não foi possível iniciar o assistente elevado do IIS. A operação exige privilégios de administrador.");
            }
            return submit(tool, arguments == null ? List.of() : arguments, timeoutSeconds);
        } catch (Exception e) {
            log.debug("Falha ao usar o assistente elevado: {}", e.getMessage());
            return new IisProcess.Result(-1, "Falha ao executar operação elevada: " + e.getMessage());
        }
    }

    private static synchronized boolean ensureRunning() throws Exception {
        if (isRunning()) {
            return true;
        }
        Path directory = Files.createTempDirectory("orion-iis-broker-" + UUID.randomUUID());
        restrictAccess(directory);
        Path script = directory.resolve("broker.ps1");
        Files.writeString(script, brokerScript(directory), StandardCharsets.UTF_8);

        lastHeartbeat = 0;
        IisProcess.Result launch = IisProcess.capture(
                List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-Command", launchScript(script)),
                LAUNCH_TIMEOUT_MS / 1000);
        if (!launch.ok()) {
            log.warn("Falha ao iniciar o assistente elevado do IIS: {}", launch.output());
            return false;
        }

        Path heartbeat = directory.resolve(HEARTBEAT_FILE);
        long deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(heartbeat)) {
                workDirectory = directory;
                brokerPid = parsePid(readText(directory.resolve("pid.txt")));
                lastHeartbeat = System.currentTimeMillis();
                log.info("Assistente elevado do IIS iniciado (PID {}).", brokerPid);
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        log.warn("O assistente elevado do IIS não confirmou a inicialização.");
        return false;
    }

    private static IisProcess.Result submit(Tool tool, List<String> arguments, long timeoutSeconds)
            throws Exception {
        return submit(workDirectory, tool, arguments, timeoutSeconds, IisBroker::isRunning);
    }

    static IisProcess.Result submit(Path directory, Tool tool, List<String> arguments, long timeoutSeconds,
                                    java.util.function.BooleanSupplier alive) throws Exception {
        if (directory == null) {
            return new IisProcess.Result(-1, "Assistente elevado indisponível.");
        }
        String sequence = String.valueOf(SEQUENCE.incrementAndGet());
        Path request = directory.resolve("req-" + sequence + ".txt");
        Path staging = directory.resolve("stg-" + sequence + ".txt");
        Path output = directory.resolve("out-" + sequence + ".txt");
        Path code = directory.resolve("rc-" + sequence + ".txt");

        List<String> lines = new ArrayList<>();
        lines.add(String.valueOf(tool.ordinal()));
        lines.addAll(arguments);
        Files.write(staging, lines, StandardCharsets.UTF_8);
        Files.move(staging, request);

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(code)) {
                int exitCode = parseExitCode(readText(code));
                String text = readText(output);
                deleteQuietly(output);
                deleteQuietly(code);
                return new IisProcess.Result(exitCode, text);
            }
            if (!alive.getAsBoolean()) {
                return new IisProcess.Result(-1, "O assistente elevado do IIS foi encerrado.");
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return new IisProcess.Result(-1, "Tempo esgotado aguardando a operação elevada do IIS.");
    }

    public static synchronized void shutdown() {
        Path directory = workDirectory;
        if (directory == null) {
            return;
        }
        try {
            Files.writeString(directory.resolve("stop"), "1", StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Falha ao sinalizar parada do assistente: {}", e.getMessage());
        }
        long pid = brokerPid;
        if (pid > 0) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (heartbeatFresh(directory)) {
                IisProcess.capture(List.of("taskkill", "/PID", String.valueOf(pid), "/T", "/F"), 20);
            }
        }
        brokerPid = 0;
        lastHeartbeat = 0;
        workDirectory = null;
    }

    private static String launchScript(Path script) {
        return "$ErrorActionPreference='Stop'; try { Start-Process -FilePath 'powershell.exe'"
                + " -ArgumentList '-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',"
                + quote(script.toString())
                + " -Verb RunAs -WindowStyle Hidden | Out-Null; exit 0 }"
                + " catch { Write-Output $_.Exception.Message; exit 1 }";
    }

    static String brokerScript(Path directory) {
        IisEnvironment.Info info = IisEnvironment.current();
        String appCmd = info.appCmd() == null ? "" : info.appCmd().toAbsolutePath().toString();
        String system32 = systemDirectory();
        return String.join("\n",
                "$ErrorActionPreference = 'Continue'",
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
                "$dir = " + quote(directory.toString()),
                "$tools = @(" + quote(appCmd) + ","
                        + quote(system32 + "\\iisreset.exe") + ","
                        + quote(system32 + "\\icacls.exe") + ")",
                "Set-Content -LiteralPath (Join-Path $dir 'pid.txt') -Value $PID -Encoding UTF8",
                "$beatFile = Join-Path $dir " + quote(HEARTBEAT_FILE),
                "$lastBeat = [DateTime]::MinValue",
                "$deadline = (Get-Date).AddHours(" + BROKER_LIFETIME_HOURS + ")",
                "while ((Get-Date) -lt $deadline) {",
                "  if (((Get-Date) - $lastBeat).TotalSeconds -ge 2) {",
                "    try { Set-Content -LiteralPath $beatFile -Value ([DateTime]::UtcNow.Ticks) -Encoding UTF8 }"
                        + " catch { }",
                "    $lastBeat = Get-Date",
                "  }",
                "  if (Test-Path -LiteralPath (Join-Path $dir 'stop')) { break }",
                "  $request = Get-ChildItem -LiteralPath $dir -Filter 'req-*.txt' -ErrorAction SilentlyContinue |"
                        + " Sort-Object CreationTime | Select-Object -First 1",
                "  if ($null -eq $request) { Start-Sleep -Milliseconds 120; continue }",
                "  $sequence = $request.BaseName.Substring(4)",
                "  $lines = @(Get-Content -LiteralPath $request.FullName -Encoding UTF8)",
                "  Remove-Item -LiteralPath $request.FullName -Force -ErrorAction SilentlyContinue",
                "  $index = 0",
                "  [int]::TryParse($lines[0], [ref]$index) | Out-Null",
                "  $arguments = @()",
                "  if ($lines.Count -gt 1) { $arguments = $lines[1..($lines.Count - 1)] }",
                "  $outFile = Join-Path $dir (\"out-$sequence.txt\")",
                "  $exit = 0",
                "  try {",
                "    $exe = $tools[$index]",
                "    if ([string]::IsNullOrWhiteSpace($exe)) { throw 'Ferramenta indisponivel.' }",
                "    $result = & $exe @arguments 2>&1",
                "    $exit = $LASTEXITCODE",
                "    if ($null -eq $exit) { $exit = 0 }",
                "    Set-Content -LiteralPath $outFile -Value ($result | Out-String) -Encoding UTF8",
                "  } catch {",
                "    Set-Content -LiteralPath $outFile -Value $_.Exception.Message -Encoding UTF8",
                "    $exit = 1",
                "  }",
                "  Set-Content -LiteralPath (Join-Path $dir (\"rc-$sequence.txt\")) -Value $exit -Encoding UTF8",
                "}",
                "Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue",
                "");
    }

    private static String systemDirectory() {
        String windir = System.getenv("windir");
        if (windir == null || windir.isBlank()) {
            windir = System.getenv("SystemRoot");
        }
        return (windir == null || windir.isBlank() ? "C:\\Windows" : windir) + "\\System32";
    }

    private static void restrictAccess(Path directory) {
        String user = System.getenv("USERNAME");
        if (user == null || user.isBlank()) {
            return;
        }
        String path = directory.toAbsolutePath().toString();
        IisProcess.Result granted = IisProcess.capture(
                List.of("icacls", path, "/grant:r", user + ":(OI)(CI)F", "/grant:r", "*S-1-5-32-544:(OI)(CI)F"),
                30);
        if (!granted.ok()) {
            log.debug("Não foi possível conceder permissões ao diretório do assistente: {}", granted.output());
            return;
        }
        IisProcess.Result isolated = IisProcess.capture(
                List.of("icacls", path, "/inheritance:r"), 30);
        if (!isolated.ok()) {
            log.debug("Não foi possível isolar o diretório do assistente: {}", isolated.output());
            return;
        }
        if (!canWrite(directory)) {
            log.warn("Diretório do assistente ficou inacessível após restringir a ACL; restaurando permissões.");
            IisProcess.capture(List.of("icacls", path, "/reset"), 30);
        }
    }

    private static boolean canWrite(Path directory) {
        Path probe = directory.resolve("probe.tmp");
        try {
            Files.writeString(probe, "1", StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (Exception e) {
                log.debug("Falha ao remover arquivo de teste do assistente: {}", e.getMessage());
            }
        }
    }

    private static String readText(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            return IisProcess.decode(Files.readAllBytes(file)).strip();
        } catch (Exception e) {
            return "";
        }
    }

    private static long parsePid(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseExitCode(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.debug("Falha ao remover temporário do assistente: {}", e.getMessage());
        }
    }

    private static String quote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }
}
