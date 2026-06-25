package dtm.ide.run;

import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.sdk.DotnetSdkService;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Slf4j
public final class DotnetRunSupport {

    public static final String TYPE_BUILD = "dotnet-build";
    public static final String TYPE_RUN = "dotnet-run";
    public static final String TYPE_TEST = "dotnet-test";

    public static final String TYPE_CURRENT_FILE = "current_file";

    public static final String PROP_CONFIGURATION = "configuration";
    public static final String PROP_LAUNCH_PROFILE = "launchProfile";

    private static final Pattern MAIN_PATTERN = Pattern.compile(
            "\\bstatic\\s+(?:async\\s+)?[\\w<>\\[\\].,\\s]*?\\bMain\\s*\\(");

    private final DotnetBuild build = new DotnetBuild();

    private volatile Path projectPath;
    private volatile DotnetSdkService sdkService;
    private volatile DotnetSdkService.DownloadProgressListener downloadProgress = DotnetSdkService.DownloadProgressListener.NOOP;
    private volatile Supplier<Path> activeFileSupplier;
    private volatile Supplier<String> activeTextSupplier;
    private volatile Function<String, OutputPanelHandle> outputPanels;
    private volatile Runnable runOutputFocus;
    private volatile BooleanSupplier breakOnAllExceptionsSupplier;
    private volatile Consumer<Boolean> debugSessionStateListener;
    private volatile Consumer<DotnetHotReloadResult> hotReloadResultListener;

    private final AtomicReference<DotnetDapDebugSession> debugSession = new AtomicReference<>();
    private volatile DotnetDebugView debugView;

    public void bindDebugView(DotnetDebugView debugView) {
        this.debugView = debugView;
    }

    public void bindProject(Path projectPath) {
        this.projectPath = projectPath == null ? null : projectPath.toAbsolutePath().normalize();
    }

    public void bindSdk(DotnetSdkService sdkService) {
        this.sdkService = sdkService;
    }

    public void bindDownloadProgress(DotnetSdkService.DownloadProgressListener listener) {
        this.downloadProgress = listener == null ? DotnetSdkService.DownloadProgressListener.NOOP : listener;
    }

    public void bindActiveFile(Supplier<Path> activeFileSupplier) {
        this.activeFileSupplier = activeFileSupplier;
    }

    public void bindActiveText(Supplier<String> activeTextSupplier) {
        this.activeTextSupplier = activeTextSupplier;
    }

    public void bindOutputPanels(Function<String, OutputPanelHandle> outputPanels) {
        this.outputPanels = outputPanels;
    }

    public void bindRunOutputFocus(Runnable runOutputFocus) {
        this.runOutputFocus = runOutputFocus;
    }

    public void bindBreakOnAllExceptions(BooleanSupplier supplier) {
        this.breakOnAllExceptionsSupplier = supplier;
    }

    public void bindDebugSessionStateListener(Consumer<Boolean> listener) {
        this.debugSessionStateListener = listener;
    }

    public void bindHotReloadResultListener(Consumer<DotnetHotReloadResult> listener) {
        this.hotReloadResultListener = listener;
    }

    public static boolean isCurrentFileType(RunConfigurationData data) {
        return data != null && TYPE_CURRENT_FILE.equals(data.getType());
    }

    public static boolean isEntryPointFile(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String code = stripComments(text);
        if (MAIN_PATTERN.matcher(code).find()) {
            return true;
        }
        return hasTopLevelStatements(code);
    }

    private static boolean hasTopLevelStatements(String code) {
        StringBuilder body = new StringBuilder();
        for (String line : code.split("\\r?\\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("using ") || t.startsWith("global using")
                    || t.startsWith("#") || t.startsWith("//")) {
                continue;
            }
            body.append(t).append('\n');
        }
        String first = body.toString().strip();
        if (first.isEmpty() || first.startsWith("[")) {
            return false;
        }

        for (String decl : new String[]{"namespace", "class", "struct", "interface", "enum",
                "record", "delegate", "public", "internal", "private", "protected",
                "static", "sealed", "abstract", "partial", "unsafe"}) {
            if (first.equals(decl) || first.startsWith(decl + " ") || first.startsWith(decl + "\n")) {
                return false;
            }
        }
        return true;
    }

    private static String stripComments(String text) {

        String noBlock = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("(?m)//.*$", " ");
    }

    public RunProcessHandle errorHandle(String message) {
        return DotnetBuild.errorHandle(message);
    }

    public static boolean isDotnetType(RunConfigurationData data) {
        return data != null && (TYPE_BUILD.equals(data.getType())
                || TYPE_RUN.equals(data.getType())
                || TYPE_TEST.equals(data.getType()));
    }

    public static boolean isRunType(RunConfigurationData data) {
        return data != null && TYPE_RUN.equals(data.getType());
    }

    public Collection<RunConfigurationData> staticRunConfigurations() {
        List<RunConfigurationData> list = new ArrayList<>();
        Path project = projectPath;
        if (project == null) {
            return list;
        }
        list.add(config(TYPE_BUILD, ".NET: Compilar"));

        if (TargetFramework.canRunAnyOnHost(project)) {
            list.add(config(TYPE_RUN, ".NET: Executar"));
            Path projectFile = TargetFramework.findPrimaryProjectFile(project);
            for (LaunchSettings.Profile profile : LaunchSettings.runnableProfiles(projectFile)) {
                list.add(runConfigWithProfile(profile.name()));
            }
        }
        list.add(config(TYPE_TEST, ".NET: Testar"));
        return list;
    }

    private static RunConfigurationData config(String type, String title) {
        return RunConfigurationData.builder()
                .type(type)
                .title(title)
                .build();
    }

    private static RunConfigurationData runConfigWithProfile(String profile) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PROP_LAUNCH_PROFILE, profile);
        return RunConfigurationData.builder()
                .type(TYPE_RUN)
                .title(".NET: Executar — " + profile)
                .properties(properties)
                .build();
    }

    public RunProcessHandle launch(RunConfigurationData data, RunExecutionContext context) {
        Path project = resolveProject(context);
        if (project == null) {
            return DotnetBuild.errorHandle("Projeto inválido: nenhum diretório de projeto disponível.");
        }

        String type = data == null ? TYPE_BUILD : data.getType();
        String configuration = configurationOf(data);

        if (TYPE_CURRENT_FILE.equals(type)) {
            return launchCurrentFile(project, configuration);
        }

        Optional<String> runnableTfm = TargetFramework.selectRunnableTfm(project);
        if (TYPE_RUN.equals(type) && runnableTfm.isEmpty()) {
            return DotnetBuild.errorHandle(runBlockedMessage(project));
        }

        Optional<Path> dotnet = ensureDotnet();
        if (dotnet.isEmpty()) {
            return DotnetBuild.errorHandle("dotnet não encontrado. Instale o .NET SDK ou aguarde o download automático.");
        }

        String launchProfile = launchProfileOf(data);
        Path projectFile = TargetFramework.findPrimaryProjectFile(project);
        return switch (type) {
            case TYPE_RUN -> launchRun(project, dotnet.get(), configuration, projectFile, launchProfile);
            case TYPE_TEST -> build.test(project, dotnet.get(), configuration);
            default -> build.build(project, dotnet.get(), configuration);
        };
    }

    private RunProcessHandle launchRun(Path project, Path dotnet, String configuration,
                                       Path projectFile, String launchProfile) {
        String targetFramework = TargetFramework.selectRunnableTfm(project).orElse(null);
        return build.buildThenRun(project, dotnet, configuration,
                projectFile, targetFramework, launchProfile, outputPanels, runOutputFocus, null);
    }

    private static String launchProfileOf(RunConfigurationData data) {
        if (data == null || data.getProperties() == null) {
            return null;
        }
        Object value = data.getProperties().get(PROP_LAUNCH_PROFILE);
        return value == null ? null : value.toString();
    }

    private RunProcessHandle launchCurrentFile(Path project, String configuration) {
        Path file = activeFileSupplier == null ? null : activeFileSupplier.get();
        if (file == null) {
            return DotnetBuild.errorHandle("Nenhum arquivo C# ativo para executar.");
        }
        String text = activeTextSupplier == null ? null : activeTextSupplier.get();
        if (!isEntryPointFile(text)) {
            return DotnetBuild.errorHandle("O arquivo atual (" + file.getFileName()
                    + ") não é o ponto de entrada do programa: não tem método Main nem top-level statements. "
                    + "Abra o arquivo do Program (com Main/top-level) ou use \".NET: Executar\".");
        }
        Optional<String> runnableTfm = TargetFramework.selectRunnableModernTfm(project);
        if (runnableTfm.isEmpty()) {
            return DotnetBuild.errorHandle(runBlockedMessage(project));
        }
        Optional<Path> dotnet = ensureDotnet();
        if (dotnet.isEmpty()) {
            return DotnetBuild.errorHandle("dotnet não encontrado. Instale o .NET SDK ou aguarde o download automático.");
        }

        return build.buildThenRun(project, dotnet.get(), configuration,
                TargetFramework.findPrimaryProjectFile(project), runnableTfm.orElse(null), null,
                outputPanels, runOutputFocus, null);
    }

    public RunProcessHandle launchDebug(RunConfigurationData data, RunExecutionContext context) {
        Path project = resolveProject(context);
        if (project == null) {
            return DotnetBuild.errorHandle("Projeto inválido: nenhum diretório de projeto disponível.");
        }
        Optional<String> runnableTfm = TargetFramework.selectRunnableModernTfm(project);
        if (runnableTfm.isEmpty()) {
            return DotnetBuild.errorHandle(debugBlockedMessage(project));
        }
        Optional<Path> dotnet = ensureDotnet();
        if (dotnet.isEmpty()) {
            return DotnetBuild.errorHandle("dotnet não encontrado. Instale o .NET SDK ou aguarde o download automático.");
        }
        DotnetSdkService sdk = sdkService;
        if (sdk == null) {
            return DotnetBuild.errorHandle("Serviço de SDK .NET indisponível para depuração.");
        }
        Path netcoredbg;
        try {
            netcoredbg = sdk.getNetcoredbgPath().orElseGet(() -> sdk.ensureNetcoredbg(downloadProgress));
        } catch (Exception e) {
            return DotnetBuild.errorHandle("netcoredbg indisponível: " + e.getMessage());
        }
        if (netcoredbg == null) {
            return DotnetBuild.errorHandle("netcoredbg não encontrado para depuração.");
        }

        String configuration = configurationOf(data);
        Path projectFile = TargetFramework.findPrimaryProjectFile(project);
        List<RunBreakpointData> breakpoints = context == null || context.getBreakpoints() == null
                ? List.of()
                : context.getBreakpoints();

        Path startupHook = sdk.getDebugStartupHook().orElse(null);
        sdk.getNcdbHook();

        LaunchSettings.Profile profile = LaunchSettings.findProfile(projectFile, launchProfileOf(data));
        List<String> programArgs = profile == null ? List.of() : profile.args();
        Map<String, String> launchEnv = new LinkedHashMap<>(profile == null ? Map.of() : profile.effectiveEnv());
        boolean breakOnAllExceptions = breakOnAllExceptionsSupplier != null
                && breakOnAllExceptionsSupplier.getAsBoolean();
        return build.buildThenDebug(project, dotnet.get(), netcoredbg, configuration, projectFile,
                runnableTfm.orElse(null), breakpoints, debugView, outputPanels, runOutputFocus,
                startupHook, this::setDebugSession, programArgs, launchEnv, breakOnAllExceptions);
    }

    public boolean isDebugging() {
        return debugSession.get() != null;
    }


    public boolean applyHotReload(Path activeFile, String activeText) {
        DotnetDapDebugSession session = debugSession.get();
        if (session == null) {
            return false;
        }
        session.applyHotReload(activeFile, activeText, hotReloadResultListener);
        return true;
    }

    public boolean sendDebugCommand(String command) {
        DotnetDapDebugSession session = debugSession.get();
        if (session == null || command == null) {
            return false;
        }
        switch (command) {
            case "continue" -> session.resume();
            case "next" -> session.stepOver();
            case "stepIn" -> session.stepInto();
            case "stepOut" -> session.stepOut();
            case "pause" -> session.pause();
            case "restart" -> session.restart();
            case "hotReload" -> session.applyHotReload(null, null, hotReloadResultListener);
            default -> {
                return false;
            }
        }
        return true;
    }

    public DebugVar evaluateDebug(String expression) {
        DotnetDapDebugSession session = debugSession.get();
        return session == null ? null : session.evaluate(expression);
    }

    public List<DebugScope> debugScopes() {
        DotnetDapDebugSession session = debugSession.get();
        return session == null ? List.of() : session.scopes();
    }

    public List<DebugVar> debugVariables(int variablesReference) {
        DotnetDapDebugSession session = debugSession.get();
        return session == null ? List.of() : session.variables(variablesReference);
    }

    public List<DebugCompletion> debugCompletions(String text, int column) {
        DotnetDapDebugSession session = debugSession.get();
        return session == null ? List.of() : session.completions(text, column);
    }

    public List<DebugFrame> debugCallStack() {
        DotnetDapDebugSession session = debugSession.get();
        return session == null ? List.of() : session.callStack();
    }

    public boolean selectDebugFrame(int frameId) {
        DotnetDapDebugSession session = debugSession.get();
        if (session == null) {
            return false;
        }
        session.selectFrame(frameId);
        return true;
    }

    public void applyBreakpointChange(Path file, int line0Based, boolean added) {
        DotnetDapDebugSession session = debugSession.get();
        if (session != null) {
            session.applyBreakpointChange(file, line0Based, added);
        }
    }

    public void stop(RunConfigurationData data) {
        DotnetDapDebugSession session = debugSession.getAndSet(null);
        if (session != null) {
            session.terminate();
        }
        notifyDebugSessionState(false);
        String type = data == null ? null : data.getType();
        if (TYPE_CURRENT_FILE.equals(type)) {
            type = TYPE_RUN;
        }
        build.stop(type);
    }

    private void setDebugSession(DotnetDapDebugSession session) {
        debugSession.set(session);
        notifyDebugSessionState(session != null);
    }

    private void notifyDebugSessionState(boolean active) {
        Consumer<Boolean> listener = debugSessionStateListener;
        if (listener == null) {
            return;
        }
        try {
            listener.accept(active);
        } catch (Exception e) {
            log.debug("Falha ao atualizar estado da sessao de debug: {}", e.getMessage());
        }
    }

    private Optional<Path> ensureDotnet() {
        DotnetSdkService sdk = sdkService;
        if (sdk == null) {
            return Optional.empty();
        }
        Path project = projectPath;
        Optional<Path> existing = sdk.getDotnetPath(project);
        if (existing.isPresent()) {
            return existing;
        }
        try {
            return Optional.of(sdk.ensureDotnet(project, downloadProgress));
        } catch (Exception e) {
            log.warn("Falha ao provisionar o .NET SDK: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Path resolveProject(RunExecutionContext context) {
        Path fromContext = context == null ? null : context.getProjectPath();
        if (fromContext != null) {
            return fromContext.toAbsolutePath().normalize();
        }
        return projectPath;
    }

    private static String configurationOf(RunConfigurationData data) {
        if (data == null || data.getProperties() == null) {
            return "Debug";
        }
        Object value = data.getProperties().get(PROP_CONFIGURATION);
        return value == null ? "Debug" : value.toString();
    }

    private static String runBlockedMessage(Path project) {
        List<String> tfms = TargetFramework.resolveTfms(project);
        String target = tfms.isEmpty() ? "desconhecido" : String.join(", ", tfms);
        return "Execucao bloqueada para TargetFramework (" + target + "). "
                + "O plugin executa com seguranca apenas TFMs modernos suportados no host "
                + "(netcoreapp* ou net5.0+ sem plataforma incompativel). "
                + ".NET Framework e netstandard ficam em modo build/test.";
    }

    private static String debugBlockedMessage(Path project) {
        List<String> tfms = TargetFramework.resolveTfms(project);
        String target = tfms.isEmpty() ? "desconhecido" : String.join(", ", tfms);
        return "Debug bloqueado para TargetFramework (" + target + "). "
                + "netcoredbg e suportado apenas para .NET moderno executavel no host. "
                + ".NET Framework exige debugger CLR Windows proprio; netstandard e biblioteca e nao executa sozinho.";
    }

    private static String netFrameworkRunBlockedMessage(Path project) {
        List<String> tfms = TargetFramework.resolveTfms(project);
        String target = tfms.isEmpty() ? ".NET Framework" : String.join(", ", tfms);
        return "Execução de .NET Framework (" + target + ") não é suportada neste sistema operacional — "
                + "apenas build. Compile aqui e copie o binário de bin/ para o Windows para executar.";
    }

}
