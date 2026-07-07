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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

@Slf4j
public class DotnetSdkService {

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
    public static final String DEFAULT_ROSLYN_LS_VERSION = "5.10.0-1.26356.6";
    public static final String ROSLYN_RUNTIME_SDK_VERSION = DOTNET_10_SDK_VERSION;
    public static final String DEFAULT_NETCOREDBG_VERSION = "3.1.3-1062-orion-hotreload.3";

    private static final int DOWNLOAD_MAX_ATTEMPTS = 3;
    private static final long DOWNLOAD_RETRY_BASE_DELAY_MS = 1500;

    private static final String SDK_DIR = "sdk";
    private static final String DOTNET_DIR = "dotnet";
    private static final String OMNISHARP_DIR = "omnisharp";
    private static final String ROSLYN_DIR = "roslyn";
    private static final String NETCOREDBG_DIR = "netcoredbg";
    private static final String NETCOREDBG_RELEASE_REPOSITORY = "DanielTM999/netcoredbg";
    private static final String ROSLYN_LS_RELEASE_REPOSITORY = "Crashdummyy/roslynLanguageServer";
    private static final String ROSLYN_LS_DLL = "Microsoft.CodeAnalysis.LanguageServer.dll";
    private static final Pattern SDK_LIST_VERSION = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\.[^\\s]+");
    private static final Pattern GLOBAL_JSON_SDK_VERSION =
            Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private final Resource resource;
    private final DownloadObserver downloadObserver;

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
        Optional<Path> external = findExternalExecutable("dotnet", commonDotnetDirs());
        if (external.isPresent()) {
            if (supportsSdkVersion(external.get(), version)) {
                return external;
            }
            log.debug("dotnet externo ignorado: SDK {} nao encontrado em {}", version, external.get());
        }
        return resolveExecutable(dotnetRoot(version), "dotnet");
    }

    public Path ensureDotnet(DownloadProgressListener progressListener) {
        Optional<Path> existing = getDotnetPath();
        if (existing.isPresent()) {
            return existing.get();
        }
        DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        Path root = dotnetRoot(DEFAULT_DOTNET_SDK_VERSION);
        downloadToRoot(dotnetArtifact(DEFAULT_DOTNET_SDK_VERSION), root, listener);
        return getDotnetPath().orElseThrow(() ->
                displayException(".NET SDK foi baixado, mas o executável dotnet não foi encontrado.", null));
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
        Path root = dotnetRoot(version);
        downloadToRoot(dotnetArtifact(version), root, listener);
        return getDotnetPath(version).orElseThrow(() ->
                displayException(".NET SDK foi baixado, mas o executavel dotnet nao foi encontrado.", null));
    }

    public Optional<Path> getDotnetRoot(Path projectRoot) {
        return getDotnetPath(projectRoot).map(Path::getParent);
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
        Optional<Path> external = findExternalExecutable("OmniSharp", commonOmniSharpDirs());
        if (external.isPresent()) {
            return external;
        }
        return resolveExecutable(omniSharpRoot(DEFAULT_OMNISHARP_VERSION), "OmniSharp");
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
                displayException("OmniSharp foi baixado, mas o executável não foi encontrado.", null));
    }

    public Optional<Path> getRoslynLanguageServerPath() {
        return resolveRoslynDll(roslynRoot(DEFAULT_ROSLYN_LS_VERSION));
    }

    public Path ensureRoslyn(DownloadProgressListener progressListener) {
        Optional<Path> existing = getRoslynLanguageServerPath();
        if (existing.isPresent()) {
            return existing.get();
        }
        DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        Path root = roslynRoot(DEFAULT_ROSLYN_LS_VERSION);
        downloadToRoot(roslynArtifact(DEFAULT_ROSLYN_LS_VERSION), root, listener);
        return getRoslynLanguageServerPath().orElseThrow(() ->
                displayException("Roslyn Language Server foi baixado, mas o binário não foi encontrado.", null));
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
                displayException("Runtime .NET para o Roslyn Language Server não foi encontrado.", null));
    }

    public Optional<Path> getNetcoredbgPath() {
        Optional<Path> external = findExternalExecutable("netcoredbg", commonNetcoredbgDirs());
        if (external.isPresent()) {
            return external;
        }
        return resolveExecutable(netcoredbgRoot(DEFAULT_NETCOREDBG_VERSION), "netcoredbg");
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
                displayException("netcoredbg foi baixado, mas o executável não foi encontrado.", null));
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

    private static boolean supportsSdkVersion(Path dotnet, String sdkVersion) {
        String requiredVersion = normalizeSdkVersion(sdkVersion);
        if (sdkMajor(requiredVersion).isEmpty()) {
            return true;
        }
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
                return false;
            }
            return supports;
        } catch (Exception e) {
            return false;
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
            return List.of(
                    "C:\\Program Files\\dotnet",
                    System.getProperty("user.home", "") + "\\.dotnet");
        }
        return List.of("/usr/bin", "/usr/local/bin", "/usr/share/dotnet",
                "/usr/lib/dotnet", "/opt/dotnet", "/snap/dotnet-sdk/current",
                System.getProperty("user.home", "") + "/.dotnet");
    }

    private static List<String> commonOmniSharpDirs() {
        return List.of("/usr/bin", "/usr/local/bin",
                System.getProperty("user.home", "") + "/.omnisharp",
                System.getProperty("user.home", "") + "/.vscode/extensions");
    }

    private static List<String> commonNetcoredbgDirs() {
        return List.of("/usr/bin", "/usr/local/bin", "/opt/netcoredbg",
                System.getProperty("user.home", "") + "/.netcoredbg");
    }

    private Path sdkRoot() {
        if (resource == null || resource.getResourcePath() == null) {
            return null;
        }
        return resource.getResourcePath().resolve(SDK_DIR).toAbsolutePath().normalize();
    }

    private Path dotnetRoot(String version) {
        Path sdk = sdkRoot();
        return sdk == null ? null : sdk.resolve(DOTNET_DIR).resolve(version);
    }

    private Path omniSharpRoot(String version) {
        Path sdk = sdkRoot();
        return sdk == null ? null : sdk.resolve(OMNISHARP_DIR).resolve(version);
    }

    private Path roslynRoot(String version) {
        Path sdk = sdkRoot();
        return sdk == null ? null : sdk.resolve(ROSLYN_DIR).resolve(version);
    }

    private Path netcoredbgRoot(String version) {
        Path sdk = sdkRoot();
        return sdk == null ? null : sdk.resolve(NETCOREDBG_DIR).resolve(version);
    }

    private Optional<Path> resolveRoslynDll(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        Path direct = root.resolve(ROSLYN_LS_DLL);
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct.toAbsolutePath().normalize());
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> ROSLYN_LS_DLL.equals(path.getFileName().toString()))
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize());
        } catch (Exception e) {
            return Optional.empty();
        }
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

    private static SdkArtifact dotnetArtifact(String version) {
        Platform p = currentPlatform();
        String rid = p.dotnetRid();
        String ext = p.isWindows() ? "zip" : "tar.gz";
        String fileName = "dotnet-sdk-" + version + "-" + rid + "." + ext;
        String url = "https://builds.dotnet.microsoft.com/dotnet/Sdk/" + version + "/" + fileName;
        return new SdkArtifact(version, fileName, url, DOTNET_PROGRESS_ID, "Baixando .NET SDK " + version);
    }

    private static SdkArtifact omniSharpArtifact(String version) {
        Platform p = currentPlatform();
        String os = p.isWindows() ? "win" : (p.isMac() ? "osx" : "linux");
        String arch = p.isArm64() ? "arm64" : "x64";
        String ext = p.isWindows() ? "zip" : "tar.gz";

        String fileName = "omnisharp-" + os + "-" + arch + "-net6.0." + ext;
        String url = "https://github.com/OmniSharp/omnisharp-roslyn/releases/download/v"
                + version + "/" + fileName;
        return new SdkArtifact(version, fileName, url, OMNISHARP_PROGRESS_ID, "Baixando OmniSharp " + version);
    }

    private static SdkArtifact roslynArtifact(String version) {
        Platform p = currentPlatform();
        String os = p.isWindows() ? "win" : (p.isMac() ? "osx" : "linux");
        String arch = p.isArm64() ? "arm64" : "x64";
        String rid = os + "-" + arch;

        String fileName = "microsoft.codeanalysis.languageserver." + rid + ".zip";
        String url = "https://github.com/" + ROSLYN_LS_RELEASE_REPOSITORY + "/releases/download/"
                + version + "/" + fileName;
        return new SdkArtifact(version, fileName, url, ROSLYN_LS_PROGRESS_ID,
                "Baixando Roslyn Language Server " + version);
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
        return new SdkArtifact(version, fileName, url, NETCOREDBG_PROGRESS_ID, "Baixando netcoredbg " + version);
    }

    private void downloadToRoot(SdkArtifact artifact, Path root, DownloadProgressListener listener) {
        if (root == null) {
            throw displayException("Diretório de recursos do plugin não disponível.", null);
        }
        Path target = root.resolve(artifact.fileName());
        try {
            Files.createDirectories(root);
            if (Files.isRegularFile(target)) {
                installArchive(target, root);
                return;
            }
        } catch (Exception e) {
            throw displayException("Não foi possível preparar a pasta do SDK: " + root, e);
        }

        if (downloadObserver == null) {
            throw displayException("Serviço de download não disponível.", null);
        }

        listener.onStart(artifact.progressId(), artifact.displayName());
        try {
            Exception lastError = null;
            for (int attempt = 1; attempt <= DOWNLOAD_MAX_ATTEMPTS; attempt++) {
                try {
                    downloadToFile(artifact, target, listener);
                    listener.onProgress(artifact.progressId(), "Extraindo arquivos", -1);
                    installArchive(target, root);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw displayException("Download interrompido: " + artifact.fileName(), e);
                } catch (DisplayException e) {
                    throw e;
                } catch (Exception e) {
                    lastError = e;
                    deletePartialDownload(target);
                    if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                        log.warn("Falha ao baixar {} (tentativa {}/{}): {}. Tentando novamente...",
                                artifact.fileName(), attempt, DOWNLOAD_MAX_ATTEMPTS, safeMessage(e));
                        listener.onProgress(artifact.progressId(),
                                artifact.displayName() + " — tentativa " + (attempt + 1) + "/" + DOWNLOAD_MAX_ATTEMPTS, -1);
                        sleepBackoff(attempt);
                    }
                }
            }
            throw displayException("Falha ao baixar " + artifact.fileName()
                    + " após " + DOWNLOAD_MAX_ATTEMPTS + " tentativas.", lastError);
        } finally {
            listener.onFinish(artifact.progressId());
        }
    }

    private void downloadToFile(SdkArtifact artifact, Path target, DownloadProgressListener listener) throws Exception {
        if (downloadObserver == null) {
            throw displayException("Serviço de download não disponível.", null);
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
        } else if (name.endsWith(".zip")) {
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
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
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
                    Files.copy(zin, output, StandardCopyOption.REPLACE_EXISTING);
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
        throw displayException("Plataforma não suportada pelo download automático: " + os + " / " + arch, null);
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
