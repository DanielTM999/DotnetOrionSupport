package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class IisEnvironment {

    public record Info(boolean windows,
                       Path appCmd,
                       Path applicationHostConfig,
                       Path iisExpress,
                       boolean aspNetCoreModule) {

        public boolean iisInstalled() {
            return appCmd != null || applicationHostConfig != null;
        }

        public boolean manageable() {
            return appCmd != null;
        }

        public boolean iisExpressInstalled() {
            return iisExpress != null;
        }

        public boolean anyHostAvailable() {
            return iisInstalled() || iisExpressInstalled();
        }

        public boolean elevated() {
            return isElevated();
        }

        public String describe() {
            return "windows=" + windows
                    + ", appcmd=" + (appCmd == null ? "não encontrado" : appCmd)
                    + ", applicationHost.config=" + (applicationHostConfig == null ? "não encontrado" : applicationHostConfig)
                    + ", iisExpress=" + (iisExpress == null ? "não encontrado" : iisExpress)
                    + ", aspNetCoreModule=" + aspNetCoreModule;
        }
    }

    private static final Pattern REG_VALUE = Pattern.compile("REG_[A-Z_]+\\s+(.+)$");
    private static final Pattern SERVICE_STATE = Pattern.compile(
            ":\\s*\\d+\\s+(RUNNING|STOPPED|START_PENDING|STOP_PENDING|CONTINUE_PENDING|PAUSE_PENDING|PAUSED)");
    private static final long DETECT_TIMEOUT_SECONDS = 15;

    private static volatile Info cached;
    private static volatile Boolean elevatedCache;

    private IisEnvironment() {
    }

    public static Info current() {
        Info info = cached;
        if (info == null) {
            synchronized (IisEnvironment.class) {
                info = cached;
                if (info == null) {
                    info = detect();
                    cached = info;
                }
            }
        }
        return info;
    }

    public static Info refresh() {
        Info info = detect();
        cached = info;
        elevatedCache = null;
        return info;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Info detect() {
        if (!isWindows()) {
            log.debug("Detecção de IIS ignorada: sistema não é Windows.");
            return new Info(false, null, null, null, false);
        }
        Info info = new Info(true,
                locateAppCmd(),
                locateApplicationHostConfig(),
                locateIisExpress(),
                hasAspNetCoreModule());
        log.info("Detecção de IIS: {}", info.describe());
        if (info.iisInstalled() && !info.manageable()) {
            log.warn("IIS detectado, mas appcmd.exe não foi encontrado. Habilite o recurso "
                    + "\"Ferramentas e scripts de gerenciamento do IIS\" nos Recursos do Windows.");
        }
        return info;
    }

    public static boolean isElevated() {
        Boolean value = elevatedCache;
        if (value == null) {
            synchronized (IisEnvironment.class) {
                value = elevatedCache;
                if (value == null) {
                    value = detectElevated();
                    elevatedCache = value;
                }
            }
        }
        return value;
    }

    public static String version() {
        return readRegistryValue("HKLM\\SOFTWARE\\Microsoft\\InetStp", "VersionString");
    }

    public static String iisExpressVersion() {
        return readRegistryValue("HKLM\\SOFTWARE\\Microsoft\\IISExpress", "VersionString");
    }

    public static String serviceState() {
        return detectServiceState();
    }

    public static boolean serviceRunning() {
        return "RUNNING".equalsIgnoreCase(detectServiceState());
    }

    private static Path locateAppCmd() {
        for (Path candidate : systemDirectories()) {
            Path appCmd = candidate.resolve("inetsrv").resolve("appcmd.exe");
            if (Files.isRegularFile(appCmd)) {
                return appCmd;
            }
        }
        return searchPath("appcmd.exe");
    }

    private static Path searchPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(entry.trim()).resolve(executable);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            } catch (Exception e) {
                log.debug("Entrada inválida no PATH: {}", entry);
            }
        }
        return null;
    }

    private static Path locateApplicationHostConfig() {
        for (Path candidate : systemDirectories()) {
            Path config = candidate.resolve("inetsrv").resolve("config").resolve("applicationHost.config");
            if (Files.isRegularFile(config)) {
                return config;
            }
        }
        return null;
    }

    private static boolean hasAspNetCoreModule() {
        for (Path candidate : systemDirectories()) {
            if (Files.isRegularFile(candidate.resolve("inetsrv").resolve("aspnetcorev2.dll"))) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> systemDirectories() {
        List<Path> directories = new ArrayList<>();
        String windir = System.getenv("windir");
        if (windir == null || windir.isBlank()) {
            windir = System.getenv("SystemRoot");
        }
        if (windir == null || windir.isBlank()) {
            return directories;
        }
        Path root = Path.of(windir);
        directories.add(root.resolve("system32"));
        directories.add(root.resolve("sysnative"));
        return directories;
    }

    private static Path locateIisExpress() {
        List<String> roots = new ArrayList<>();
        roots.add(System.getenv("ProgramFiles"));
        roots.add(System.getenv("ProgramFiles(x86)"));
        roots.add(System.getenv("ProgramW6432"));
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            Path candidate = Path.of(root).resolve("IIS Express").resolve("iisexpress.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        String installPath = readRegistryValue("HKLM\\SOFTWARE\\Microsoft\\IISExpress", "InstallPath");
        if (installPath != null && !installPath.isBlank()) {
            Path candidate = Path.of(installPath.trim()).resolve("iisexpress.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean detectElevated() {
        if (!isWindows()) {
            return false;
        }
        IisProcess.Result result = IisProcess.capture(List.of("whoami", "/groups"), DETECT_TIMEOUT_SECONDS);
        if (!result.ok()) {
            return false;
        }
        return result.output().contains("S-1-16-12288") || result.output().contains("S-1-16-16384");
    }

    private static String detectServiceState() {
        IisProcess.Result service = IisProcess.capture(
                List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "$s = Get-Service -Name W3SVC -ErrorAction SilentlyContinue;"
                                + " if ($s) { $s.Status.ToString() } else { '' }"),
                DETECT_TIMEOUT_SECONDS);
        if (service.ok()) {
            String value = service.output().strip();
            if (!value.isEmpty()) {
                return value.toUpperCase(Locale.ROOT);
            }
        }
        IisProcess.Result result = IisProcess.capture(List.of("sc", "query", "W3SVC"), DETECT_TIMEOUT_SECONDS);
        if (!result.ok()) {
            return null;
        }
        Matcher matcher = SERVICE_STATE.matcher(result.output());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String readRegistryValue(String key, String name) {
        IisProcess.Result result = IisProcess.capture(
                List.of("reg", "query", key, "/v", name), DETECT_TIMEOUT_SECONDS);
        if (!result.ok()) {
            return null;
        }
        for (String line : result.output().split("\\R")) {
            if (!line.contains(name)) {
                continue;
            }
            Matcher matcher = REG_VALUE.matcher(line.strip());
            if (matcher.find()) {
                return matcher.group(1).strip();
            }
        }
        return null;
    }
}
