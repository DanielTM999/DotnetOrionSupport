package dtm.ide.sdk;

import dtm.ide.api.exceptions.DisplayException;
import dtm.ide.api.extension.Resource;
import dtm.ide.run.TargetFramework;
import dtm.request_actions.http.download.core.DownloadObserver;
import dtm.request_actions.http.download.core.client.DownloadObserverStreamClient;
import dtm.request_actions.http.download.core.config.ObserverConfiguration;
import dtm.stools.component.popup.ModernDialog;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

@Slf4j
public class DotnetSdkService {

    private static String text(String key, String def) {
        return dtm.stools.i18n.I18n.getText(DotnetSdkService.class, key, def);
    }

    public static final String DOTNET_PROGRESS_ID = "downloadDotnetSdk";
    public static final String OMNISHARP_PROGRESS_ID = "downloadOmniSharp";
    public static final String ROSLYN_LS_PROGRESS_ID = "downloadRoslynLs";
    public static final String NETCOREDBG_PROGRESS_ID = "downloadNetcoredbg";

    public static final String DEFAULT_DOTNET_SDK_VERSION = "8.0.422";
    public static final String DOTNET_10_SDK_VERSION = "10.0.301";
    public static final String DOTNET_9_SDK_VERSION = "9.0.315";
    public static final String DOTNET_7_SDK_VERSION = "7.0.410";
    public static final String DOTNET_6_SDK_VERSION = "6.0.428";
    public static final String DEFAULT_OMNISHARP_VERSION = "1.39.15";
    public static final String DEFAULT_ROSLYN_LS_VERSION = "5.0.0-1.25277.114";
    public static final String DEFAULT_ROSLYN_RAZOR_VERSION = "10.0.0-preview.25277.114";
    public static final String ROSLYN_RUNTIME_SDK_VERSION = DOTNET_10_SDK_VERSION;
    public static final String DEFAULT_NETCOREDBG_VERSION = "3.1.3-1062-orion-hotreload.3";

    private static final int DOWNLOAD_MAX_ATTEMPTS = 3;
    private static final long DOWNLOAD_RETRY_BASE_DELAY_MS = 1500;

    private static final String SDK_DIR = "sdk";
    private static final String DOTNET_DIR = "dotnet";
    private static final String DOTNET_HOST_DIR = "host";
    private static final String DOTNET_HOST_STASH_DIR = ".orion-hosts";
    private static final String OMNISHARP_DIR = "omnisharp";
    private static final String ROSLYN_DIR = "roslyn";
    private static final String NETCOREDBG_DIR = "netcoredbg";
    private static final String NETCOREDBG_RELEASE_REPOSITORY = "DanielTM999/netcoredbg";
    private static final String NUGET_FLAT_CONTAINER = "https://api.nuget.org/v3-flatcontainer";
    private static final String ROSLYN_LS_DLL = "Microsoft.CodeAnalysis.LanguageServer.dll";
    private static final String ROSLYN_RAZOR_EXTENSION_DLL = "Microsoft.VisualStudioCode.RazorExtension.dll";
    private static final String ROSLYN_RAZOR_SOURCE_GENERATOR_DLL = "Microsoft.CodeAnalysis.Razor.Compiler.dll";
    private static final Pattern SDK_LIST_VERSION = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\.[^\\s]+");
    private static final Pattern GLOBAL_JSON_SDK_VERSION =
            Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private final Resource resource;
    private final DownloadObserver downloadObserver;
    private final Map<String, Boolean> sdkSupportCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Path>> dotnetPathCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Path>> managedDotnetPathCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Path>> roslynBundleCache = new ConcurrentHashMap<>();

    public DotnetSdkService(Resource resource, DownloadObserver downloadObserver) {
        this.resource = resource;
        this.downloadObserver = downloadObserver;
    }

    public boolean isReady() {
        return getDotnetPath().isPresent() && getOmniSharpPath().isPresent();
    }

    public Optional<Path> getDotnetPath() {
        return getDotnetPath(DEFAULT_DOTNET_SDK_VERSION);
    }

    public Optional<Path> getDotnetPath(Path projectRoot) {
        return getDotnetPath(resolveSdkVersion(projectRoot));
    }

    public Optional<Path> getDotnetPath(String sdkVersion) {
        String version = normalizeSdkVersion(sdkVersion);
        return dotnetPathCache.computeIfAbsent(version, this::resolveDotnetPath);
    }

    private Optional<Path> resolveDotnetPath(String version) {
        for (Path home : dotnetHomes()) {
            Optional<Path> muxer = dotnetExecutableIn(home);
            if (muxer.isPresent() && homeHasSdk(home, version)) {
                return muxer;
            }
        }
        Optional<Path> external = findExternalExecutable("dotnet", commonDotnetDirs());
        if (external.isPresent()) {
            if (supportsSdkVersion(external.get(), version)) {
                return external;
            }
            log.debug("dotnet externo ignorado: SDK {} nao encontrado em {}", version, external.get());
        }
        return Optional.empty();
    }

    public Path ensureDotnet(DownloadProgressListener progressListener) {
        return ensureDotnet(DEFAULT_DOTNET_SDK_VERSION, progressListener);
    }

    public Optional<Path> getDotnetRoot() {
        return getDotnetPath().map(Path::getParent);
    }

    public Path ensureDotnet(Path projectRoot, DownloadProgressListener progressListener) {
        return ensureDotnet(resolveSdkVersion(projectRoot), progressListener);
    }

    public Path ensureDotnet(String sdkVersion, DownloadProgressListener progressListener) {
        String version = normalizeSdkVersion(sdkVersion);
        Optional<Path> existing = getDotnetPath(version);
        if (existing.isPresent()) {
            return existing.get();
        }
        DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        installDotnetSdk(version, dotnetHome(), listener);
        return getDotnetPath(version).orElseThrow(() ->
                displayException(text("error.dotnetMissing",
                        "The .NET SDK was downloaded, but the dotnet executable was not found."), null));
    }

    public Optional<Path> getDotnetRoot(Path projectRoot) {
        return getDotnetPath(projectRoot).map(Path::getParent);
    }

    public Optional<Path> getManagedDotnetPath(String sdkVersion) {
        String version = normalizeSdkVersion(sdkVersion);
        return managedDotnetPathCache.computeIfAbsent(version, this::resolveManagedDotnetPath);
    }

    private Optional<Path> resolveManagedDotnetPath(String version) {
        for (Path home : dotnetHomes()) {
            Optional<Path> muxer = dotnetExecutableIn(home);
            if (muxer.isPresent() && homeHasSdk(home, version)) {
                return muxer;
            }
        }
        return Optional.empty();
    }

    public void clearProbeCaches() {
        sdkSupportCache.clear();
        dotnetPathCache.clear();
        managedDotnetPathCache.clear();
        roslynBundleCache.clear();
    }

    public Path ensureManagedDotnet(String sdkVersion, DownloadProgressListener progressListener) {
        String version = normalizeSdkVersion(sdkVersion);
        Optional<Path> existing = getManagedDotnetPath(version);
        if (existing.isPresent()) {
            return existing.get();
        }
        DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        installDotnetSdk(version, dotnetHome(), listener);
        return getManagedDotnetPath(version).orElseThrow(() ->
                displayException(text("error.dotnetMissing",
                        "The .NET SDK was downloaded, but the dotnet executable was not found."), null));
    }

    public String resolveSdkVersion(Path projectRoot) {
        String pinned = readGlobalJsonSdkVersion(projectRoot);
        if (!pinned.isBlank()) {
            return pinned;
        }
        OptionalInt major = TargetFramework.requiredModernMajor(projectRoot);
        return major.isPresent() ? sdkVersionForMajor(major.getAsInt()) : DEFAULT_DOTNET_SDK_VERSION;
    }

    public static String sdkVersionForMajor(int major) {
        return switch (major) {
            case 10 -> DOTNET_10_SDK_VERSION;
            case 9 -> DOTNET_9_SDK_VERSION;
            case 8 -> DEFAULT_DOTNET_SDK_VERSION;
            case 7 -> DOTNET_7_SDK_VERSION;
            case 6 -> DOTNET_6_SDK_VERSION;
            default -> major > 10 ? major + ".0.100" : DEFAULT_DOTNET_SDK_VERSION;
        };
    }

    public Optional<Path> getOmniSharpPath() {
        Optional<Path> bundled = resolveExecutable(omniSharpRoots(DEFAULT_OMNISHARP_VERSION), "OmniSharp");
        if (bundled.isPresent()) {
            return bundled;
        }
        Optional<Path> external = findExternalExecutable("OmniSharp", commonOmniSharpDirs());
        if (external.isPresent()) {
            return external;
        }
        return Optional.empty();
    }

    public Path ensureOmniSharp(DownloadProgressListener progressListener) {
        Optional<Path> existing = getOmniSharpPath();
        if (existing.isPresent()) {
            return existing.get();
        }
        DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        Path root = omniSharpRoot(DEFAULT_OMNISHARP_VERSION);
        downloadToRoot(omniSharpArtifact(DEFAULT_OMNISHARP_VERSION), root, listener);
        return getOmniSharpPath().orElseThrow(() ->
                displayException(text("error.intellisenseMissing",
                        "The C# IntelliSense component was downloaded, but its executable was not found."), null));
    }

    public Optional<Path> getRoslynLanguageServerPath() {
        return resolveRoslynDll(roslynSearchRoots(DEFAULT_ROSLYN_LS_VERSION));
    }

    public Optional<Path> getRazorExtensionPath() {
        return findInRoslynBundle(ROSLYN_RAZOR_EXTENSION_DLL, ROSLYN_RAZOR_EXTENSION_DLL::equals);
    }

    public Optional<Path> getRazorSourceGeneratorPath() {
        return findInRoslynBundle(ROSLYN_RAZOR_SOURCE_GENERATOR_DLL, ROSLYN_RAZOR_SOURCE_GENERATOR_DLL::equals);
    }

    public Optional<Path> getRazorDesignTimeTargets() {
        return findInRoslynBundle("razor.designtime.targets", name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith("designtime.targets") && lower.contains("razor");
        });
    }

    public boolean isRoslynRazorReady() {
        return getRazorExtensionPath().isPresent()
                && getRazorSourceGeneratorPath().isPresent();
    }

    public Path ensureRoslyn(DownloadProgressListener progressListener) {
        Optional<Path> existing = getRoslynLanguageServerPath();
        if (existing.isPresent()) {
            return existing.get();
        }
        DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        Path root = roslynRoot(DEFAULT_ROSLYN_LS_VERSION);
        downloadToRoot(roslynArtifact(DEFAULT_ROSLYN_LS_VERSION), root, listener);
        clearRoslynCompositionCache();
        return getRoslynLanguageServerPath().orElseThrow(() ->
                displayException(text("error.intellisenseMissing",
                        "The C# IntelliSense component was downloaded, but its executable was not found."), null));
    }

    public Path ensureRoslynRazor(DownloadProgressListener progressListener) {
        if (isRoslynRazorReady()) {
            return getRazorExtensionPath().orElseThrow();
        }
        DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        Path root = roslynRoot(DEFAULT_ROSLYN_LS_VERSION);
        downloadToRoot(razorArtifact(DEFAULT_ROSLYN_RAZOR_VERSION), root, listener);
        downloadToRoot(razorCompilerArtifact(DEFAULT_ROSLYN_RAZOR_VERSION), root, listener);
        clearRoslynCompositionCache();
        if (!isRoslynRazorReady()) {
            throw displayException(text("error.razorMissing",
                    "The Razor IntelliSense component was downloaded, but the required Razor files were not found."), null);
        }
        return getRazorExtensionPath().orElseThrow();
    }

    private void clearRoslynCompositionCache() {
        roslynBundleCache.clear();
        for (Path root : roslynSearchRoots(DEFAULT_ROSLYN_LS_VERSION)) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            Path cache = root.resolve("content").resolve("LanguageServer")
                    .resolve(roslynRid()).resolve("cache");
            if (!Files.isDirectory(cache)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(cache)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
                log.info("Cache de composição do Roslyn LS limpo: {}", cache);
            } catch (Exception e) {
                log.debug("Falha ao limpar cache de composição do Roslyn LS: {}", e.getMessage());
            }
        }
    }

    public Optional<Path> getRoslynRuntimeRoot() {
        return getDotnetPath(ROSLYN_RUNTIME_SDK_VERSION).map(Path::getParent);
    }

    public Path ensureRoslynRuntime(DownloadProgressListener progressListener) {
        Optional<Path> existing = getDotnetPath(ROSLYN_RUNTIME_SDK_VERSION);
        if (existing.isPresent()) {
            return existing.get().getParent();
        }
        ensureDotnet(ROSLYN_RUNTIME_SDK_VERSION, progressListener);
        return getRoslynRuntimeRoot().orElseThrow(() ->
                displayException(text("error.runtimeMissing",
                        "The .NET runtime for C# IntelliSense was not found."), null));
    }

    public Optional<Path> getNetcoredbgPath() {
        Optional<Path> bundled = resolveExecutable(netcoredbgRoots(DEFAULT_NETCOREDBG_VERSION), "netcoredbg");
        if (bundled.isPresent()) {
            return bundled;
        }
        Optional<Path> external = findExternalExecutable("netcoredbg", commonNetcoredbgDirs());
        if (external.isPresent()) {
            return external;
        }
        return Optional.empty();
    }

    public boolean isDebuggerReady() {
        return getNetcoredbgPath().isPresent();
    }

    public Optional<Path> getDebugStartupHook() {
        Path sdk = sdkRoot();
        if (sdk == null) {
            return Optional.empty();
        }
        Path target = sdk.resolve("debug").resolve("OrionDebugStartupHook.dll");
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = DotnetSdkService.class.getResourceAsStream("/debug/OrionDebugStartupHook.dll")) {
                if (in == null) {
                    log.debug("Recurso do startup hook de debug não encontrado no classpath.");
                    return Files.isRegularFile(target) && Files.size(target) > 0
                            ? Optional.of(target)
                            : Optional.empty();
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.of(target);
        } catch (Exception e) {
            log.debug("Falha ao provisionar startup hook de debug: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Path> getNcdbHook() {
        Path sdk = sdkRoot();
        if (sdk == null) {
            return Optional.empty();
        }
        Path target = sdk.resolve("debug").resolve("ncdbhook.dll");
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = DotnetSdkService.class.getResourceAsStream("/debug/ncdbhook.dll")) {
                if (in == null) {
                    log.debug("Recurso ncdbhook.dll não encontrado no classpath.");
                    return Files.isRegularFile(target) && Files.size(target) > 0
                            ? Optional.of(target)
                            : Optional.empty();
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.of(target);
        } catch (Exception e) {
            log.debug("Falha ao provisionar ncdbhook.dll: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Path ensureNetcoredbg(DownloadProgressListener progressListener) {
        Optional<Path> existing = getNetcoredbgPath();
        if (existing.isPresent()) {
            return existing.get();
        }
        DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        Path root = netcoredbgRoot(DEFAULT_NETCOREDBG_VERSION);
        downloadToRoot(netcoredbgArtifact(DEFAULT_NETCOREDBG_VERSION), root, listener);
        return getNetcoredbgPath().orElseThrow(() ->
                displayException(text("error.debuggerMissing",
                        "The .NET debugger was downloaded, but its executable was not found."), null));
    }

    private static String normalizeSdkVersion(String sdkVersion) {
        return sdkVersion == null || sdkVersion.isBlank()
                ? DEFAULT_DOTNET_SDK_VERSION
                : sdkVersion.trim();
    }

    private static String readGlobalJsonSdkVersion(Path projectRoot) {
        Path dir = projectRoot == null ? null : (Files.isDirectory(projectRoot) ? projectRoot : projectRoot.getParent());
        while (dir != null) {
            Path globalJson = dir.resolve("global.json");
            if (Files.isRegularFile(globalJson)) {
                try {
                    Matcher matcher = GLOBAL_JSON_SDK_VERSION.matcher(Files.readString(globalJson));
                    return matcher.find() ? matcher.group(1).trim() : "";
                } catch (Exception e) {
                    return "";
                }
            }
            dir = dir.getParent();
        }
        return "";
    }

    private boolean supportsSdkVersion(Path dotnet, String sdkVersion) {
        String requiredVersion = normalizeSdkVersion(sdkVersion);
        if (sdkMajor(requiredVersion).isEmpty()) {
            return true;
        }
        String key = dotnet + "|" + requiredVersion;
        Boolean cached = sdkSupportCache.get(key);
        if (cached != null) {
            return cached;
        }
        Boolean supports = probeSdkVersion(dotnet, requiredVersion);
        if (supports == null) {
            return false;
        }
        sdkSupportCache.put(key, supports);
        return supports;
    }

    private static Boolean probeSdkVersion(Path dotnet, String requiredVersion) {
        Process process = null;
        try {
            process = new ProcessBuilder(dotnet.toString(), "--list-sdks")
                    .redirectErrorStream(true)
                    .start();
            boolean supports = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = SDK_LIST_VERSION.matcher(line);
                    if (matcher.find()) {
                        String installedVersion = line.trim().split("\\s+", 2)[0];
                        if (installedVersion.equals(requiredVersion)) {
                            supports = true;
                        }
                    }
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return supports;
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static OptionalInt sdkMajor(String sdkVersion) {
        String version = normalizeSdkVersion(sdkVersion);
        int dot = version.indexOf('.');
        if (dot <= 0) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(version.substring(0, dot)));
        } catch (Exception e) {
            return OptionalInt.empty();
        }
    }

    private static Optional<Path> findExternalExecutable(String baseName, List<String> commonDirs) {
        String executable = baseName + executableSuffix();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(System.getProperty("path.separator", ":"))) {
                Optional<Path> hit = executableIn(dir, executable);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        for (String dir : commonDirs) {
            Optional<Path> hit = executableIn(dir, executable);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> executableIn(String dir, String executable) {
        if (dir == null || dir.isBlank()) {
            return Optional.empty();
        }
        try {
            Path candidate = Path.of(dir.trim(), executable);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static List<String> commonDotnetDirs() {
        if (isWindows()) {
            return List.of("C:\\Program Files\\dotnet");
        }
        return List.of("/usr/bin", "/usr/local/bin", "/usr/share/dotnet",
                "/usr/lib/dotnet", "/opt/dotnet", "/snap/dotnet-sdk/current");
    }

    private static List<String> commonOmniSharpDirs() {
        return List.of("/usr/bin", "/usr/local/bin");
    }

    private static List<String> commonNetcoredbgDirs() {
        return List.of("/usr/bin", "/usr/local/bin", "/opt/netcoredbg");
    }

    private Path sdkRoot() {
        Path root = resourcePath();
        return root == null ? null : root.resolve(SDK_DIR).toAbsolutePath().normalize();
    }

    private List<Path> sdkRoots() {
        List<Path> roots = new ArrayList<>();
        addSdkRoot(roots, resourcePath());
        addSdkRoot(roots, sharedResourcePath());
        return roots;
    }

    private Path resourcePath() {
        try {
            return resource == null ? null : resource.getResourcePath();
        } catch (Exception e) {
            return null;
        }
    }

    private Path sharedResourcePath() {
        try {
            return resource == null ? null : resource.getSharedResourcePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static void addSdkRoot(List<Path> roots, Path root) {
        if (root == null) {
            return;
        }
        Path sdk = root.resolve(SDK_DIR).toAbsolutePath().normalize();
        addPath(roots, sdk);
    }

    private static void addPath(List<Path> paths, Path path) {
        if (path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!paths.contains(normalized)) {
            paths.add(normalized);
        }
    }

    private Path dotnetHome() {
        Path sdk = sdkRoot();
        return sdk == null ? null : sdk.resolve(DOTNET_DIR).resolve(DOTNET_HOST_DIR);
    }

    private List<Path> dotnetHomes() {
        return sdkRoots().stream()
                .map(root -> root.resolve(DOTNET_DIR).resolve(DOTNET_HOST_DIR))
                .toList();
    }

    private static Optional<Path> dotnetExecutableIn(Path home) {
        if (home == null) {
            return Optional.empty();
        }
        Path muxer = home.resolve("dotnet" + executableSuffix());
        return Files.isRegularFile(muxer)
                ? Optional.of(muxer.toAbsolutePath().normalize())
                : Optional.empty();
    }

    private static boolean homeHasSdk(Path home, String version) {
        return home != null && version != null && !version.isBlank()
                && Files.isDirectory(home.resolve("sdk").resolve(version));
    }

    private void installDotnetSdk(String version, Path home, DownloadProgressListener listener) {
        if (home == null) {
            throw displayException(text("error.resourceDirUnavailable",
                    "Plugin resource directory is not available."), null);
        }
        downloadToRoot(dotnetArtifact(version), home, listener);
        stashHostMuxer(home, version);
        reassertNewestHostMuxer(home);
        sdkSupportCache.clear();
        dotnetPathCache.clear();
        managedDotnetPathCache.clear();
    }

    private void stashHostMuxer(Path home, String version) {
        Optional<Path> muxer = dotnetExecutableIn(home);
        if (muxer.isEmpty()) {
            return;
        }
        try {
            Path stash = home.resolve(DOTNET_HOST_STASH_DIR).resolve(version);
            Files.createDirectories(stash);
            Files.copy(muxer.get(), stash.resolve(muxer.get().getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.debug("Falha ao preservar o host dotnet {}: {}", version, e.getMessage());
        }
    }

    private void reassertNewestHostMuxer(Path home) {
        Path stashRoot = home.resolve(DOTNET_HOST_STASH_DIR);
        if (!Files.isDirectory(stashRoot)) {
            return;
        }
        String newest = null;
        try (Stream<Path> versions = Files.list(stashRoot)) {
            newest = versions
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .max(DotnetSdkService::compareSdkVersions)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Falha ao inspecionar hosts dotnet preservados: {}", e.getMessage());
        }
        if (newest == null) {
            return;
        }
        String muxerName = "dotnet" + executableSuffix();
        Path source = stashRoot.resolve(newest).resolve(muxerName);
        Path target = home.resolve(muxerName);
        if (!Files.isRegularFile(source)) {
            return;
        }
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            if (!isWindows()) {
                target.toFile().setExecutable(true, false);
            }
        } catch (Exception e) {
            log.debug("Falha ao restaurar o host dotnet mais recente ({}): {}", newest, e.getMessage());
        }
    }

    static int compareSdkVersions(String a, String b) {
        int[] va = parseVersionParts(a);
        int[] vb = parseVersionParts(b);
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(va[i], vb[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        boolean aPre = a != null && a.indexOf('-') >= 0;
        boolean bPre = b != null && b.indexOf('-') >= 0;
        if (aPre == bPre) {
            return 0;
        }
        return aPre ? -1 : 1;
    }

    private static int[] parseVersionParts(String version) {
        int[] parts = new int[3];
        if (version == null) {
            return parts;
        }
        String core = version;
        int dash = core.indexOf('-');
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        String[] segments = core.split("\\.");
        for (int i = 0; i < parts.length && i < segments.length; i++) {
            try {
                parts[i] = Integer.parseInt(segments[i].trim());
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    private Path omniSharpRoot(String version) {
        Path sdk = sdkRoot();
        return sdk == null ? null : sdk.resolve(OMNISHARP_DIR).resolve(version);
    }

    private List<Path> omniSharpRoots(String version) {
        return sdkRoots().stream()
                .map(root -> root.resolve(OMNISHARP_DIR).resolve(version))
                .toList();
    }

    private Path roslynRoot(String version) {
        Path sdk = sdkRoot();
        return sdk == null ? null : sdk.resolve(ROSLYN_DIR).resolve(version);
    }

    private List<Path> roslynRoots(String version) {
        return sdkRoots().stream()
                .map(root -> root.resolve(ROSLYN_DIR).resolve(version))
                .toList();
    }

    private List<Path> roslynSearchRoots(String version) {
        List<Path> roots = new ArrayList<>();
        for (Path root : roslynRoots(version)) {
            addPath(roots, root);
        }
        for (Path sdk : sdkRoots()) {
            Path roslyn = sdk.resolve(ROSLYN_DIR);
            if (!Files.isDirectory(roslyn)) {
                continue;
            }
            try (Stream<Path> versions = Files.list(roslyn)) {
                versions.filter(Files::isDirectory)
                        .forEach(path -> addPath(roots, path));
            } catch (Exception ignored) {
            }
        }
        return roots;
    }

    private Path netcoredbgRoot(String version) {
        Path sdk = sdkRoot();
        return sdk == null ? null : sdk.resolve(NETCOREDBG_DIR).resolve(version);
    }

    private List<Path> netcoredbgRoots(String version) {
        return sdkRoots().stream()
                .map(root -> root.resolve(NETCOREDBG_DIR).resolve(version))
                .toList();
    }

    private Optional<Path> findInRoslynBundle(String cacheKey, java.util.function.Predicate<String> nameMatch) {
        Optional<Path> cached = roslynBundleCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Optional<Path> found = searchRoslynBundle(nameMatch);
        if (found.isPresent()) {
            roslynBundleCache.put(cacheKey, found);
        }
        return found;
    }

    private Optional<Path> searchRoslynBundle(java.util.function.Predicate<String> nameMatch) {
        for (Path root : roslynSearchRoots(DEFAULT_ROSLYN_LS_VERSION)) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                Optional<Path> hit = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName() != null)
                        .filter(path -> nameMatch.test(path.getFileName().toString()))
                        .min(Comparator.comparingInt(path -> bundleRank(root, path)))
                        .map(path -> path.toAbsolutePath().normalize());
                if (hit.isPresent()) {
                    return hit;
                }
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }

    private static int bundleRank(Path root, Path path) {
        try {
            Path relative = root.toAbsolutePath().normalize()
                    .relativize(path.toAbsolutePath().normalize());
            String first = relative.getNameCount() > 0
                    ? relative.getName(0).toString().toLowerCase(Locale.ROOT)
                    : "";
            if ("content".equals(first)) {
                return 0;
            }
            if ("lib".equals(first) || "contentfiles".equals(first)
                    || "package".equals(first) || "ref".equals(first)) {
                return 2;
            }
            return 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private Optional<Path> resolveRoslynDll(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        Path direct = root.resolve(ROSLYN_LS_DLL);
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct.toAbsolutePath().normalize());
        }
        Path bundled = root.resolve("content").resolve("LanguageServer")
                .resolve(roslynRid()).resolve(ROSLYN_LS_DLL);
        if (Files.isRegularFile(bundled)) {
            return Optional.of(bundled.toAbsolutePath().normalize());
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> ROSLYN_LS_DLL.equals(path.getFileName().toString()))
                    .min(Comparator.comparingInt(path -> bundleRank(root, path)))
                    .map(path -> path.toAbsolutePath().normalize());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Path> resolveRoslynDll(List<Path> roots) {
        for (Path root : roots) {
            Optional<Path> hit = resolveRoslynDll(root);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    private Optional<Path> resolveExecutable(Path root, String baseName) {
        if (root == null || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        String executable = baseName + executableSuffix();
        Path direct = root.resolve(executable);
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct.toAbsolutePath().normalize());
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> executable.equals(path.getFileName().toString()))
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Path> resolveExecutable(List<Path> roots, String baseName) {
        for (Path root : roots) {
            Optional<Path> executable = resolveExecutable(root, baseName);
            if (executable.isPresent()) {
                return executable;
            }
        }
        return Optional.empty();
    }


    private static SdkArtifact dotnetArtifact(String version) {
        Platform p = currentPlatform();
        String rid = p.dotnetRid();
        String ext = p.isWindows() ? "zip" : "tar.gz";
        String fileName = "dotnet-sdk-" + version + "-" + rid + "." + ext;
        String url = "https://builds.dotnet.microsoft.com/dotnet/Sdk/" + version + "/" + fileName;
        return new SdkArtifact(version, fileName, url, DOTNET_PROGRESS_ID,
                text("download.dotnetSdk", "Downloading .NET SDK") + " " + version);
    }

    private static SdkArtifact omniSharpArtifact(String version) {
        Platform p = currentPlatform();
        String os = p.isWindows() ? "win" : (p.isMac() ? "osx" : "linux");
        String arch = p.isArm64() ? "arm64" : "x64";
        String ext = p.isWindows() ? "zip" : "tar.gz";

        String fileName = "omnisharp-" + os + "-" + arch + "-net6.0." + ext;
        String url = "https://github.com/OmniSharp/omnisharp-roslyn/releases/download/v"
                + version + "/" + fileName;
        return new SdkArtifact(version, fileName, url, OMNISHARP_PROGRESS_ID,
                text("download.intellisense", "Downloading C# IntelliSense") + " " + version);
    }

    private static String roslynRid() {
        Platform p = currentPlatform();
        String os = p.isWindows() ? "win" : (p.isMac() ? "osx" : "linux");
        String arch = p.isArm64() ? "arm64" : "x64";
        return os + "-" + arch;
    }

    private static SdkArtifact roslynArtifact(String version) {
        String packageId = "microsoft.codeanalysis.languageserver." + roslynRid();
        String fileName = packageId + "." + version.toLowerCase(Locale.ROOT) + ".nupkg";
        String url = nugetPackageUrl(packageId, version);
        return new SdkArtifact(version, fileName, url, ROSLYN_LS_PROGRESS_ID,
                text("download.intellisense", "Downloading C# IntelliSense") + " " + version);
    }

    private static SdkArtifact razorArtifact(String version) {
        String packageId = "microsoft.visualstudiocode.razorextension";
        String fileName = packageId + "." + version.toLowerCase(Locale.ROOT) + ".nupkg";
        String url = nugetPackageUrl(packageId, version);
        return new SdkArtifact(version, fileName, url, ROSLYN_LS_PROGRESS_ID,
                text("download.razorSupport", "Downloading Razor support") + " " + version);
    }

    private static SdkArtifact razorCompilerArtifact(String version) {
        String packageId = "microsoft.codeanalysis.razor.compiler";
        String fileName = packageId + "." + version.toLowerCase(Locale.ROOT) + ".nupkg";
        String url = nugetPackageUrl(packageId, version);
        return new SdkArtifact(version, fileName, url, ROSLYN_LS_PROGRESS_ID,
                text("download.razorCompiler", "Downloading Razor compiler") + " " + version);
    }

    private static String nugetPackageUrl(String lowerPackageId, String version) {
        String lowerVersion = version.toLowerCase(Locale.ROOT);
        return NUGET_FLAT_CONTAINER + "/" + lowerPackageId + "/" + lowerVersion
                + "/" + lowerPackageId + "." + lowerVersion + ".nupkg";
    }

    private static SdkArtifact netcoredbgArtifact(String version) {
        Platform p = currentPlatform();
        String fileName;
        if (p.isWindows()) {
            fileName = "netcoredbg-win64.zip";
        } else if (p.isMac()) {
            fileName = "netcoredbg-osx-" + (p.isArm64() ? "arm64" : "amd64") + ".tar.gz";
        } else {
            fileName = "netcoredbg-linux-" + (p.isArm64() ? "arm64" : "amd64") + ".tar.gz";
        }
        String url = "https://github.com/" + NETCOREDBG_RELEASE_REPOSITORY + "/releases/download/"
                + version + "/" + fileName;
        return new SdkArtifact(version, fileName, url, NETCOREDBG_PROGRESS_ID,
                text("download.debugger", "Downloading .NET debugger") + " " + version);
    }

    private void downloadToRoot(SdkArtifact artifact, Path root, DownloadProgressListener listener) {
        if (root == null) {
            throw displayException(text("error.resourceDirUnavailable",
                    "Plugin resource directory is not available."), null);
        }
        Path target = root.resolve(artifact.fileName());
        try {
            Files.createDirectories(root);
            if (Files.isRegularFile(target)) {
                installArchive(target, root);
                return;
            }
        } catch (Exception e) {
            throw displayException(text("error.sdkFolder", "Could not prepare the SDK folder:") + " " + root, e);
        }

        if (downloadObserver == null) {
            throw displayException(text("error.downloadServiceUnavailable",
                    "Download service is not available."), null);
        }

        listener.onStart(artifact.progressId(), artifact.displayName());
        try {
            Exception lastError = null;
            for (int attempt = 1; attempt <= DOWNLOAD_MAX_ATTEMPTS; attempt++) {
                try {
                    downloadToFile(artifact, target, listener);
                    listener.onProgress(artifact.progressId(), text("progress.extracting", "Extracting files"), -1);
                    installArchive(target, root);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw displayException(text("error.downloadInterrupted", "Download interrupted:")
                            + " " + artifact.fileName(), e);
                } catch (DisplayException e) {
                    throw e;
                } catch (Exception e) {
                    lastError = e;
                    deletePartialDownload(target);
                    if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                        log.warn("Falha ao baixar {} (tentativa {}/{}): {}. Tentando novamente...",
                                artifact.fileName(), attempt, DOWNLOAD_MAX_ATTEMPTS, safeMessage(e));
                        listener.onProgress(artifact.progressId(),
                                artifact.displayName() + " — " + text("progress.attempt", "attempt")
                                        + " " + (attempt + 1) + "/" + DOWNLOAD_MAX_ATTEMPTS, -1);
                        sleepBackoff(attempt);
                    }
                }
            }
            throw displayException(text("error.downloadFailed", "Failed to download") + " " + artifact.fileName()
                    + " — " + DOWNLOAD_MAX_ATTEMPTS + " " + text("error.attempts", "attempts"), lastError);
        } finally {
            listener.onFinish(artifact.progressId());
        }
    }

    private void downloadToFile(SdkArtifact artifact, Path target, DownloadProgressListener listener) throws Exception {
        if (downloadObserver == null) {
            throw displayException(text("error.downloadServiceUnavailable",
                    "Download service is not available."), null);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(temp);

        CompletableFuture<Path> done = new CompletableFuture<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        int[] lastPercent = {-1};
        OutputStream output = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        try {
            downloadObserver.newDownloadGetStream(
                    artifact.url(),
                    Map.of("User-Agent", "DotnetOrionSupport/1.0"),
                    new DownloadObserverStreamClient() {
                        @Override
                        public void observerConfiguration(ObserverConfiguration observerConfiguration) {
                            observerConfiguration.setBufferSize(1024 * 128);
                            observerConfiguration.setTimeout(30, TimeUnit.MINUTES);
                            observerConfiguration.setReadTimeout(2, TimeUnit.MINUTES);
                            observerConfiguration.setMaxSizeDownload(2L * 1024L * 1024L * 1024L);
                        }

                        @Override
                        public void onProgress(byte[] content, long bytesRead, long expectedSize, Map<String, List<String>> headers) {
                            try {
                                output.write(content);
                                if (expectedSize > 0) {
                                    int percent = Math.clamp((bytesRead * 100L) / expectedSize, 0, 99);
                                    if (percent != lastPercent[0]) {
                                        lastPercent[0] = percent;
                                        listener.onProgress(artifact.progressId(), artifact.displayName(), percent);
                                    }
                                }
                            } catch (Exception e) {
                                done.completeExceptionally(e);
                                throw new CompletionException(e);
                            }
                        }

                        @Override
                        public void onComplete(Map<String, List<String>> headers) {
                            done.complete(temp);
                        }

                        @Override
                        public void onError(Throwable exception) {
                            done.completeExceptionally(exception);
                        }

                        @Override
                        public void onDisconect() {
                            done.completeExceptionally(new IllegalStateException("Download desconectado: " + artifact.url()));
                        }
                    });

            done.get();
            output.close();
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            finished.set(true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } finally {
            closeQuietly(output);
            if (!finished.get()) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static void installArchive(Path archive, Path targetDir) throws Exception {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archive, targetDir);
        } else if (name.endsWith(".zip") || name.endsWith(".nupkg")) {
            extractZipInto(archive, targetDir);
        } else {
            throw new IllegalStateException("Formato de arquivo não suportado: " + archive.getFileName());
        }
        Files.deleteIfExists(archive);
        makeExecutablesRunnable(targetDir);
    }

    private static void extractTarGz(Path archive, Path targetDir) throws Exception {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(archive));
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path output = normalizedTarget.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedTarget)) {
                    throw new IllegalStateException("Entrada tar inválida: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else if (entry.isFile()) {
                    Path parent = output.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(tar, output, StandardCopyOption.REPLACE_EXISTING);
                    applyTarMode(output, entry.getMode());
                }
            }
        }
    }

    private static void extractZipInto(Path zip, Path targetDir) throws java.io.IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zip.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                Path output = normalizedTarget.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedTarget)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Path parent = output.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static void applyTarMode(Path file, int mode) {
        if (isWindows() || mode <= 0) {
            return;
        }

        if ((mode & 0100) != 0) {
            file.toFile().setExecutable(true, false);
        }
    }

    private static void makeExecutablesRunnable(Path targetDir) {
        if (isWindows()) {
            return;
        }
        try (Stream<Path> paths = Files.walk(targetDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equals("dotnet") || name.equals("OmniSharp")
                                || name.equals("netcoredbg") || name.endsWith(".sh");
                    })
                    .forEach(path -> path.toFile().setExecutable(true, false));
        } catch (Exception ignored) {
        }
    }

    private static void deletePartialDownload(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (Exception ignored) {
        }
        try {
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".part"));
        } catch (Exception ignored) {
        }
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(DOWNLOAD_RETRY_BASE_DELAY_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "";
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static void closeQuietly(OutputStream output) {
        try {
            output.close();
        } catch (Exception ignored) {
        }
    }

    private static String executableSuffix() {
        return isWindows() ? ".exe" : "";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Platform currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.contains("amd64") || arch.contains("x86_64") || arch.equals("x64");
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return arm64 ? Platform.WINDOWS_ARM64 : Platform.WINDOWS_X64;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arm64 ? Platform.MACOS_ARM64 : Platform.MACOS_X64;
        }
        if (os.contains("linux")) {
            return arm64 ? Platform.LINUX_ARM64 : Platform.LINUX_X64;
        }
        if (x64) {
            return Platform.LINUX_X64;
        }
        throw displayException(text("error.unsupportedPlatform",
                "Platform not supported by automatic download:") + " " + os + " / " + arch, null);
    }

    private static DisplayException displayException(String message, Throwable cause) {
        DisplayException exception = cause == null
                ? new DisplayException(message)
                : new DisplayException(message, cause);
        return exception
                .title("Erro ao configurar a toolchain .NET")
                .type(ModernDialog.Type.ERROR)
                .draggable(true);
    }

    private enum Platform {
        WINDOWS_X64("win-x64"),
        WINDOWS_ARM64("win-arm64"),
        LINUX_X64("linux-x64"),
        LINUX_ARM64("linux-arm64"),
        MACOS_X64("osx-x64"),
        MACOS_ARM64("osx-arm64");

        private final String rid;

        Platform(String rid) {
            this.rid = rid;
        }

        String dotnetRid() {
            return rid;
        }

        boolean isWindows() {
            return this == WINDOWS_X64 || this == WINDOWS_ARM64;
        }

        boolean isMac() {
            return this == MACOS_X64 || this == MACOS_ARM64;
        }

        boolean isArm64() {
            return this == WINDOWS_ARM64 || this == LINUX_ARM64 || this == MACOS_ARM64;
        }
    }

    private record SdkArtifact(String version, String fileName, String url, String progressId, String displayName) {
    }

    public interface DownloadProgressListener {
        DownloadProgressListener NOOP = new DownloadProgressListener() {
        };

        default void onStart(String id, String label) {
        }

        default void onProgress(String id, String label, int percent) {
        }

        default void onFinish(String id) {
        }
    }
}
