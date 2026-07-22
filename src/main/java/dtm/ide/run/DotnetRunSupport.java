package dtm.ide.run;

import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.iis.IisBinding;
import dtm.ide.iis.IisEnvironment;
import dtm.ide.iis.IisLaunchSettings;
import dtm.ide.iis.IisService;
import dtm.ide.iis.IisWebProject;
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
    public static final String TYPE_IIS_EXPRESS = "dotnet-iisexpress";
    public static final String TYPE_IIS = "dotnet-iis";

    public static final String TYPE_CURRENT_FILE = "current_file";

    public static final String PROP_CONFIGURATION = "configuration";
    public static final String PROP_LAUNCH_PROFILE = "launchProfile";
    public static final String PROP_PROJECT = "projectFile";
    public static final String PROP_TARGET_FRAMEWORK = "targetFramework";
    public static final String PROP_PROGRAM_ARGS = "programArgs";
    public static final String PROP_WORKING_DIRECTORY = "workingDirectory";
    public static final String PROP_ENVIRONMENT = "environment";
    public static final String PROP_IIS_SITE = "iisSiteName";
    public static final String PROP_IIS_APP_PATH = "iisApplicationPath";
    public static final String PROP_IIS_APP_POOL = "iisApplicationPool";
    public static final String PROP_IIS_LAUNCH_BROWSER = "iisLaunchBrowser";
    public static final String PROP_IIS_LAUNCH_URL = "iisLaunchUrl";

    private static final Pattern MAIN_PATTERN = Pattern.compile("\\bstatic\\s+(?:async\\s+)?[\\w<>\\[\\].,\\s]*?\\bMain\\s*\\(");

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
    private volatile Function<List<Path>, Path> runnableProjectChooser;
    private volatile BooleanSupplier iisAutoCreateSiteSupplier;
    private volatile BooleanSupplier iisStopPoolOnExitSupplier;
    private volatile BooleanSupplier iisLaunchBrowserSupplier;

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

    public void bindRunnableProjectChooser(Function<List<Path>, Path> chooser) {
        this.runnableProjectChooser = chooser;
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
                || TYPE_TEST.equals(data.getType())
                || isIisType(data));
    }

    public static boolean isRunType(RunConfigurationData data) {
        return data != null && TYPE_RUN.equals(data.getType());
    }

    public static boolean isIisType(RunConfigurationData data) {
        return data != null && (TYPE_IIS_EXPRESS.equals(data.getType()) || TYPE_IIS.equals(data.getType()));
    }

    public static boolean isIisExpressType(RunConfigurationData data) {
        return data != null && TYPE_IIS_EXPRESS.equals(data.getType());
    }

    public static boolean supportsDebug(RunConfigurationData data, Path projectRoot) {
        if (isIisType(data)) {
            Path webProject = resolveWebProjectFile(data, projectRoot);
            return webProject != null && !IisWebProject.isClassicAspNet(webProject);
        }
        return isRunType(data) && projectRoot != null && TargetFramework.canRunOnHost(projectRoot);
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
            List<Path> runnable = TargetFramework.findRunnableProjectFiles(project);
            if (runnable.size() > 1) {
                for (Path projectFile : runnable) {
                    list.add(runConfigForProject(projectFile));
                }
            } else {
                Path projectFile = runnable.size() == 1
                        ? runnable.get(0)
                        : TargetFramework.findPrimaryProjectFile(project);
                for (LaunchSettings.Profile profile : LaunchSettings.runnableProfiles(projectFile)) {
                    list.add(runConfigWithProfile(profile.name()));
                }
            }
        }
        list.add(config(TYPE_TEST, ".NET: Testar"));
        addIisConfigurations(list, project);
        return list;
    }

    private static void addIisConfigurations(List<RunConfigurationData> list, Path project) {
        if (!IisEnvironment.isWindows()) {
            return;
        }
        IisEnvironment.Info info = IisEnvironment.current();
        if (!info.anyHostAvailable()) {
            return;
        }
        List<Path> webProjects = new ArrayList<>();
        for (Path projectFile : TargetFramework.findProjectFiles(project)) {
            if (IisWebProject.isWebProject(projectFile)) {
                webProjects.add(projectFile);
            }
        }
        for (Path projectFile : webProjects) {
            String suffix = webProjects.size() > 1 ? " — " + projectDisplayName(projectFile) : "";
            List<LaunchSettings.Profile> profiles = LaunchSettings.iisProfiles(projectFile);
            boolean addedExpress = false;
            boolean addedIis = false;
            for (LaunchSettings.Profile profile : profiles) {
                if (profile.isIisExpressCommand() && info.iisExpressInstalled()) {
                    list.add(iisConfig(TYPE_IIS_EXPRESS, profile.name() + suffix, projectFile, profile.name()));
                    addedExpress = true;
                } else if (profile.isIisCommand() && info.manageable()) {
                    list.add(iisConfig(TYPE_IIS, profile.name() + suffix, projectFile, profile.name()));
                    addedIis = true;
                }
            }
            if (!addedExpress && info.iisExpressInstalled()) {
                list.add(iisConfig(TYPE_IIS_EXPRESS, "IIS Express — " + projectDisplayName(projectFile),
                        projectFile, null));
            }
            if (!addedIis && info.manageable()) {
                list.add(iisConfig(TYPE_IIS, "IIS — " + projectDisplayName(projectFile), projectFile, null));
            }
        }
    }

    private static RunConfigurationData iisConfig(String type, String title, Path projectFile, String profile) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PROP_PROJECT, projectFile.toString());
        if (profile != null && !profile.isBlank()) {
            properties.put(PROP_LAUNCH_PROFILE, profile);
        }
        return RunConfigurationData.builder()
                .type(type)
                .title(title)
                .properties(properties)
                .build();
    }

    private static RunConfigurationData runConfigForProject(Path projectFile) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PROP_PROJECT, projectFile.toString());
        return RunConfigurationData.builder()
                .type(TYPE_RUN)
                .title(".NET: Executar — " + projectDisplayName(projectFile))
                .properties(properties)
                .build();
    }

    private static String projectDisplayName(Path projectFile) {
        if (projectFile == null || projectFile.getFileName() == null) {
            return "projeto";
        }
        return projectFile.getFileName().toString().replaceFirst("(?i)\\.(csproj|vbproj|fsproj)$", "");
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
                .title(profile)
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

        Optional<Path> dotnet = ensureDotnet();
        if (dotnet.isEmpty()) {
            return DotnetBuild.errorHandle("dotnet não encontrado. Instale o .NET SDK ou aguarde o download automático.");
        }

        String launchProfile = launchProfileOf(data);
        String requestedTfm = targetFrameworkOf(data);
        if (TYPE_IIS_EXPRESS.equals(type) || TYPE_IIS.equals(type)) {
            return launchIis(data, project, false, List.of());
        }
        return switch (type) {
            case TYPE_RUN -> {
                Path projectFile = resolveTargetProjectFile(data, project);
                if (projectFile == null) {
                    yield DotnetBuild.errorHandle(runBlockedMessage(project));
                }
                Path runDir = parentOr(projectFile, project);
                Optional<String> runnableTfm = runnableTfmOf(projectFile, requestedTfm);
                if (runnableTfm.isEmpty()) {
                    yield DotnetBuild.errorHandle(runBlockedMessage(runDir));
                }
                yield launchRun(runDir, dotnet.get(), configuration, projectFile, launchProfile, runnableTfm.orElse(null), data);
            }
            case TYPE_TEST -> {
                Path projectFile = projectFileOf(data);
                Path runDir = parentOr(projectFile, project);
                yield build.test(runDir, dotnet.get(), configuration, projectFile, requestedTfm);
            }
            default -> {
                Path projectFile = projectFileOf(data);
                Path runDir = parentOr(projectFile, project);
                yield build.build(runDir, dotnet.get(), configuration, projectFile, requestedTfm);
            }
        };
    }

    private RunProcessHandle launchRun(Path project, Path dotnet, String configuration, Path projectFile, String launchProfile, String targetFramework, RunConfigurationData data) {
        LaunchSettings.Profile profile = LaunchSettings.findProfile(projectFile, launchProfile);
        Path workingDirectory = workingDirectoryOf(data, projectFile, profile == null ? null : profile.workingDirectory());
        return build.buildThenRun(project, dotnet, configuration, projectFile, targetFramework, launchProfile, outputPanels, runOutputFocus, programArgsOf(data), environmentOf(data), workingDirectory);
    }

    private Path resolveTargetProjectFile(RunConfigurationData data, Path projectDir) {
        if (isCurrentFileType(data)) {
            Path file = activeFileSupplier == null ? null : activeFileSupplier.get();
            Path owner = TargetFramework.findProjectFileForSource(file, projectDir);
            if (owner != null) {
                return owner;
            }
        }
        Path explicit = projectFileOf(data);
        if (explicit != null && java.nio.file.Files.isRegularFile(explicit)) {
            return explicit;
        }
        List<Path> runnable = TargetFramework.findRunnableProjectFiles(projectDir);
        if (runnable.size() == 1) {
            return runnable.getFirst();
        }
        if (runnable.size() > 1) {
            Function<List<Path>, Path> chooser = runnableProjectChooser;
            Path chosen = chooser == null ? null : chooser.apply(runnable);
            return chosen != null ? chosen : runnable.getFirst();
        }
        return TargetFramework.findPrimaryProjectFile(projectDir);
    }

    private static Path parentOr(Path projectFile, Path fallback) {
        if (projectFile != null && projectFile.getParent() != null) {
            return projectFile.getParent();
        }
        return fallback;
    }

    private static Optional<String> runnableTfmOf(Path projectFile) {
        return TargetFramework.selectRunnableTfm(TargetFramework.readTfms(projectFile), TargetFramework.isWindows());
    }

    private static Optional<String> runnableTfmOf(Path projectFile, String requestedTfm) {
        if (requestedTfm == null || requestedTfm.isBlank()) {
            return runnableTfmOf(projectFile);
        }
        for (String tfm : TargetFramework.readTfms(projectFile)) {
            if (tfm.equalsIgnoreCase(requestedTfm.trim())
                    && TargetFramework.selectRunnableTfm(List.of(tfm), TargetFramework.isWindows()).isPresent()) {
                return Optional.of(tfm);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> runnableModernTfmOf(Path projectFile) {
        return TargetFramework.selectRunnableModernTfm(TargetFramework.readTfms(projectFile), TargetFramework.isWindows());
    }

    private static Optional<String> runnableModernTfmOf(Path projectFile, String requestedTfm) {
        if (requestedTfm == null || requestedTfm.isBlank()) {
            return runnableModernTfmOf(projectFile);
        }
        for (String tfm : TargetFramework.readTfms(projectFile)) {
            if (tfm.equalsIgnoreCase(requestedTfm.trim())
                    && TargetFramework.isRunnableModernOnHost(tfm)) {
                return Optional.of(tfm);
            }
        }
        return Optional.empty();
    }

    private static Path projectFileOf(RunConfigurationData data) {
        if (data == null || data.getProperties() == null) {
            return null;
        }
        Object value = data.getProperties().get(PROP_PROJECT);
        if (value == null) {
            return null;
        }
        try {
            return Path.of(value.toString()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String launchProfileOf(RunConfigurationData data) {
        if (data == null || data.getProperties() == null) {
            return null;
        }
        Object value = data.getProperties().get(PROP_LAUNCH_PROFILE);
        return value == null ? null : value.toString();
    }

    private static String targetFrameworkOf(RunConfigurationData data) {
        if (data == null || data.getProperties() == null) {
            return null;
        }
        Object value = data.getProperties().get(PROP_TARGET_FRAMEWORK);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static List<String> programArgsOf(RunConfigurationData data) {
        String value = propertyText(data, PROP_PROGRAM_ARGS);
        return LaunchSettings.splitArgs(value);
    }

    private static Map<String, String> environmentOf(RunConfigurationData data) {
        String value = propertyText(data, PROP_ENVIRONMENT);
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, String> environment = new LinkedHashMap<>();
        for (String line : value.split("\\R")) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator > 0) {
                environment.put(entry.substring(0, separator).trim(), entry.substring(separator + 1));
            }
        }
        return environment;
    }

    private static Path workingDirectoryOf(RunConfigurationData data, Path projectFile, String profileDirectory) {
        String configured = propertyText(data, PROP_WORKING_DIRECTORY);
        String value = configured == null || configured.isBlank() ? profileDirectory : configured;
        Path projectDirectory = parentOr(projectFile, null);
        if (value == null || value.isBlank()) {
            return projectDirectory;
        }
        try {
            Path path = Path.of(value.trim());
            if (!path.isAbsolute() && projectDirectory == null) {
                return null;
            }
            return (path.isAbsolute() ? path : projectDirectory.resolve(path)).toAbsolutePath().normalize();
        } catch (Exception e) {
            return projectDirectory;
        }
    }

    private static String propertyText(RunConfigurationData data, String key) {
        if (data == null || data.getProperties() == null) {
            return null;
        }
        Object value = data.getProperties().get(key);
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
        Path projectFile = TargetFramework.findProjectFileForSource(file, project);
        if (projectFile == null) {
            projectFile = TargetFramework.findPrimaryProjectFile(project);
        }
        Path runDir = parentOr(projectFile, project);
        Optional<String> runnableTfm = runnableModernTfmOf(projectFile);
        if (runnableTfm.isEmpty()) {
            return DotnetBuild.errorHandle(runBlockedMessage(runDir));
        }
        Optional<Path> dotnet = ensureDotnet();
        if (dotnet.isEmpty()) {
            return DotnetBuild.errorHandle("dotnet não encontrado. Instale o .NET SDK ou aguarde o download automático.");
        }

        return build.buildThenRun(runDir, dotnet.get(), configuration,
                projectFile, runnableTfm.orElse(null), null,
                outputPanels, runOutputFocus, List.of(), Map.of(), runDir);
    }

    public RunProcessHandle launchDebug(RunConfigurationData data, RunExecutionContext context) {
        Path project = resolveProject(context);
        if (project == null) {
            return DotnetBuild.errorHandle("Projeto inválido: nenhum diretório de projeto disponível.");
        }
        if (isIisType(data)) {
            List<RunBreakpointData> iisBreakpoints = context == null || context.getBreakpoints() == null
                    ? List.of()
                    : context.getBreakpoints();
            return launchIis(data, project, true, iisBreakpoints);
        }
        Path projectFile = resolveTargetProjectFile(data, project);
        if (projectFile == null) {
            return DotnetBuild.errorHandle(debugBlockedMessage(project));
        }
        Path runDir = parentOr(projectFile, project);
        Optional<String> runnableTfm = runnableModernTfmOf(projectFile, targetFrameworkOf(data));
        if (runnableTfm.isEmpty()) {
            return DotnetBuild.errorHandle(debugBlockedMessage(runDir));
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
        List<RunBreakpointData> breakpoints = context == null || context.getBreakpoints() == null
                ? List.of()
                : context.getBreakpoints();

        Path startupHook = sdk.getDebugStartupHook().orElse(null);
        sdk.getNcdbHook();

        LaunchSettings.Profile profile = LaunchSettings.findProfile(projectFile, launchProfileOf(data));
        List<String> customArgs = programArgsOf(data);
        List<String> programArgs = customArgs.isEmpty() && profile != null ? profile.args() : customArgs;
        Map<String, String> launchEnv = new LinkedHashMap<>(profile == null ? Map.of() : profile.effectiveEnv());
        launchEnv.putAll(environmentOf(data));
        Path workingDirectory = workingDirectoryOf(data, projectFile,
                profile == null ? null : profile.workingDirectory());
        boolean breakOnAllExceptions = breakOnAllExceptionsSupplier != null
                && breakOnAllExceptionsSupplier.getAsBoolean();
        return build.buildThenDebug(workingDirectory == null ? runDir : workingDirectory,
                dotnet.get(), netcoredbg, configuration, projectFile,
                runnableTfm.orElse(null), breakpoints, debugView, outputPanels, runOutputFocus,
                startupHook, this::setDebugSession, programArgs, launchEnv, breakOnAllExceptions);
    }

    private RunProcessHandle launchIis(RunConfigurationData data, Path project, boolean debug,
                                       List<RunBreakpointData> breakpoints) {
        boolean iisExpress = TYPE_IIS_EXPRESS.equals(data == null ? null : data.getType());
        IisEnvironment.Info info = IisEnvironment.current();
        if (iisExpress && !info.iisExpressInstalled()) {
            return DotnetBuild.errorHandle("IIS Express não está instalado nesta máquina. "
                    + "Instale o IIS Express (ou o ASP.NET Core Hosting Bundle) para usar este modo.");
        }
        if (!iisExpress && !info.iisInstalled()) {
            return DotnetBuild.errorHandle("O IIS não está instalado nesta máquina. "
                    + "Habilite \"Serviços de Informações da Internet\" nos Recursos do Windows.");
        }
        if (!iisExpress && !info.manageable()) {
            return DotnetBuild.errorHandle("O IIS está instalado, mas o appcmd.exe não foi encontrado. "
                    + "Habilite \"Ferramentas e scripts de gerenciamento do IIS\" nos Recursos do Windows.");
        }

        Path projectFile = resolveWebProjectFile(data, project);
        if (projectFile == null) {
            return DotnetBuild.errorHandle("Nenhum projeto web (ASP.NET / ASP.NET Core) foi encontrado para hospedar no IIS.");
        }
        if (debug && IisWebProject.isClassicAspNet(projectFile)) {
            return DotnetBuild.errorHandle("Depuração no IIS não é suportada para projetos .NET Framework: "
                    + "o netcoredbg depura apenas CoreCLR. Use execução sem depuração para este projeto.");
        }
        if (IisWebProject.isAspNetCore(projectFile) && !iisExpress && info.aspNetCoreModuleMissing()) {
            return DotnetBuild.errorHandle("O ASP.NET Core Module não está registrado no IIS. "
                    + "Instale o ASP.NET Core Hosting Bundle para hospedar projetos ASP.NET Core.");
        }

        Optional<Path> dotnet = ensureDotnet();
        if (dotnet.isEmpty()) {
            return DotnetBuild.errorHandle("dotnet não encontrado. Instale o .NET SDK ou aguarde o download automático.");
        }
        Path netcoredbg = null;
        if (debug) {
            DotnetSdkService sdk = sdkService;
            if (sdk == null) {
                return DotnetBuild.errorHandle("Serviço de SDK .NET indisponível para depuração.");
            }
            try {
                netcoredbg = sdk.getNetcoredbgPath().orElseGet(() -> sdk.ensureNetcoredbg(downloadProgress));
            } catch (Exception e) {
                return DotnetBuild.errorHandle("netcoredbg indisponível: " + e.getMessage());
            }
            if (netcoredbg == null) {
                return DotnetBuild.errorHandle("netcoredbg não encontrado para depuração.");
            }
            if (!iisExpress && !IisEnvironment.isElevated()) {
                log.warn("Depuração no IIS sem privilégios elevados pode falhar ao anexar ao w3wp.");
            }
        }

        String projectName = IisWebProject.projectName(projectFile);
        IisLaunchSettings.Settings settings = IisLaunchSettings.read(projectFile);
        LaunchSettings.Profile profile = LaunchSettings.findProfile(projectFile, launchProfileOf(data));

        List<IisBinding> bindings;
        String applicationPath;
        String siteName = propertyText(data, PROP_IIS_SITE);
        if (iisExpress) {
            bindings = IisLaunchSettings.bindingsOf(settings, projectName);
            applicationPath = "/";
            if (siteName == null || siteName.isBlank()) {
                siteName = IisService.suggestSiteName(projectName);
            }
        } else {
            IisLaunchSettings.IisTarget target = IisLaunchSettings.iisTarget(settings, projectName);
            bindings = List.of(target.binding());
            String configuredPath = propertyText(data, PROP_IIS_APP_PATH);
            applicationPath = configuredPath == null || configuredPath.isBlank()
                    ? target.applicationPath() : configuredPath;
            if (siteName == null || siteName.isBlank()) {
                siteName = "Default Web Site";
            }
        }
        String appPool = propertyText(data, PROP_IIS_APP_POOL);
        boolean dedicatedPool = false;
        if (appPool == null || appPool.isBlank()) {
            appPool = iisExpress ? null : IisService.existingAppPool(siteName, applicationPath);
        }
        if (appPool == null || appPool.isBlank()) {
            appPool = IisService.suggestAppPoolName(projectName);
            dedicatedPool = true;
        }

        Map<String, String> environment = new LinkedHashMap<>(profile == null ? Map.of() : profile.env());
        environment.putAll(environmentOf(data));
        environment.putIfAbsent("ASPNETCORE_ENVIRONMENT", "Development");

        String launchUrl = propertyText(data, PROP_IIS_LAUNCH_URL);
        if ((launchUrl == null || launchUrl.isBlank()) && profile != null) {
            launchUrl = profile.launchUrl();
        }
        String launchBrowserText = propertyText(data, PROP_IIS_LAUNCH_BROWSER);
        boolean launchBrowser = launchBrowserText == null || launchBrowserText.isBlank()
                ? (profile != null ? profile.launchBrowser() : resolveFlag(iisLaunchBrowserSupplier, true))
                : Boolean.parseBoolean(launchBrowserText);

        IisLaunchRequest request = IisLaunchRequest.builder()
                .projectFile(projectFile)
                .workspaceRoot(projectPath)
                .dotnet(dotnet.get())
                .netcoredbg(netcoredbg)
                .configuration(configurationOf(data))
                .targetFramework(targetFrameworkOf(data))
                .iisExpress(iisExpress)
                .debug(debug)
                .siteName(siteName)
                .applicationPath(applicationPath)
                .appPoolName(appPool)
                .dedicatedAppPool(dedicatedPool)
                .bindings(bindings)
                .launchUrl(launchUrl)
                .launchBrowser(launchBrowser)
                .environment(environment)
                .breakpoints(breakpoints)
                .debugView(debug ? debugView : null)
                .sessionSink(debug ? this::setDebugSession : null)
                .breakOnAllExceptions(breakOnAllExceptionsSupplier != null
                        && breakOnAllExceptionsSupplier.getAsBoolean())
                .stopPoolOnExit(resolveFlag(iisStopPoolOnExitSupplier, false))
                .autoCreateSite(resolveFlag(iisAutoCreateSiteSupplier, true))
                .build();
        return build.launchIis(request, outputPanels, runOutputFocus);
    }

    private static Path resolveWebProjectFile(RunConfigurationData data, Path projectDir) {
        Path explicit = projectFileOf(data);
        if (explicit != null && java.nio.file.Files.isRegularFile(explicit)) {
            return explicit;
        }
        for (Path candidate : TargetFramework.findProjectFiles(projectDir)) {
            if (IisWebProject.isWebProject(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public void bindIisAutoCreateSite(BooleanSupplier supplier) {
        this.iisAutoCreateSiteSupplier = supplier;
    }

    public void bindIisStopPoolOnExit(BooleanSupplier supplier) {
        this.iisStopPoolOnExitSupplier = supplier;
    }

    public void bindIisLaunchBrowser(BooleanSupplier supplier) {
        this.iisLaunchBrowserSupplier = supplier;
    }

    private static boolean resolveFlag(BooleanSupplier supplier, boolean fallback) {
        return supplier == null ? fallback : supplier.getAsBoolean();
    }

    public boolean isDebugging() {
        return debugSession.get() != null;
    }

    public RunProcessHandle attachToProcess(long pid) {
        return attachToProcess(pid, List.of());
    }

    public RunProcessHandle attachToProcess(long pid, List<RunBreakpointData> breakpoints) {
        Path project = projectPath;
        if (project == null || pid <= 0 || ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) {
            return DotnetBuild.errorHandle("Processo inválido ou encerrado.");
        }
        Optional<Path> dotnet = ensureDotnet();
        if (dotnet.isEmpty() || sdkService == null) {
            return DotnetBuild.errorHandle("Toolchain .NET indisponível para attach.");
        }
        Path netcoredbg;
        try {
            netcoredbg = sdkService.getNetcoredbgPath()
                    .orElseGet(() -> sdkService.ensureNetcoredbg(downloadProgress));
        } catch (Exception e) {
            return DotnetBuild.errorHandle("netcoredbg indisponível: " + e.getMessage());
        }
        boolean breakOnAllExceptions = breakOnAllExceptionsSupplier != null
                && breakOnAllExceptionsSupplier.getAsBoolean();
        return build.attachDebugger(project, dotnet.get(), netcoredbg, pid,
                breakpoints == null ? List.of() : breakpoints, debugView,
                this::setDebugSession, breakOnAllExceptions);
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

    public boolean setNextStatement(Path file, int line0Based) {
        DotnetDapDebugSession session = debugSession.get();
        return session != null && session.setNextStatement(file, line0Based);
    }

    public boolean runToCursor(Path file, int line0Based) {
        DotnetDapDebugSession session = debugSession.get();
        return session != null && session.runToCursor(file, line0Based);
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

    public void applyBreakpointChange(Path file, int line0Based, boolean added, String condition) {
        DotnetDapDebugSession session = debugSession.get();
        if (session != null) {
            session.applyBreakpointChange(file, line0Based, added, condition);
        }
    }

    public void applyBreakpointCondition(Path file, int line0Based, String condition) {
        DotnetDapDebugSession session = debugSession.get();
        if (session != null) {
            session.applyBreakpointCondition(file, line0Based, condition);
        }
    }

    public void stop(RunConfigurationData data) {
        DotnetDapDebugSession session = debugSession.getAndSet(null);
        if (session != null) {
            session.terminate();
        }
        notifyDebugSessionState(false);
        String type = data == null ? null : data.getType();
        if (TYPE_CURRENT_FILE.equals(type) || TYPE_IIS_EXPRESS.equals(type) || TYPE_IIS.equals(type)) {
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
