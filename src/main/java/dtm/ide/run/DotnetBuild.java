package dtm.ide.run;

import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
public final class DotnetBuild {

    static final String BUILD_PANEL_NAME = "Build";

    private static final Pattern ASSEMBLY_NAME =
            Pattern.compile("<AssemblyName>\\s*([^<]+)</AssemblyName>", Pattern.CASE_INSENSITIVE);

    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    public RunProcessHandle build(Path project, Path dotnet, String configuration) {
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().toString());
        command.add("build");
        command.add("-c");
        command.add(configuration == null || configuration.isBlank() ? "Debug" : configuration);
        command.add("--nologo");
        return launchProcess(DotnetRunSupport.TYPE_BUILD, command, project, false);
    }

    public RunProcessHandle run(Path project, Path dotnet, String configuration) {
        Path projectFile = TargetFramework.findPrimaryProjectFile(project);
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().toString());
        command.add("run");
        if (projectFile != null) {
            command.add("--project");
            command.add(projectFile.toAbsolutePath().toString());
        }
        command.add("-c");
        command.add(configuration == null || configuration.isBlank() ? "Debug" : configuration);
        return launchProcess(DotnetRunSupport.TYPE_RUN, command, project, true);
    }

    public RunProcessHandle test(Path project, Path dotnet, String configuration) {
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().toString());
        command.add("test");
        command.add("-c");
        command.add(configuration == null || configuration.isBlank() ? "Debug" : configuration);
        command.add("--nologo");
        return launchProcess(DotnetRunSupport.TYPE_TEST, command, project, false);
    }

    public List<String> listTests(Path project, Path dotnet, String configuration) {
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().toString());
        command.add("test");
        command.add("-c");
        command.add(configuration == null || configuration.isBlank() ? "Debug" : configuration);
        command.add("--nologo");
        command.add("--list-tests");
        return parseTestList(captureOutput(command, project, 240));
    }

    public Process launchTest(Path project, Path dotnet, String configuration, String filter) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().toString());
        command.add("test");
        command.add("-c");
        command.add(configuration == null || configuration.isBlank() ? "Debug" : configuration);
        command.add("--nologo");
        if (filter != null && !filter.isBlank()) {
            command.add("--filter");
            command.add(filter);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.redirectErrorStream(true);
        applyDotnetEnv(builder, firstCommandPath(command));
        Process process = builder.start();
        registerProcess(DotnetRunSupport.TYPE_TEST, process);
        return process;
    }

    public void stopTests() {
        stop(DotnetRunSupport.TYPE_TEST);
    }

    private String captureOutput(List<String> command, Path workingDir, long timeoutSeconds) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDir != null) {
                builder.directory(workingDir.toFile());
            }
            builder.redirectErrorStream(true);
            applyDotnetEnv(builder, firstCommandPath(command));
            process = builder.start();
            registerProcess(DotnetRunSupport.TYPE_TEST, process);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Process started = process;
            Thread reader = new Thread(() -> {
                try {
                    pump(started.getInputStream(), out);
                } catch (IOException ignored) {
                }
            }, "dotnet-test-list");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                destroyQuietly(process);
            }
            reader.join(2000);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[erro] " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null) {
                activeProcesses.remove(DotnetRunSupport.TYPE_TEST, process);
            }
        }
    }

    static List<String> parseTestList(String output) {
        List<String> tests = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return tests;
        }
        for (String raw : output.split("\\R")) {
            if (raw.isEmpty() || !Character.isWhitespace(raw.charAt(0))) {
                continue;
            }
            String line = raw.strip();
            if (line.isEmpty() || line.indexOf('.') < 0 || !Character.isJavaIdentifierStart(line.charAt(0))) {
                continue;
            }
            if (!tests.contains(line)) {
                tests.add(line);
            }
        }
        return tests;
    }

    private RunProcessHandle launchProcess(String type, List<String> command, Path project, boolean interactive) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(project.toFile());
            builder.redirectErrorStream(true);

            applyDotnetEnv(builder, firstCommandPath(command));
            Process process = builder.start();
            registerProcess(type, process);
            return RunProcessHandle.builder()
                    .output(process.getInputStream())
                    .input(process.getOutputStream())
                    .readonly(!interactive)
                    .alive(process::isAlive)
                    .terminate(() -> destroyQuietly(process))
                    .process(process)
                    .build();
        } catch (IOException e) {
            return errorHandle("Falha ao iniciar dotnet: " + e.getMessage());
        }
    }

    public RunProcessHandle buildThenRun(Path project, Path dotnet, String configuration,
                                         Path projectFile, String targetFramework, String launchProfile,
                                         Function<String, OutputPanelHandle> panelProvider,
                                         Runnable showRunOutput, Map<String, String> launchEnv) {
        String config = configuration == null || configuration.isBlank() ? "Debug" : configuration;
        PipedInputStream consoleIn;
        PipedOutputStream consoleOut;
        try {
            consoleIn = new PipedInputStream(1 << 16);
            consoleOut = new PipedOutputStream(consoleIn);
        } catch (IOException e) {
            return errorHandle("Falha ao preparar console: " + e.getMessage());
        }

        DeferredOutputStream stdinBridge = new DeferredOutputStream();
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicReference<Process> runProcess = new AtomicReference<>();

        OutputPanelHandle buildPanel = requestBuildPanel(panelProvider);
        OutputStream buildStream = buildPanel != null ? buildPanel.getOutputStream() : consoleOut;
        boolean separatePanels = buildPanel != null;

        List<String> buildCmd = new ArrayList<>(List.of(
                dotnet.toString(), "build", "-c", config, "--nologo"));
        if (projectFile != null) {
            buildCmd.add(projectFile.toString());
        }
        addFrameworkOption(buildCmd, targetFramework);
        List<String> runCmd = new ArrayList<>(List.of(
                dotnet.toString(), "run", "--no-build", "-c", config));
        if (projectFile != null) {
            runCmd.add("--project");
            runCmd.add(projectFile.toString());
        }
        addFrameworkOption(runCmd, targetFramework);
        addLaunchProfileOption(runCmd, launchProfile);

        Thread worker = new Thread(() -> {
            try (OutputStream out = consoleOut) {
                OutputStream buildOut = separatePanels ? buildStream : out;
                if (separatePanels) {
                    buildPanel.show();
                }
                writeLine(buildOut, "> " + String.join(" ", buildCmd));
                int exit = runAndStream(buildCmd, project, buildOut, DotnetRunSupport.TYPE_RUN, runProcess);
                if (exit != 0) {
                    writeLine(buildOut, System.lineSeparator() + "[build] falhou (código " + exit + ").");
                    if (separatePanels) {
                        writeLine(out, "[erro] Falha na compilação (veja o painel \"" + BUILD_PANEL_NAME + "\").");
                    }
                    return;
                }
                writeLine(buildOut, System.lineSeparator() + "[build] OK.");
                flushQuietly(buildOut);
                if (separatePanels && showRunOutput != null) {
                    showRunOutput.run();
                }
                writeLine(out, "> " + String.join(" ", runCmd));
                ProcessBuilder runBuilder = new ProcessBuilder(runCmd)
                        .directory(project.toFile())
                        .redirectErrorStream(true);
                applyDotnetEnv(runBuilder, dotnet);
                mergeLaunchEnv(runBuilder, launchEnv);
                Process process = runBuilder.start();
                runProcess.set(process);
                activeProcesses.put(DotnetRunSupport.TYPE_RUN, process);
                stdinBridge.bind(process.getOutputStream());
                pump(process.getInputStream(), out);
                process.waitFor();
            } catch (Exception e) {
                safeWriteLine(consoleOut, "[erro] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                done.set(true);
                activeProcesses.remove(DotnetRunSupport.TYPE_RUN);
                stdinBridge.closeQuietly();
                flushQuietly(buildStream);
            }
        }, "dotnet-build-run");
        worker.setDaemon(true);
        worker.start();

        return RunProcessHandle.builder()
                .output(consoleIn)
                .input(stdinBridge)
                .readonly(false)
                .alive(() -> !done.get())
                .terminate(() -> {
                    Process p = runProcess.get();
                    if (p != null) {
                        destroyQuietly(p);
                    }
                    worker.interrupt();
                })
                .stdinMode(RunProcessHandle.StdinMode.TERMINAL)
                .build();
    }

    public RunProcessHandle buildThenDebug(Path project, Path dotnet, Path netcoredbg, String configuration,
                                           Path projectFile, String targetFramework,
                                           List<RunBreakpointData> breakpoints,
                                           DotnetDebugView view,
                                           Function<String, OutputPanelHandle> panelProvider,
                                           Runnable showRunOutput,
                                           Path startupHook,
                                           Consumer<DotnetDapDebugSession> sessionSink,
                                           List<String> programArgs,
                                           Map<String, String> launchEnv,
                                           boolean breakOnAllExceptions) {
        String config = configuration == null || configuration.isBlank() ? "Debug" : configuration;
        PipedInputStream consoleIn;
        PipedOutputStream consoleOut;
        try {
            consoleIn = new PipedInputStream(1 << 16);
            consoleOut = new PipedOutputStream(consoleIn);
        } catch (IOException e) {
            return errorHandle("Falha ao preparar console: " + e.getMessage());
        }

        AtomicBoolean done = new AtomicBoolean(false);
        AtomicReference<DotnetDapDebugSession> sessionRef = new AtomicReference<>();
        DeferredOutputStream stdinBridge = new DeferredOutputStream();

        OutputPanelHandle buildPanel = requestBuildPanel(panelProvider);
        OutputStream buildStream = buildPanel != null ? buildPanel.getOutputStream() : consoleOut;
        boolean separatePanels = buildPanel != null;

        List<String> buildCmd = new ArrayList<>(List.of(
                dotnet.toString(), "build", "-c", config, "--nologo"));
        if (projectFile != null) {
            buildCmd.add(projectFile.toString());
        }
        addFrameworkOption(buildCmd, targetFramework);

        Thread worker = new Thread(() -> {
            try (OutputStream out = consoleOut) {
                OutputStream buildOut = separatePanels ? buildStream : out;
                if (separatePanels) {
                    buildPanel.show();
                }
                writeLine(buildOut, "> " + String.join(" ", buildCmd));
                int exit = runAndStream(buildCmd, project, buildOut, DotnetRunSupport.TYPE_RUN, null);
                if (exit != 0) {
                    writeLine(buildOut, System.lineSeparator() + "[build] falhou (código " + exit + ").");
                    if (separatePanels) {
                        writeLine(out, "[erro] Falha na compilação (veja o painel \"" + BUILD_PANEL_NAME + "\").");
                    }
                    return;
                }
                writeLine(buildOut, System.lineSeparator() + "[build] OK.");
                flushQuietly(buildOut);
                if (separatePanels && showRunOutput != null) {
                    showRunOutput.run();
                }
                Path dll = resolveDebugTargetDll(project, projectFile, config, targetFramework);
                if (dll == null) {
                    writeLine(out, "[erro] Assembly de saída (.dll) não encontrado para depuração.");
                    return;
                }
                writeLine(out, "> netcoredbg " + dll.getFileName()
                        + " (DOTNET_ROOT=" + dotnet.toAbsolutePath().normalize().getParent() + ")");
                DotnetDapDebugSession session = new DotnetDapDebugSession(
                        netcoredbg, dotnet, dll, project, projectFile, targetFramework, config,
                        programArgs == null ? List.of() : programArgs, breakpoints, view, out, stdinBridge,
                        startupHook);
                session.setLaunchEnv(launchEnv);
                session.setBreakOnAllExceptions(breakOnAllExceptions);
                sessionRef.set(session);
                if (sessionSink != null) {
                    sessionSink.accept(session);
                }
                session.start();
                session.awaitTermination();
            } catch (Exception e) {
                safeWriteLine(consoleOut, "[erro] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                done.set(true);
                if (sessionSink != null) {
                    sessionSink.accept(null);
                }
                if (view != null) {
                    try {
                        view.onDebugFinished();
                    } catch (Exception ignored) {
                    }
                }
                stdinBridge.closeQuietly();
                flushQuietly(buildStream);
            }
        }, "dotnet-build-debug");
        worker.setDaemon(true);
        worker.start();

        return RunProcessHandle.builder()
                .output(consoleIn)
                .input(stdinBridge)
                .readonly(false)
                .alive(() -> !done.get())
                .terminate(() -> {
                    DotnetDapDebugSession s = sessionRef.get();
                    if (s != null) {
                        s.terminate();
                    }
                    worker.interrupt();
                })
                .stdinMode(RunProcessHandle.StdinMode.TERMINAL)
                .build();
    }

    static Path resolveDebugTargetDll(Path project, Path projectFile, String configuration) {
        return resolveDebugTargetDll(project, projectFile, configuration, null);
    }

    static Path resolveDebugTargetDll(Path project, Path projectFile, String configuration, String targetFramework) {
        Path csprojDir = projectFile != null && projectFile.getParent() != null
                ? projectFile.getParent()
                : project;
        if (csprojDir == null) {
            return null;
        }
        String assemblyName = resolveAssemblyName(projectFile, csprojDir);
        if (targetFramework != null && !targetFramework.isBlank()) {
            Path found = findAssemblyDll(csprojDir.resolve("bin")
                    .resolve(configuration)
                    .resolve(targetFramework.trim()), assemblyName);
            if (found != null) {
                return found;
            }
        }
        Path found = findAssemblyDll(csprojDir.resolve("bin").resolve(configuration), assemblyName);
        if (found != null) {
            return found;
        }
        return findAssemblyDll(csprojDir.resolve("bin"), assemblyName);
    }

    private static Path findAssemblyDll(Path dir, String assemblyName) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        String target = assemblyName + ".dll";
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase(target))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolveAssemblyName(Path projectFile, Path csprojDir) {
        if (projectFile != null) {
            try {
                Matcher matcher = ASSEMBLY_NAME.matcher(Files.readString(projectFile));
                if (matcher.find()) {
                    String name = matcher.group(1).trim();
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            } catch (Exception ignored) {
            }
            String fileName = projectFile.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            return dot > 0 ? fileName.substring(0, dot) : fileName;
        }
        return csprojDir.getFileName() == null ? "app" : csprojDir.getFileName().toString();
    }

    private OutputPanelHandle requestBuildPanel(Function<String, OutputPanelHandle> panelProvider) {
        if (panelProvider == null) {
            return null;
        }
        try {
            OutputPanelHandle panel = panelProvider.apply(BUILD_PANEL_NAME);
            if (panel == null || panel.getOutputStream() == null) {
                return null;
            }
            panel.clear();
            return panel;
        } catch (Exception e) {
            return null;
        }
    }

    private int runAndStream(List<String> command, Path workingDir, OutputStream out,
                             String type, AtomicReference<Process> currentProcess) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        applyDotnetEnv(builder, firstCommandPath(command));
        Process process = builder.start();
        if (currentProcess != null) {
            currentProcess.set(process);
        }
        if (type != null) {
            activeProcesses.put(type, process);
        }
        try {
            pump(process.getInputStream(), out);
            return process.waitFor();
        } finally {
            if (type != null) {
                activeProcesses.remove(type, process);
            }
            if (currentProcess != null) {
                currentProcess.compareAndSet(process, null);
            }
        }
    }

    private static void pump(java.io.InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    private static void writeLine(OutputStream out, String text) {
        try {
            out.write((text + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static void safeWriteLine(OutputStream out, String text) {
        try {
            writeLine(out, text);
        } catch (Exception ignored) {
        }
    }

    private static void flushQuietly(OutputStream out) {
        try {
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static void addFrameworkOption(List<String> command, String targetFramework) {
        if (command == null || targetFramework == null || targetFramework.isBlank()) {
            return;
        }
        command.add("-f");
        command.add(targetFramework.trim());
    }

    private static void addLaunchProfileOption(List<String> command, String launchProfile) {
        if (command == null || launchProfile == null || launchProfile.isBlank()) {
            return;
        }
        command.add("--launch-profile");
        command.add(launchProfile.trim());
    }

    private static Path firstCommandPath(List<String> command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        try {
            return Path.of(command.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    static void mergeLaunchEnv(ProcessBuilder builder, Map<String, String> launchEnv) {
        if (builder == null || launchEnv == null || launchEnv.isEmpty()) {
            return;
        }
        Map<String, String> env = builder.environment();
        for (Map.Entry<String, String> entry : launchEnv.entrySet()) {
            if ("DOTNET_STARTUP_HOOKS".equalsIgnoreCase(entry.getKey())) {
                String existing = env.get("DOTNET_STARTUP_HOOKS");
                env.put("DOTNET_STARTUP_HOOKS", existing == null || existing.isBlank()
                        ? entry.getValue()
                        : entry.getValue() + java.io.File.pathSeparator + existing);
            } else {
                env.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void applyDotnetEnv(ProcessBuilder builder, Path dotnet) {
        Map<String, String> env = builder.environment();
        env.put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        env.put("DOTNET_NOLOGO", "1");
        if (dotnet == null) {
            return;
        }
        Path root = dotnet.toAbsolutePath().normalize().getParent();
        if (root == null) {
            return;
        }
        String value = root.toString();
        env.put("DOTNET_ROOT", value);
        env.put("DOTNET_ROOT_X64", value);
        env.put("DOTNET_ROOT(x86)", value);
        env.put("DOTNET_HOST_PATH", dotnet.toAbsolutePath().normalize().toString());
        String pathKey = pathEnvName(env);
        String path = env.get(pathKey);
        env.put(pathKey, value + java.io.File.pathSeparator + (path == null ? "" : path));
    }

    private static String pathEnvName(Map<String, String> env) {
        if (env != null) {
            for (String key : env.keySet()) {
                if ("PATH".equalsIgnoreCase(key)) {
                    return key;
                }
            }
        }
        return isWindows() ? "Path" : "PATH";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public void stop(String type) {
        if (type == null) {
            activeProcesses.values().forEach(DotnetBuild::destroyQuietly);
            activeProcesses.clear();
            return;
        }
        Process process = activeProcesses.remove(type);
        destroyQuietly(process);
    }

    private void registerProcess(String type, Process process) {
        Process previous = activeProcesses.put(type, process);
        destroyQuietly(previous);
        process.onExit().thenRun(() -> activeProcesses.remove(type, process));
    }

    private static void destroyQuietly(Process process) {
        if (process == null) {
            return;
        }
        destroyProcessTree(process.toHandle());
    }

    private static void destroyQuietly(ProcessHandle process) {
        if (process == null) {
            return;
        }
        destroyProcessTree(process);
    }

    private static void destroyProcessTree(ProcessHandle root) {
        if (root == null) {
            return;
        }
        List<ProcessHandle> processes = new ArrayList<>();
        try {
            root.descendants().forEach(processes::add);
        } catch (Exception ignored) {
        }
        processes.add(root);

        for (ProcessHandle process : processes) {
            try {
                if (process.isAlive()) {
                    process.destroy();
                }
            } catch (Exception ignored) {
            }
        }

        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (ProcessHandle process : processes) {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
            }
        }
    }

    static RunProcessHandle errorHandle(String message) {
        String text = "[erro] " + (message == null || message.isBlank() ? "Falha desconhecida." : message)
                + System.lineSeparator();
        return RunProcessHandle.outputOnly(
                new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    static final class DeferredOutputStream extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile OutputStream delegate;

        synchronized void bind(OutputStream out) {
            this.delegate = out;
            try {
                out.write(buffer.toByteArray());
                out.flush();
                buffer.reset();
            } catch (IOException ignored) {
            }
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (delegate != null) {
                delegate.write(b);
            } else {
                buffer.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (delegate != null) {
                delegate.write(b, off, len);
            } else {
                buffer.write(b, off, len);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (delegate != null) {
                delegate.flush();
            }
        }

        void closeQuietly() {
            try {
                if (delegate != null) {
                    delegate.close();
                }
            } catch (IOException ignored) {
            }
        }
    }
}
