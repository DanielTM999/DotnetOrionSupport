package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
public final class IisElevation {

    private static final String CANCELLED_MARKER = "operation was canceled by the user";
    private static final String CANCELLED_MARKER_PT = "cancelada pelo usu";

    private IisElevation() {
    }

    public static IisProcess.Result run(Path executable, List<String> arguments, long timeoutSeconds) {
        if (executable == null) {
            return new IisProcess.Result(-1, "Executável indisponível para elevação.");
        }
        return run(executable.toAbsolutePath().toString(), arguments, timeoutSeconds);
    }

    public static IisProcess.Result run(String executable, List<String> arguments, long timeoutSeconds) {
        if (executable == null || executable.isBlank()) {
            return new IisProcess.Result(-1, "Executável indisponível para elevação.");
        }
        if (IisEnvironment.isElevated()) {
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.addAll(arguments == null ? List.of() : arguments);
            return IisProcess.capture(command, timeoutSeconds);
        }
        return runElevated(executable, arguments, timeoutSeconds);
    }

    private static IisProcess.Result runElevated(String executable, List<String> arguments, long timeoutSeconds) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("orion-iis-" + UUID.randomUUID());
            Path output = directory.resolve("out.txt");
            Path script = directory.resolve("run.cmd");
            Files.writeString(script, batchScript(executable, arguments, output), StandardCharsets.UTF_8);

            IisProcess.Result launch = IisProcess.capture(
                    List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                            "-Command", elevationScript(script)),
                    timeoutSeconds);

            String captured = readIfPresent(output);
            if (launch.ok()) {
                return new IisProcess.Result(0, captured);
            }
            if (isCancelled(launch.output())) {
                return new IisProcess.Result(launch.exitCode(),
                        "Elevação cancelada: a operação exige privilégios de administrador.");
            }
            String detail = captured.isBlank() ? launch.output() : captured;
            return new IisProcess.Result(launch.exitCode(), detail);
        } catch (Exception e) {
            return new IisProcess.Result(-1, "Falha ao elevar privilégios: " + e.getMessage());
        } finally {
            deleteQuietly(directory);
        }
    }

    public static List<IisProcess.Result> runAll(String executable, List<List<String>> argumentSets,
                                                 long timeoutSeconds) {
        if (argumentSets == null || argumentSets.isEmpty()) {
            return List.of();
        }
        if (IisEnvironment.isElevated()) {
            List<IisProcess.Result> results = new ArrayList<>();
            for (List<String> arguments : argumentSets) {
                results.add(run(executable, arguments, timeoutSeconds));
            }
            return results;
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory("orion-iis-" + UUID.randomUUID());
            List<Path> outputs = new ArrayList<>();
            List<Path> codes = new ArrayList<>();
            StringBuilder script = new StringBuilder("@echo off\r\nchcp 65001 > nul\r\n");
            for (int i = 0; i < argumentSets.size(); i++) {
                Path output = directory.resolve("out" + i + ".txt");
                Path code = directory.resolve("rc" + i + ".txt");
                outputs.add(output);
                codes.add(code);
                script.append(alwaysQuote(executable));
                for (String argument : argumentSets.get(i)) {
                    script.append(' ').append(quoteForCmd(argument));
                }
                script.append(" > ").append(alwaysQuote(output.toString())).append(" 2>&1\r\n");
                script.append("echo %ERRORLEVEL%> ").append(alwaysQuote(code.toString())).append("\r\n");
            }
            script.append("exit /b 0\r\n");

            Path scriptFile = directory.resolve("run.cmd");
            Files.writeString(scriptFile, script.toString(), StandardCharsets.UTF_8);
            IisProcess.Result launch = IisProcess.capture(
                    List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                            "-Command", elevationScript(scriptFile)),
                    timeoutSeconds);

            List<IisProcess.Result> results = new ArrayList<>();
            for (int i = 0; i < argumentSets.size(); i++) {
                String output = readIfPresent(outputs.get(i));
                if (!launch.ok() && output.isBlank()) {
                    results.add(new IisProcess.Result(launch.exitCode(), isCancelled(launch.output())
                            ? "Elevação cancelada: a operação exige privilégios de administrador."
                            : launch.output()));
                    continue;
                }
                results.add(new IisProcess.Result(exitCodeOf(codes.get(i)), output));
            }
            return results;
        } catch (Exception e) {
            List<IisProcess.Result> results = new ArrayList<>();
            for (int i = 0; i < argumentSets.size(); i++) {
                results.add(new IisProcess.Result(-1, "Falha ao elevar privilégios: " + e.getMessage()));
            }
            return results;
        } finally {
            deleteQuietly(directory);
        }
    }

    private static int exitCodeOf(Path file) {
        String value = readIfPresent(file);
        if (value.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isCancelled(String output) {
        if (output == null) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains(CANCELLED_MARKER) || lower.contains(CANCELLED_MARKER_PT);
    }

    static String batchScript(String executable, List<String> arguments, Path output) {
        StringBuilder script = new StringBuilder();
        script.append("@echo off\r\n");
        script.append("chcp 65001 > nul\r\n");
        script.append(alwaysQuote(executable));
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            script.append(' ').append(quoteForCmd(argument));
        }
        script.append(" > ").append(alwaysQuote(output.toString())).append(" 2>&1\r\n");
        script.append("exit /b %ERRORLEVEL%\r\n");
        return script.toString();
    }

    static String alwaysQuote(String value) {
        String escaped = value == null ? "" : value.replace("%", "%%");
        return '"' + escaped.replace("\"", "\"\"") + '"';
    }

    static String quoteForCmd(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        String escaped = value.replace("%", "%%");
        if (escaped.indexOf(' ') < 0 && escaped.indexOf('\t') < 0 && escaped.indexOf('&') < 0
                && escaped.indexOf('|') < 0 && escaped.indexOf('<') < 0 && escaped.indexOf('>') < 0
                && escaped.indexOf('^') < 0 && escaped.indexOf('"') < 0) {
            return escaped;
        }
        return '"' + escaped.replace("\"", "\"\"") + '"';
    }

    private static String elevationScript(Path script) {
        return "$ErrorActionPreference='Stop'; "
                + "try { $p = Start-Process -FilePath 'cmd.exe'"
                + " -ArgumentList '/c', " + quoteForPowerShell(script.toString())
                + " -Verb RunAs -PassThru -Wait -WindowStyle Hidden; exit $p.ExitCode }"
                + " catch { Write-Output $_.Exception.Message; exit 1 }";
    }

    private static String quoteForPowerShell(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }

    private static String readIfPresent(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            return IisProcess.decode(Files.readAllBytes(file)).strip();
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                Files.deleteIfExists(child);
            }
        } catch (Exception e) {
            log.debug("Falha ao limpar temporários de elevação: {}", e.getMessage());
        }
        try {
            Files.deleteIfExists(directory);
        } catch (Exception e) {
            log.debug("Falha ao remover diretório de elevação: {}", e.getMessage());
        }
    }
}
