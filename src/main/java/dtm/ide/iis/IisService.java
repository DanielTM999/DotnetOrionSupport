package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class IisService {

    public record Result(boolean success, String message) {

        public static Result ok() {
            return new Result(true, "");
        }

        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }

        public static Result of(IisProcess.Result result, String failureContext) {
            if (result.ok()) {
                return ok(result.output());
            }
            String detail = result.output() == null || result.output().isBlank()
                    ? "código " + result.exitCode()
                    : result.output().strip();
            return fail(failureContext + ": " + detail);
        }
    }

    private IisService() {
    }

    public record Snapshot(List<IisAppPool> pools,
                           List<IisSite> sites,
                           List<IisApplication> applications,
                           List<IisWorkerProcess> workerProcesses) {

        public static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), List.of(), List.of());
        }
    }

    public static boolean available() {
        return AppCmd.available();
    }

    public static Snapshot snapshot() {
        if (!available()) {
            return Snapshot.empty();
        }
        return AppCmd.snapshot();
    }

    public static List<IisAppPool> appPools() {
        return AppCmd.listAppPools();
    }

    public static List<IisSite> sites() {
        return AppCmd.listSites();
    }

    public static List<IisApplication> applications() {
        return AppCmd.listApplications();
    }

    public static List<IisVirtualDirectory> virtualDirectories() {
        return AppCmd.listVirtualDirectories();
    }

    public static List<IisWorkerProcess> workerProcesses() {
        return AppCmd.listWorkerProcesses();
    }

    private static final long SERVER_TIMEOUT_SECONDS = 180;
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("%([A-Za-z_][A-Za-z0-9_()]*)%");

    public static Result startServer() {
        return serverAction(List.of("/start"), "Falha ao iniciar o IIS");
    }

    public static Result stopServer() {
        return serverAction(List.of("/stop"), "Falha ao parar o IIS");
    }

    public static Result restartServer() {
        return serverAction(List.of("/restart", "/noforce"), "Falha ao reiniciar o IIS");
    }

    private static Result serverAction(List<String> arguments, String failureContext) {
        return Result.of(IisBroker.run(IisBroker.Tool.IIS_RESET, arguments, SERVER_TIMEOUT_SECONDS),
                failureContext);
    }

    public static Result startAppPool(String name) {
        return Result.of(AppCmd.write(List.of("start", "apppool", name)), "Falha ao iniciar o pool " + name);
    }

    public static Result stopAppPool(String name) {
        return Result.of(AppCmd.write(List.of("stop", "apppool", name)), "Falha ao parar o pool " + name);
    }

    public static Result recycleAppPool(String name) {
        return Result.of(AppCmd.write(List.of("recycle", "apppool", name)), "Falha ao reciclar o pool " + name);
    }

    public static Result deleteAppPool(String name) {
        return Result.of(AppCmd.write(List.of("delete", "apppool", name)), "Falha ao remover o pool " + name);
    }

    public static Result addAppPool(String name, String runtimeVersion, String pipelineMode) {
        List<String> arguments = new ArrayList<>(List.of("add", "apppool", "/name:" + name));
        arguments.add("/managedRuntimeVersion:" + (runtimeVersion == null ? "" : runtimeVersion));
        arguments.add("/managedPipelineMode:" + (pipelineMode == null || pipelineMode.isBlank()
                ? "Integrated" : pipelineMode));
        return Result.of(AppCmd.write(arguments), "Falha ao criar o pool " + name);
    }

    public static Result renameAppPool(String name, String newName) {
        return Result.of(AppCmd.write(List.of("set", "apppool", name, "/name:" + newName)),
                "Falha ao renomear o pool " + name);
    }

    public static Result applyAppPoolBasics(String name, String runtimeVersion, String pipelineMode,
                                            boolean autoStart) {
        List<String> arguments = new ArrayList<>(List.of("set", "apppool", name));
        arguments.add("/managedRuntimeVersion:" + (runtimeVersion == null ? "" : runtimeVersion));
        arguments.add("/managedPipelineMode:" + (pipelineMode == null || pipelineMode.isBlank()
                ? "Integrated" : pipelineMode));
        arguments.add("/autoStart:" + autoStart);
        return Result.of(AppCmd.write(arguments), "Falha ao aplicar configurações básicas do pool " + name);
    }

    public static Result applyAppPoolSettings(String name, IisAppPool settings) {
        List<String> arguments = new ArrayList<>(List.of("set", "apppool", name));
        arguments.add("/managedRuntimeVersion:" + (settings.managedRuntimeVersion() == null
                ? "" : settings.managedRuntimeVersion()));
        arguments.add("/managedPipelineMode:" + settings.managedPipelineMode());
        arguments.add("/enable32BitAppOnWin64:" + settings.enable32Bit());
        arguments.add("/autoStart:" + settings.autoStart());
        arguments.add("/startMode:" + settings.startMode());
        arguments.add("/queueLength:" + settings.queueLength());
        arguments.add("/processModel.identityType:" + settings.identityType());
        if ("SpecificUser".equalsIgnoreCase(settings.identityType())
                && settings.userName() != null && !settings.userName().isBlank()) {
            arguments.add("/processModel.userName:" + settings.userName());
        }
        arguments.add("/processModel.idleTimeout:" + AppCmd.toTimeSpan(settings.idleTimeoutMinutes()));
        arguments.add("/processModel.maxProcesses:" + settings.maxProcesses());
        arguments.add("/recycling.periodicRestart.time:" + AppCmd.toTimeSpan(settings.recyclingIntervalMinutes()));
        arguments.add("/recycling.periodicRestart.privateMemory:" + settings.recyclingPrivateMemoryKb());
        arguments.add("/recycling.periodicRestart.memory:" + settings.recyclingVirtualMemoryKb());
        return Result.of(AppCmd.write(arguments), "Falha ao aplicar configurações do pool " + name);
    }

    public static Result startSite(String name) {
        return Result.of(AppCmd.write(List.of("start", "site", name)), "Falha ao iniciar o site " + name);
    }

    public static Result stopSite(String name) {
        return Result.of(AppCmd.write(List.of("stop", "site", name)), "Falha ao parar o site " + name);
    }

    public static Result deleteSite(String name) {
        return Result.of(AppCmd.write(List.of("delete", "site", name)), "Falha ao remover o site " + name);
    }

    public static Result addSite(String name, IisBinding binding, Path physicalPath) {
        List<String> arguments = new ArrayList<>(List.of("add", "site", "/name:" + name));
        arguments.add("/bindings:" + binding.descriptor());
        arguments.add("/physicalPath:" + physicalPath.toAbsolutePath().normalize());
        return Result.of(AppCmd.write(arguments), "Falha ao criar o site " + name);
    }

    public static Result addBinding(String siteName, IisBinding binding) {
        String argument = "/+bindings.[protocol='" + binding.protocol()
                + "',bindingInformation='" + binding.bindingInformation() + "']";
        return Result.of(AppCmd.write(List.of("set", "site", siteName, argument)),
                "Falha ao adicionar binding em " + siteName);
    }

    public static Result removeBinding(String siteName, IisBinding binding) {
        String argument = "/-bindings.[protocol='" + binding.protocol()
                + "',bindingInformation='" + binding.bindingInformation() + "']";
        return Result.of(AppCmd.write(List.of("set", "site", siteName, argument)),
                "Falha ao remover binding em " + siteName);
    }

    public static Result setSitePhysicalPath(String siteName, Path physicalPath) {
        return setVirtualDirectoryPath(siteName + "/", physicalPath);
    }

    public static Result setVirtualDirectoryPath(String vdirName, Path physicalPath) {
        return Result.of(AppCmd.write(List.of("set", "vdir", vdirName,
                        "/physicalPath:" + physicalPath.toAbsolutePath().normalize())),
                "Falha ao definir o caminho físico de " + vdirName);
    }

    public record LogSettings(boolean enabled, String directory, String format, String period) {

        public static LogSettings defaults() {
            return new LogSettings(true, "%SystemDrive%\\inetpub\\logs\\LogFiles", "W3C", "Daily");
        }
    }

    public static LogSettings readLogSettings(String siteName) {
        IisProcess.Result result = AppCmd.read(List.of("list", "site", siteName, "/config"));
        if (!result.ok()) {
            return LogSettings.defaults();
        }
        return AppCmd.parseLogSettings(result.output());
    }

    public static Result applyLogSettings(String siteName, LogSettings settings) {
        List<String> arguments = new ArrayList<>(List.of("set", "site", siteName));
        arguments.add("/logFile.enabled:" + settings.enabled());
        if (settings.directory() != null && !settings.directory().isBlank()) {
            arguments.add("/logFile.directory:" + settings.directory().strip());
        }
        if (settings.format() != null && !settings.format().isBlank()) {
            arguments.add("/logFile.logFormat:" + settings.format().strip());
        }
        if (settings.period() != null && !settings.period().isBlank()) {
            arguments.add("/logFile.period:" + settings.period().strip());
        }
        return Result.of(AppCmd.write(arguments), "Falha ao aplicar configurações de log de " + siteName);
    }

    public static Path resolveLogFolder(IisSite site, LogSettings settings) {
        if (settings == null || settings.directory() == null || settings.directory().isBlank()) {
            return null;
        }
        String expanded = expandEnvironment(settings.directory());
        try {
            Path base = Path.of(expanded);
            String id = site == null ? null : site.id();
            return id == null || id.isBlank() ? base : base.resolve("W3SVC" + id);
        } catch (Exception e) {
            return null;
        }
    }

    public static String expandEnvironment(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        Matcher matcher = ENVIRONMENT_VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = System.getenv(name);
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(replacement == null ? matcher.group(0) : replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public record SiteLimits(String connectionTimeout, String maxBandwidth, String maxConnections) {

        public static SiteLimits defaults() {
            return new SiteLimits("00:02:00", "4294967295", "4294967295");
        }
    }

    public static SiteLimits readLimits(String siteName) {
        IisProcess.Result result = AppCmd.read(List.of("list", "site", siteName, "/config"));
        return result.ok() ? AppCmd.parseLimits(result.output()) : SiteLimits.defaults();
    }

    public static Result applyLimits(String siteName, SiteLimits limits) {
        List<String> arguments = new ArrayList<>(List.of("set", "site", siteName));
        if (limits.connectionTimeout() != null && !limits.connectionTimeout().isBlank()) {
            arguments.add("/limits.connectionTimeout:" + limits.connectionTimeout().strip());
        }
        if (limits.maxBandwidth() != null && !limits.maxBandwidth().isBlank()) {
            arguments.add("/limits.maxBandwidth:" + limits.maxBandwidth().strip());
        }
        if (limits.maxConnections() != null && !limits.maxConnections().isBlank()) {
            arguments.add("/limits.maxConnections:" + limits.maxConnections().strip());
        }
        if (arguments.size() == 3) {
            return Result.ok();
        }
        return Result.of(AppCmd.write(arguments), "Falha ao aplicar limites de " + siteName);
    }

    public static Result addVirtualDirectory(String applicationName, String path, Path physicalPath) {
        List<String> arguments = new ArrayList<>(List.of("add", "vdir",
                "/app.name:" + applicationName,
                "/path:" + normalizePath(path),
                "/physicalPath:" + physicalPath.toAbsolutePath().normalize()));
        return Result.of(AppCmd.write(arguments),
                "Falha ao criar o diretório virtual " + applicationName + normalizePath(path));
    }

    public static Result deleteVirtualDirectory(String vdirName) {
        return Result.of(AppCmd.write(List.of("delete", "vdir", vdirName)),
                "Falha ao remover o diretório virtual " + vdirName);
    }

    public static Result addApplication(String siteName, String path, Path physicalPath, String appPool) {
        List<String> arguments = new ArrayList<>(List.of("add", "app",
                "/site.name:" + siteName,
                "/path:" + normalizePath(path),
                "/physicalPath:" + physicalPath.toAbsolutePath().normalize()));
        IisProcess.Result created = AppCmd.write(arguments);
        if (!created.ok()) {
            return Result.of(created, "Falha ao criar o aplicativo " + siteName + normalizePath(path));
        }
        if (appPool == null || appPool.isBlank()) {
            return Result.ok();
        }
        return setApplicationPool(siteName + normalizePath(path), appPool);
    }

    public static Result setApplicationPool(String appName, String appPool) {
        return Result.of(AppCmd.write(List.of("set", "app", appName, "/applicationPool:" + appPool)),
                "Falha ao definir o pool do aplicativo " + appName);
    }

    public static Result deleteApplication(String appName) {
        return Result.of(AppCmd.write(List.of("delete", "app", appName)),
                "Falha ao remover o aplicativo " + appName);
    }

    public static IisAppPool findAppPool(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (IisAppPool pool : appPools()) {
            if (name.equals(pool.name())) {
                return pool;
            }
        }
        return null;
    }

    public static IisSite findSite(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (IisSite site : sites()) {
            if (name.equals(site.name())) {
                return site;
            }
        }
        return null;
    }

    public static IisApplication findApplication(String siteName, String path) {
        String target = siteName + normalizePath(path);
        for (IisApplication application : applications()) {
            if (target.equalsIgnoreCase(application.name())) {
                return application;
            }
        }
        return null;
    }

    public static String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "/";
        }
        String value = path.strip().replace('\\', '/');
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public static String suggestAppPoolName(String projectName) {
        String base = projectName == null || projectName.isBlank() ? "OrionApp" : projectName.strip();
        return base.replaceAll("[^A-Za-z0-9._-]", "_") + "AppPool";
    }

    public static String suggestSiteName(String projectName) {
        String base = projectName == null || projectName.isBlank() ? "OrionSite" : projectName.strip();
        return base.replaceAll("[^A-Za-z0-9._ -]", "_");
    }

    public static boolean isNoManagedCodePool(IisAppPool pool) {
        return pool != null && pool.noManagedCode();
    }

    public static String describeState(String state) {
        if (state == null || state.isBlank()) {
            return "Desconhecido";
        }
        return state.substring(0, 1).toUpperCase(Locale.ROOT) + state.substring(1);
    }
}
