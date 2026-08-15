package dtm.ide.run;

import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.iis.IisAppPool;
import dtm.ide.iis.IisDeployment;
import dtm.ide.iis.IisEnvironment;
import dtm.ide.iis.IisExpressLauncher;
import dtm.ide.iis.IisService;
import dtm.ide.iis.IisWarmUp;
import dtm.ide.iis.IisWebConfig;
import dtm.ide.iis.IisWebProject;
import dtm.ide.iis.IisWorkerProcess;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final long CHILD_LOOKUP_GRACE_NANOS = TimeUnit.SECONDS.toNanos(3);

    private static final long CLR_WAIT_MS = 45_000;
    private static final long WORKER_WAIT_MS = 6_000;
    private static final long WORKER_WATCH_INTERVAL_MS = 2_000;
    private static final long POOL_STOP_WAIT_MS = 20_000;

    private static final int WARM_UP_ATTEMPTS = 12;
    private static final int WARM_UP_TIMEOUT_MS = 5_000;
    private static final long WARM_UP_RETRY_MS = 1_000;
    private static final long DEBUG_CONFIG_WAIT_MS = 20_000;
    private static final int DEBUG_TRIGGER_TIMEOUT_MS = 5_000;

    private static final Set<String> HOSTING_NOISE_PROCESSES = Set.of(
            "conhost.exe",
            "werfault.exe",
            "vsjitdebugger.exe"
    );

    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final AtomicReference<Runnable> activeIisStop = new AtomicReference<>();

    public RunProcessHandle build(Path project, Path dotnet, String configuration) {
        return build(project, dotnet, configuration, null, null);
    }

    public RunProcessHandle build(Path project, Path dotnet, String configuration,
                                  Path projectFile, String targetFramework) {
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().toString());
        command.add("build");
        command.add("-c");
        command.add(configuration == null || configuration.isBlank() ? "Debug" : configuration);
        command.add("--nologo");
        if (projectFile != null) {
            command.add(projectFile.toAbsolutePath().toString());
        }
        addFrameworkOption(command, targetFramework);
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
        return test(project, dotnet, configuration, null, null);
    }

    public RunProcessHandle test(Path project, Path dotnet, String configuration,
                                 Path projectFile, String targetFramework) {
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().toString());
        command.add("test");
        command.add("-c");
        command.add(configuration == null || configuration.isBlank() ? "Debug" : configuration);
        command.add("--nologo");
        if (projectFile != null) {
            command.add(projectFile.toAbsolutePath().toString());
        }
        addFrameworkOption(command, targetFramework);
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
        return launchTest(project, dotnet, configuration, filter, false);
    }

    public Process launchTest(Path project, Path dotnet, String configuration,
                              String filter, boolean waitForDebugger) throws IOException {
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
        builder.environment().put("DOTNET_CLI_UI_LANGUAGE", "en");
        if (waitForDebugger) {
            builder.environment().put("VSTEST_HOST_DEBUG", "1");
        }
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
            builder.environment().put("DOTNET_CLI_UI_LANGUAGE", "en");
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
        boolean afterHeader = false;
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (!afterHeader) {
                if (line.toLowerCase(Locale.ROOT).contains("available:")) {
                    afterHeader = true;
                }
                continue;
            }
            if (line.isEmpty() || line.indexOf('.') < 0 || !Character.isJavaIdentifierStart(line.charAt(0))) {
                continue;
            }
            if (!tests.contains(line)) {
                tests.add(line);
            }
        }
        if (tests.isEmpty()) {
            for (String raw : output.split("\\R")) {
                if (raw.isEmpty() || !Character.isWhitespace(raw.charAt(0))) {
                    continue;
                }
                String line = raw.strip();
                if (isStandaloneTestName(line) && !tests.contains(line)) {
                    tests.add(line);
                }
            }
        }
        return tests;
    }

    private static boolean isStandaloneTestName(String line) {
        if (line.isEmpty() || line.indexOf('.') < 0 || !Character.isJavaIdentifierStart(line.charAt(0))) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private RunProcessHandle launchProcess(String type, List<String> command, Path project, boolean interactive) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(project.toFile());
            builder.redirectErrorStream(true);

            applyDotnetEnv(builder, firstCommandPath(command));
            Process process = builder.start();
            long startedNanos = System.nanoTime();
            registerProcess(type, process);
            return RunProcessHandle.builder()
                    .process(process)
                    .readonly(!interactive)
                    .terminate(() -> destroyQuietly(process))
                    .processPid(DotnetRunSupport.TYPE_RUN.equals(type)
                            ? () -> resolveApplicationPid(process, startedNanos)
                            : process::pid)
                    .build();
        } catch (IOException e) {
            return errorHandle("Falha ao iniciar dotnet: " + e.getMessage());
        }
    }

    static long resolveApplicationPid(Process process, long startedNanos) {
        if (process == null || !process.isAlive()) {
            return 0L;
        }

        ProcessHandle application = deepestSingleDescendant(process.toHandle());
        if (application.pid() != process.pid()) {
            return application.pid();
        }

        boolean graceElapsed = System.nanoTime() - startedNanos >= CHILD_LOOKUP_GRACE_NANOS;
        return graceElapsed ? process.pid() : 0L;
    }

    private static ProcessHandle deepestSingleDescendant(ProcessHandle handle) {
        ProcessHandle current = handle;
        while (true) {
            List<ProcessHandle> children = current.children()
                    .filter(ProcessHandle::isAlive)
                    .filter(child -> !isHostingNoise(child))
                    .toList();
            if (children.size() != 1) {
                return current;
            }
            current = children.getFirst();
        }
    }

    private static boolean isHostingNoise(ProcessHandle handle) {
        String command = handle.info().command().orElse(null);
        if (command == null) {
            return false;
        }
        String name = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        return HOSTING_NOISE_PROCESSES.contains(name);
    }

    public RunProcessHandle buildThenRun(Path project, Path dotnet, String configuration,
                                         Path projectFile, String targetFramework, String launchProfile,
                                         Function<String, OutputPanelHandle> panelProvider,
                                         Runnable showRunOutput, List<String> programArgs,
                                         Map<String, String> launchEnv, Path runWorkingDirectory) {
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
        AtomicReference<Process> monitoredProcess = new AtomicReference<>();
        AtomicLong monitoredStartedNanos = new AtomicLong();

        OutputPanelHandle buildPanel = requestBuildPanel(panelProvider);
        OutputStream buildStream = buildPanel != null ? buildPanel.getOutputStream() : consoleOut;
        boolean separatePanels = buildPanel != null;

        List<String> buildCmd = new ArrayList<>(List.of(dotnet.toString(), "build", "-c", config, "--nologo"));
        if (projectFile != null) {
            buildCmd.add(projectFile.toString());
        }
        addFrameworkOption(buildCmd, targetFramework);
        List<String> runCmd = new ArrayList<>(List.of(dotnet.toString(), "run", "--no-build", "-c", config));
        if (projectFile != null) {
            runCmd.add("--project");
            runCmd.add(projectFile.toString());
        }
        addFrameworkOption(runCmd, targetFramework);
        addLaunchProfileOption(runCmd, launchProfile);
        if (programArgs != null && !programArgs.isEmpty()) {
            runCmd.add("--");
            runCmd.addAll(programArgs);
        }

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
                        .directory((runWorkingDirectory == null ? project : runWorkingDirectory).toFile())
                        .redirectErrorStream(true);
                applyDotnetEnv(runBuilder, dotnet);
                mergeLaunchEnv(runBuilder, launchEnv);
                Process process = runBuilder.start();
                runProcess.set(process);
                monitoredStartedNanos.set(System.nanoTime());
                monitoredProcess.set(process);
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
                .processPid(() -> resolveApplicationPid(monitoredProcess.get(), monitoredStartedNanos.get()))
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
                .processPid(() -> {
                    DotnetDapDebugSession s = sessionRef.get();
                    return s != null ? s.getMonitoredPid() : 0L;
                })
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

    public RunProcessHandle attachDebugger(Path project, Path dotnet, Path netcoredbg, long pid,
                                           List<RunBreakpointData> breakpoints, DotnetDebugView view,
                                           Consumer<DotnetDapDebugSession> sessionSink,
                                           boolean breakOnAllExceptions) {
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
        Thread worker = new Thread(() -> {
            try (OutputStream out = consoleOut) {
                writeLine(out, "> netcoredbg attach PID " + pid);
                DotnetDapDebugSession session = new DotnetDapDebugSession(
                        netcoredbg, dotnet, null, project, null, null, "Debug", List.of(),
                        breakpoints == null ? List.of() : breakpoints, view, out, stdinBridge,
                        null, pid, null);
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
            }
        }, "dotnet-attach-debug");
        worker.setDaemon(true);
        worker.start();
        return RunProcessHandle.builder()
                .output(consoleIn)
                .input(stdinBridge)
                .processPid(pid)
                .readonly(false)
                .alive(() -> !done.get())
                .terminate(() -> {
                    DotnetDapDebugSession session = sessionRef.get();
                    if (session != null) {
                        session.terminate();
                    }
                    worker.interrupt();
                })
                .stdinMode(RunProcessHandle.StdinMode.TERMINAL)
                .build();
    }

    public RunProcessHandle launchIis(IisLaunchRequest request,
                                      Function<String, OutputPanelHandle> panelProvider,
                                      Runnable showRunOutput) {
        PipedInputStream consoleIn;
        PipedOutputStream consoleOut;
        try {
            consoleIn = new PipedInputStream(1 << 16);
            consoleOut = new PipedOutputStream(consoleIn);
        } catch (IOException e) {
            return errorHandle("Falha ao preparar console: " + e.getMessage());
        }

        AtomicBoolean done = new AtomicBoolean(false);
        AtomicReference<Process> hostProcess = new AtomicReference<>();
        AtomicReference<DotnetDapDebugSession> sessionRef = new AtomicReference<>();
        AtomicLong monitoredPid = new AtomicLong();
        AtomicReference<Runnable> stopHolder = new AtomicReference<>();
        DeferredOutputStream stdinBridge = new DeferredOutputStream();
        CountDownLatch stopSignal = new CountDownLatch(1);

        OutputPanelHandle buildPanel = requestBuildPanel(panelProvider);
        OutputStream buildStream = buildPanel != null ? buildPanel.getOutputStream() : consoleOut;
        boolean separatePanels = buildPanel != null;

        Thread worker = new Thread(() -> {
            try (OutputStream out = consoleOut) {
                OutputStream buildOut = separatePanels ? buildStream : out;
                if (separatePanels) {
                    buildPanel.show();
                }
                String stoppedPool = stopPoolForBuild(request, buildOut);
                boolean prepared;
                try {
                    prepared = prepareIisContent(request, buildOut, out);
                } finally {
                    startPoolAfterBuild(stoppedPool, out);
                }
                if (!prepared) {
                    return;
                }
                if (separatePanels && showRunOutput != null) {
                    showRunOutput.run();
                }
                if (request.iisExpress()) {
                    runIisExpress(request, out, hostProcess, sessionRef, monitoredPid, stdinBridge, stopSignal);
                } else {
                    runFullIis(request, out, sessionRef, monitoredPid, stdinBridge, stopSignal);
                }
            } catch (Exception e) {
                safeWriteLine(consoleOut, "[erro] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                stopSignal.countDown();
                done.set(true);
                activeProcesses.remove(DotnetRunSupport.TYPE_RUN);
                activeIisStop.compareAndSet(stopHolder.get(), null);
                if (request.sessionSink() != null) {
                    request.sessionSink().accept(null);
                }
                if (request.debug() && request.debugView() != null) {
                    try {
                        request.debugView().onDebugFinished();
                    } catch (Exception ignored) {
                    }
                }
                stdinBridge.closeQuietly();
                flushQuietly(buildStream);
            }
        }, "dotnet-iis-run");
        worker.setDaemon(true);
        worker.start();

        Runnable stopAction = () -> {
            stopSignal.countDown();
            DotnetDapDebugSession session = sessionRef.get();
            if (session != null) {
                session.terminate();
            }
            Process process = hostProcess.get();
            if (process != null) {
                destroyQuietly(process);
                worker.interrupt();
            }
        };
        stopHolder.set(stopAction);
        activeIisStop.set(stopAction);
        if (done.get()) {
            activeIisStop.compareAndSet(stopAction, null);
        }

        return RunProcessHandle.builder()
                .output(consoleIn)
                .input(stdinBridge)
                .processPid(monitoredPid::get)
                .readonly(false)
                .alive(() -> !done.get())
                .terminate(stopAction)
                .stdinMode(RunProcessHandle.StdinMode.TERMINAL)
                .build();
    }

    private static String stopPoolForBuild(IisLaunchRequest request, OutputStream buildOut) {
        if (request.iisExpress() || !IisWebProject.requiresPublish(request.projectFile())) {
            return null;
        }
        String pool = publishBlockingPool(request);
        if (pool == null) {
            return null;
        }
        writeLine(buildOut, "[iis] Parando o pool \"" + pool
                + "\" antes de publicar (o w3wp mantém os assemblies em uso).");
        IisService.Result stopped = IisService.stopAppPool(pool);
        if (!stopped.success()) {
            writeLine(buildOut, "[aviso] " + stopped.message());
        }
        long alive = IisWarmUp.awaitWorkerExit(pool, POOL_STOP_WAIT_MS);
        if (alive > 0) {
            writeLine(buildOut, "[aviso] O w3wp " + alive + " do pool \"" + pool + "\" não encerrou em "
                    + (POOL_STOP_WAIT_MS / 1000) + "s: a publicação pode falhar por arquivo em uso.");
        } else {
            writeLine(buildOut, "[iis] Pool parado; os arquivos publicados estão liberados.");
        }
        return pool;
    }

    private static void startPoolAfterBuild(String pool, OutputStream out) {
        if (pool == null || pool.isBlank()) {
            return;
        }
        IisService.Result started = IisService.startAppPoolStarted(pool);
        writeLine(out, started.success()
                ? "[iis] Pool \"" + pool + "\" iniciado após a compilação/publicação."
                : "[aviso] " + started.message());
    }

    private static String publishBlockingPool(IisLaunchRequest request) {
        String existing = IisService.existingAppPool(request.siteName(), request.applicationPath());
        String pool = existing == null || existing.isBlank() ? request.appPoolName() : existing;
        if (pool == null || pool.isBlank() || IisService.findAppPool(pool) == null) {
            return null;
        }
        return pool;
    }

    private boolean prepareIisContent(IisLaunchRequest request, OutputStream buildOut, OutputStream out)
            throws Exception {
        Path projectFile = request.projectFile();
        if (!IisWebProject.requiresPublish(projectFile)) {
            writeLine(out, "[iis] Projeto .NET Framework: usando o diretório do projeto como raiz de conteúdo.");
            return true;
        }
        Path publishDirectory = IisWebProject.publishDirectory(projectFile, request.effectiveConfiguration());
        List<String> publishCmd = new ArrayList<>(List.of(
                request.dotnet().toString(), "publish", projectFile.toString(),
                "-c", request.effectiveConfiguration(), "-o", publishDirectory.toString(), "--nologo"));
        addFrameworkOption(publishCmd, request.targetFramework());
        if (request.debug()) {
            publishCmd.add("-p:DebugType=portable");
            publishCmd.add("-p:DebugSymbols=true");
        }
        writeLine(buildOut, "> " + String.join(" ", publishCmd));
        int exit = runAndStream(publishCmd, projectFile.getParent(), buildOut, DotnetRunSupport.TYPE_RUN, null);
        if (exit != 0) {
            writeLine(buildOut, System.lineSeparator() + "[publish] falhou (código " + exit + ").");
            writeLine(out, "[erro] Falha ao publicar o projeto para o IIS.");
            return false;
        }
        writeLine(buildOut, System.lineSeparator() + "[publish] OK -> " + publishDirectory);
        flushQuietly(buildOut);
        return true;
    }

    private void runIisExpress(IisLaunchRequest request, OutputStream out,
                               AtomicReference<Process> hostProcess,
                               AtomicReference<DotnetDapDebugSession> sessionRef,
                               AtomicLong monitoredPid,
                               DeferredOutputStream stdinBridge,
                               CountDownLatch stopSignal) throws Exception {
        Path contentRoot = IisWebProject.contentRoot(request.projectFile(), request.effectiveConfiguration());
        if (contentRoot == null || !Files.isDirectory(contentRoot)) {
            writeLine(out, "[erro] Raiz de conteúdo inexistente: " + contentRoot);
            return;
        }
        IisExpressLauncher.SiteSpec spec = new IisExpressLauncher.SiteSpec(
                request.siteName(), contentRoot, request.bindings(), request.aspNetCore());
        IisExpressLauncher.Prepared prepared = IisExpressLauncher.prepare(
                request.projectFile(), request.workspaceRoot(), spec);
        writeLine(out, "[iis] applicationhost.config gerado em " + prepared.configFile());

        List<String> command = IisExpressLauncher.command(prepared.configFile(), prepared.siteName());
        writeLine(out, "> " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(contentRoot.toFile())
                .redirectErrorStream(true);
        applyDotnetEnv(builder, request.dotnet());
        mergeLaunchEnv(builder, request.environment());
        Process process = builder.start();
        hostProcess.set(process);
        activeProcesses.put(DotnetRunSupport.TYPE_RUN, process);
        stdinBridge.bind(process.getOutputStream());
        monitoredPid.set(process.pid());

        Thread pumpThread = new Thread(() -> {
            try {
                pump(process.getInputStream(), out);
            } catch (IOException e) {
                log.debug("Saída do IIS Express encerrada: {}", e.getMessage());
            }
        }, "iisexpress-output");
        pumpThread.setDaemon(true);
        pumpThread.start();

        String url = request.resolveUrl();
        writeLine(out, "[iis] Aplicação disponível em " + url);
        warmUp(url, out);

        if (request.debug()) {
            long pid = IisWarmUp.awaitClrProcess(process.toHandle(), request.hostingModel(),
                    request.assemblyName(), CLR_WAIT_MS);
            if (pid <= 0) {
                writeLine(out, "[erro] Não foi possível localizar o processo CoreCLR do IIS Express para depurar.");
            } else {
                monitoredPid.set(pid);
                attachDebugSession(request, out, sessionRef, contentRoot, pid, stdinBridge, false);
            }
        }
        if (request.launchBrowser()) {
            IisWarmUp.openBrowser(url);
        }
        if (request.debug()) {
            DotnetDapDebugSession session = sessionRef.get();
            if (session != null) {
                session.awaitTermination();
            }
            destroyQuietly(process);
        } else {
            process.waitFor();
        }
        stopSignal.countDown();
        pumpThread.interrupt();
    }

    private void runFullIis(IisLaunchRequest request, OutputStream out,
                            AtomicReference<DotnetDapDebugSession> sessionRef,
                            AtomicLong monitoredPid,
                            DeferredOutputStream stdinBridge,
                            CountDownLatch stopSignal) throws Exception {
        Path contentRoot = IisWebProject.contentRoot(request.projectFile(), request.effectiveConfiguration());
        if (contentRoot == null || !Files.isDirectory(contentRoot)) {
            writeLine(out, "[erro] Raiz de conteúdo inexistente: " + contentRoot);
            return;
        }
        IisDeployment.Target target = new IisDeployment.Target(request.siteName(), request.applicationPath(),
                request.appPoolName(), contentRoot, request.primaryBinding(), request.aspNetCore());

        if (request.autoCreateSite()) {
            writeLine(out, "[iis] Garantindo site \"" + target.siteName()
                    + "\" e pool \"" + target.appPoolName() + "\"...");
            IisService.Result ensured = IisDeployment.ensure(target);
            if (!ensured.success()) {
                writeLine(out, "[erro] " + ensured.message());
                IisService.startAppPool(target.appPoolName());
                return;
            }
        } else if (IisService.findSite(target.siteName()) == null) {
            writeLine(out, "[erro] O site \"" + target.siteName() + "\" não existe no IIS e a criação automática "
                    + "está desativada nas configurações do plugin.");
            IisService.startAppPool(target.appPoolName());
            return;
        }
        if (request.aspNetCore()) {
            applyWebConfigEnvironment(request, target, contentRoot, out);
        }
        IisService.Result started = IisDeployment.start(target);
        if (!started.success()) {
            writeLine(out, "[erro] " + started.message());
            writeLine(out, "[erro] O depurador não será anexado enquanto o site e o pool não estiverem iniciados.");
            return;
        }

        if (request.debug() && !checkIisDebugPrerequisites(request, target, contentRoot, out)) {
            return;
        }

        IisService.DebugPoolState watchdogs = null;
        if (request.debug()) {
            watchdogs = IisService.suspendPoolWatchdogs(target.appPoolName());
            if (watchdogs == null) {
                writeLine(out, "[aviso] Não foi possível desligar o ping/idle timeout do pool. O IIS pode "
                        + "encerrar o w3wp enquanto ele estiver parado em um breakpoint.");
            } else {
                writeLine(out, "[iis] Watchdogs do pool suspensos durante a depuração "
                        + "(ping, idle timeout e reciclagem periódica).");
            }
        }
        try {
            runFullIisSession(request, out, sessionRef, monitoredPid, stdinBridge, stopSignal,
                    target, contentRoot);
        } finally {
            if (watchdogs != null) {
                IisService.Result restored = IisService.restorePoolWatchdogs(watchdogs);
                writeLine(out, restored.success()
                        ? "[iis] Watchdogs do pool restaurados."
                        : "[aviso] " + restored.message());
            }
        }
    }

    private static void applyWebConfigEnvironment(IisLaunchRequest request, IisDeployment.Target target,
                                                  Path contentRoot, OutputStream out) {
        IisWebConfig.Result result = IisWebConfig.applyAspNetCore(contentRoot, request.environment(), true);
        if (!result.changed()) {
            return;
        }
        String environment = request.environment() == null
                ? null : request.environment().get("ASPNETCORE_ENVIRONMENT");
        writeLine(out, "[iis] web.config ajustado para o IIS: ASPNETCORE_ENVIRONMENT="
                + (environment == null || environment.isBlank() ? "(herdado)" : environment)
                + " (o IIS, ao contrário do IIS Express, não herda o ambiente da IDE).");
        if (result.stdoutLogEnabled()) {
            Path logs = contentRoot.resolve(IisWebConfig.LOGS_FOLDER);
            try {
                Files.createDirectories(logs);
            } catch (IOException e) {
                log.debug("Falha ao criar a pasta de logs do IIS: {}", e.getMessage());
            }
            IisDeployment.grantPoolWrite(target.appPoolName(), logs);
            writeLine(out, "[iis] Log de startup do ASP.NET Core habilitado em " + logs
                    + " (consulte stdout*.log para ver a exceção do 500.30).");
        }
    }

    private void runFullIisSession(IisLaunchRequest request, OutputStream out,
                                   AtomicReference<DotnetDapDebugSession> sessionRef,
                                   AtomicLong monitoredPid,
                                   DeferredOutputStream stdinBridge,
                                   CountDownLatch stopSignal,
                                   IisDeployment.Target target,
                                   Path contentRoot) throws Exception {
        String url = request.resolveUrl();
        writeLine(out, "[iis] Site \"" + target.siteName() + "\" no pool \"" + target.appPoolName()
                + "\" servindo " + contentRoot);
        writeLine(out, "[iis] Aplicação disponível em " + url);

        String effectivePool = effectiveAppPool(target, out);
        boolean publishWorkerDuringStartup = !request.debug()
                || request.hostingModel() == IisWebProject.HostingModel.IN_PROCESS;
        Thread pidWatcher = publishWorkerDuringStartup
                ? startWorkerPidWatcher(effectivePool, monitoredPid, stopSignal)
                : null;

        warmUpFullIis(target, url, out);
        long workerPid = IisWarmUp.awaitWorkerPid(effectivePool, WORKER_WAIT_MS);
        if (workerPid > 0) {
            if (publishWorkerDuringStartup) {
                monitoredPid.set(workerPid);
            }
            writeLine(out, "[iis] Worker process do pool \"" + effectivePool + "\": PID " + workerPid + ".");
        } else {
            writeLine(out, "[aviso] w3wp do pool \"" + effectivePool
                    + "\" ainda não subiu. O monitor será atualizado assim que o pool atender uma requisição.");
            describeWorkers(out);
        }

        boolean attached = false;
        if (request.debug()) {
            if (!IisEnvironment.isElevated()) {
                writeLine(out, "[iis] A IDE não está elevada: o netcoredbg vai anexar pelo assistente elevado "
                        + "do IIS (reaproveita a mesma confirmação do UAC, sem pedir de novo).");
            }
            long pid = request.hostingModel() == IisWebProject.HostingModel.IN_PROCESS && workerPid > 0
                    ? workerPid
                    : IisWarmUp.awaitWorkerProcess(effectivePool, request.hostingModel(),
                            request.assemblyName(), CLR_WAIT_MS);
            if (pid <= 0) {
                writeLine(out, "[erro] Nenhum processo CoreCLR do pool \"" + effectivePool
                        + "\" foi encontrado em " + (CLR_WAIT_MS / 1000) + "s. Hospedagem "
                        + request.hostingModel().descriptor() + ": confirme que o pool está iniciado e que a "
                        + "aplicação respondeu a uma requisição.");
                describeWorkers(out);
            } else {
                monitoredPid.set(pid);
                writeLine(out, "[iis] Anexando depurador ao processo " + pid + " ("
                        + request.hostingModel().descriptor() + ").");
                Path sourceRoot = request.projectFile().getParent() == null
                        ? contentRoot : request.projectFile().getParent();
                attachDebugSession(request, out, sessionRef, sourceRoot, pid, stdinBridge, true);
                DotnetDapDebugSession session = sessionRef.get();
                attached = session != null && session.awaitConfigured(DEBUG_CONFIG_WAIT_MS, TimeUnit.MILLISECONDS);
                writeLine(out, attached
                        ? "[iis] Depurador anexado e breakpoints registrados; disparando uma requisição para "
                                + "acioná-los."
                        : "[aviso] O depurador ainda está registrando os breakpoints. Recarregue a página se o "
                                + "primeiro acesso não parar.");
                writeLine(out, "[iis] Breakpoints em código de inicialização só param em um novo start do worker; "
                        + "os de controllers param a cada requisição.");
            }
        }
        if (attached) {
            triggerDebugRequest(url);
        }
        if (request.launchBrowser()) {
            IisWarmUp.openBrowser(url);
        }

        if (request.debug() && sessionRef.get() != null) {
            try {
                sessionRef.get().awaitTermination();
            } finally {
                if (pidWatcher != null) {
                    pidWatcher.interrupt();
                }
            }
        } else {
            writeLine(out, "[iis] O site continua hospedado no IIS. Use \"Parar\" para encerrar o monitoramento.");
            try {
                stopSignal.await();
            } finally {
                if (pidWatcher != null) {
                    pidWatcher.interrupt();
                }
            }
        }
        stopIisOnExit(request, target, effectivePool, out);
    }

    private static void triggerDebugRequest(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        Thread trigger = new Thread(() -> IisWarmUp.request(url, DEBUG_TRIGGER_TIMEOUT_MS), "iis-debug-trigger");
        trigger.setDaemon(true);
        trigger.start();
    }

    private static Thread startWorkerPidWatcher(String pool, AtomicLong monitoredPid, CountDownLatch stopSignal) {
        if (pool == null || pool.isBlank()) {
            return null;
        }
        Thread watcher = new Thread(() -> {
            while (stopSignal.getCount() > 0 && !Thread.currentThread().isInterrupted()) {
                long pid = IisWarmUp.findWorkerPid(pool);
                if (pid > 0 && pid != monitoredPid.get()) {
                    monitoredPid.set(pid);
                }
                try {
                    if (stopSignal.await(WORKER_WATCH_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "iis-pid-watcher");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    private static void stopIisOnExit(IisLaunchRequest request, IisDeployment.Target target,
                                      String effectivePool, OutputStream out) {
        String pool = effectivePool == null || effectivePool.isBlank()
                ? target.appPoolName() : effectivePool;
        if (shouldStopPoolOnExit(request)) {
            IisService.Result stopped = IisService.stopAppPool(pool);
            writeLine(out, stopped.success()
                    ? "[iis] Pool \"" + pool + "\" parado."
                    : "[aviso] " + stopped.message());
        } else {
            writeLine(out, "[iis] Pool \"" + pool
                    + "\" mantido iniciado; o site continua disponível após encerrar Run/Debug.");
        }
    }

    static boolean shouldStopPoolOnExit(IisLaunchRequest request) {
        return request != null && request.stopPoolOnExit();
    }

    private static void warmUpFullIis(IisDeployment.Target target, String url, OutputStream out) {
        IisWarmUp.WarmUpResult result = warmUpStatus(url);
        if (result.status() == 503) {
            writeLine(out, "[aviso] O IIS respondeu 503: o pool \"" + target.appPoolName()
                    + "\" não está atendendo. Reiniciando o pool...");
            IisService.Result restarted = IisService.startAppPoolStarted(target.appPoolName());
            if (!restarted.success()) {
                writeLine(out, "[aviso] " + restarted.message());
            }
            IisService.recycleAppPool(target.appPoolName());
            result = warmUpStatus(url);
        }
        if (result.status() == 503) {
            reportUnavailablePool(target, out);
            return;
        }
        if (result.status() <= 0) {
            writeLine(out, "[aviso] A aplicação não respondeu ao warm-up em " + url + ".");
            return;
        }
        writeLine(out, "[iis] Warm-up respondeu HTTP " + result.status()
                + (result.redirects() > 0 ? " em " + result.url() + " (após " + result.redirects()
                        + (result.redirects() == 1 ? " redirecionamento)" : " redirecionamentos)") : "")
                + ".");
    }

    private static IisWarmUp.WarmUpResult warmUpStatus(String url) {
        IisWarmUp.WarmUpResult result = new IisWarmUp.WarmUpResult(0, url, 0);
        for (int attempt = 0; attempt < WARM_UP_ATTEMPTS; attempt++) {
            result = IisWarmUp.warm(url, WARM_UP_TIMEOUT_MS);
            if (result.reachedApplication() && result.status() != 503) {
                return result;
            }
            try {
                Thread.sleep(WARM_UP_RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
        }
        return result;
    }

    private static void reportUnavailablePool(IisDeployment.Target target, OutputStream out) {
        IisAppPool pool = IisService.findAppPool(target.appPoolName());
        writeLine(out, "[erro] 503 Serviço Indisponível: o pool \"" + target.appPoolName() + "\" está "
                + (pool == null ? "ausente" : IisService.describeState(pool.state()))
                + " e o worker process não sobe.");
        if (pool != null && target.aspNetCore() && !pool.noManagedCode()) {
            writeLine(out, "[dica] O pool está com managedRuntimeVersion=" + pool.managedRuntimeVersion()
                    + "; ASP.NET Core exige \"Sem Código Gerenciado\".");
        }
        if (IisEnvironment.current().aspNetCoreModuleMissing()) {
            writeLine(out, "[dica] O ASP.NET Core Module não foi encontrado: instale o ASP.NET Core Hosting Bundle.");
        }
        writeLine(out, "[dica] Causa mais comum: a identidade do pool não consegue ler " + target.contentRoot()
                + ". Conceda acesso com:");
        writeLine(out, "       icacls \"" + target.contentRoot() + "\" /grant \"IIS AppPool\\"
                + target.appPoolName() + "\":(OI)(CI)RX /T");
        writeLine(out, "[dica] O Log de Eventos do Windows (Application) traz o motivo exato da parada do pool.");
    }

    private void attachDebugSession(IisLaunchRequest request, OutputStream out,
                                    AtomicReference<DotnetDapDebugSession> sessionRef,
                                    Path contentRoot, long pid,
                                    DeferredOutputStream stdinBridge,
                                    boolean elevatedServer) throws Exception {
        writeLine(out, "> netcoredbg attach PID " + pid);
        DotnetDapDebugSession session = new DotnetDapDebugSession(
                request.netcoredbg(), request.dotnet(), null, contentRoot, null, null,
                request.effectiveConfiguration(), List.of(),
                request.breakpoints() == null ? List.of() : request.breakpoints(),
                request.debugView(), out, stdinBridge, null, pid, null);
        session.setBreakOnAllExceptions(request.breakOnAllExceptions());
        session.setElevatedServer(elevatedServer);
        session.setHotReloadOnAttach(false);
        sessionRef.set(session);
        if (request.sessionSink() != null) {
            request.sessionSink().accept(session);
        }
        session.start();
    }

    private static String effectiveAppPool(IisDeployment.Target target, OutputStream out) {
        String actual = IisService.existingAppPool(target.siteName(), target.applicationPath());
        if (actual == null || actual.isBlank() || actual.equalsIgnoreCase(target.appPoolName())) {
            return target.appPoolName();
        }
        writeLine(out, "[iis] A aplicação \"" + target.applicationName() + "\" está no pool \"" + actual
                + "\", não em \"" + target.appPoolName() + "\": o worker será procurado no pool real.");
        return actual;
    }

    private static void describeWorkers(OutputStream out) {
        List<IisWorkerProcess> workers = IisService.workerProcesses();
        if (workers.isEmpty()) {
            writeLine(out, "[dica] O IIS não tem nenhum w3wp ativo: a requisição de warm-up não chegou a "
                    + "executar a aplicação. Abra a URL no navegador e tente depurar de novo.");
            return;
        }
        StringBuilder text = new StringBuilder("[dica] Workers ativos no IIS:");
        for (IisWorkerProcess worker : workers) {
            text.append(System.lineSeparator()).append("       PID ").append(worker.pid())
                    .append(" no pool \"").append(worker.appPoolName()).append('"');
        }
        writeLine(out, text.toString());
    }

    private static boolean checkIisDebugPrerequisites(IisLaunchRequest request, IisDeployment.Target target,
                                                      Path contentRoot, OutputStream out) {
        IisAppPool pool = IisService.findAppPool(target.appPoolName());
        if (pool != null) {
            writeLine(out, "[iis] Pool \"" + pool.name() + "\": identidade=" + pool.identityLabel()
                    + ", runtime=" + pool.runtimeLabel()
                    + ", 32 bits=" + (pool.enable32Bit() ? "sim" : "não") + ".");
            if (pool.enable32Bit()) {
                writeLine(out, "[erro] O pool está em modo 32 bits e o netcoredbg é 64 bits: o attach ao w3wp "
                        + "é impossível nessa combinação. Desative com:");
                writeLine(out, "       appcmd set apppool \"" + pool.name()
                        + "\" /enable32BitAppOnWin64:false");
                return false;
            }
        }
        String assembly = request.assemblyName();
        if (assembly != null && !assembly.isBlank() && contentRoot != null) {
            Path pdb = contentRoot.resolve(assembly + ".pdb");
            if (!Files.isRegularFile(pdb)) {
                writeLine(out, "[erro] Símbolos ausentes: " + pdb + " não existe. Sem o .pdb ao lado do "
                        + "assembly publicado nenhum breakpoint é resolvido. Publique em Debug ou defina "
                        + "<DebugType>portable</DebugType> no projeto.");
                return false;
            }
        }
        if ("Release".equalsIgnoreCase(request.effectiveConfiguration())) {
            writeLine(out, "[aviso] Configuração Release: o código está otimizado e muitos breakpoints "
                    + "não param ou param em linhas deslocadas. Use Debug para depurar no IIS.");
        }
        return true;
    }

    private static void warmUp(String url, OutputStream out) {
        for (int attempt = 0; attempt < WARM_UP_ATTEMPTS; attempt++) {
            if (IisWarmUp.request(url, WARM_UP_TIMEOUT_MS)) {
                return;
            }
            try {
                Thread.sleep(WARM_UP_RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        writeLine(out, "[aviso] A aplicação não respondeu ao warm-up em " + url + ".");
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
            stopIis();
            activeProcesses.values().forEach(DotnetBuild::destroyQuietly);
            activeProcesses.clear();
            return;
        }
        if (DotnetRunSupport.TYPE_RUN.equals(type)) {
            stopIis();
        }
        Process process = activeProcesses.remove(type);
        destroyQuietly(process);
    }

    public void stopIis() {
        Runnable stopAction = activeIisStop.getAndSet(null);
        if (stopAction != null) {
            stopAction.run();
        }
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
