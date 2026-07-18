package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.api.extension.Resource;
import dtm.ide.api.project.editor.DocumentHighlight;
import dtm.ide.run.TargetFramework;
import dtm.ide.sdk.DotnetSdkService;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class RoslynLspService extends AbstractLspService {

    private static final long DIAGNOSTIC_TIMEOUT_MS = 5000;
    private static final long RAZOR_COMPLETION_REGISTRATION_WAIT_MS = 3000;
    private volatile boolean razorProject;
    private volatile boolean launchedWithRazor;
    private volatile CountDownLatch razorCompletionRegistration = new CountDownLatch(1);
    private final Set<String> razorRegisteredMethods = ConcurrentHashMap.newKeySet();
    private final List<Runnable> razorCohostReadyListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean razorCohostReadyFired = new AtomicBoolean(false);
    private final AtomicBoolean razorDocsResynced = new AtomicBoolean(false);

    public RoslynLspService(Resource resource, DotnetSdkService sdkService) {
        super(resource, sdkService);
    }

    @Override
    protected String serverName() {
        return "C# IntelliSense";
    }

    @Override
    protected Optional<Path> resolveServerBinary() {
        return sdkService().getRoslynLanguageServerPath();
    }

    @Override
    protected List<String> buildLaunchCommand(Path binary, Path loadTarget) {
        Path dotnet = sdkService().getDotnetPath(DotnetSdkService.ROSLYN_RUNTIME_SDK_VERSION)
                .or(() -> sdkService().getDotnetPath(projectPath()))
                .orElseThrow(() -> new IllegalStateException(
                        "dotnet não encontrado para executar o IntelliSense C#."));
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().toString());
        command.add(binary.toAbsolutePath().toString());
        command.add("--logLevel");
        command.add("Information");
        command.add("--extensionLogDirectory");
        command.add(logDirectory().toAbsolutePath().toString());
        command.add("--stdio");
        Optional<Path> razorExtension = sdkService().getRazorExtensionPath();
        Optional<Path> razorSourceGenerator = sdkService().getRazorSourceGeneratorPath();
        Optional<Path> razorDesignTimeTargets = sdkService().getRazorDesignTimeTargets();
        launchedWithRazor = razorExtension.isPresent();
        if (!launchedWithRazor) {
            log.warn("Roslyn LS sem suporte Razor — RazorExtension.dll não encontrado no bundle "
                    + "(sourceGenerator={} designTimeTargets={})",
                    razorSourceGenerator.isPresent(), razorDesignTimeTargets.isPresent());
        } else {
            razorSourceGenerator.ifPresent(path -> {
                command.add("--razorSourceGenerator");
                command.add(path.toString());
            });
            razorDesignTimeTargets.ifPresent(path -> {
                command.add("--razorDesignTimePath");
                command.add(path.toString());
            });
            command.add("--extension");
            command.add(razorExtension.get().toString());
        }
        log.info("Roslyn LS: {}", String.join(" ", command));
        return command;
    }

    @Override
    protected void prepareServerConfig(Path binary) {
        razorRegisteredMethods.clear();
        razorCompletionRegistration = new CountDownLatch(1);
        razorCohostReadyFired.set(false);
        razorDocsResynced.set(false);
    }

    public void addRazorCohostReadyListener(Runnable listener) {
        if (listener != null) {
            razorCohostReadyListeners.add(listener);
        }
    }

    public void bindRazorProject(boolean razorProject) {
        this.razorProject = razorProject;
    }

    @Override
    public boolean supportsRazor() {
        return razorProject || launchedWithRazor || sdkService().isRoslynRazorReady();
    }

    public boolean isRazorLaunchEnabled() {
        return launchedWithRazor;
    }

    @Override
    public HoverInfo hover(Path filePath, String text, int line, int character) {
        if (isRazorFile(filePath) && !isRazorMethodRegistered("textDocument/hover")) {
            return null;
        }
        return super.hover(filePath, text, line, character);
    }

    @Override
    public List<DocumentHighlight> documentHighlights(Path filePath, String text, int line, int character) {
        if (isRazorFile(filePath) && !isRazorMethodRegistered("textDocument/documentHighlight")) {
            return Collections.emptyList();
        }
        return super.documentHighlights(filePath, text, line, character);
    }

    @Override
    public List<DocumentSymbol> documentSymbols(Path filePath, String text) {
        if (isRazorFile(filePath) && !isRazorMethodRegistered("textDocument/documentSymbol")) {
            return Collections.emptyList();
        }
        return super.documentSymbols(filePath, text);
    }

    @Override
    public List<InlayHint> inlayHints(Path filePath, String text, int firstLine, int lastLine) {
        if (isRazorFile(filePath)) {
            return Collections.emptyList();
        }
        return super.inlayHints(filePath, text, firstLine, lastLine);
    }

    @Override
    public List<AutoCompleteItem> completeForEditor(Path filePath, String text, int line, int character, String prefix) {
        if (isRazorFile(filePath) && !awaitRazorCompletionRegistration()) {
            log.warn("Completion Razor sem registro cohost (launchedWithRazor={}, registrados={}); tentando mesmo assim",
                    launchedWithRazor, razorRegisteredMethods);
        }
        return super.completeForEditor(filePath, text, line, character, prefix);
    }

    @Override
    public List<AutoCompleteItem> complete(Path filePath, String text, int line, int character) {
        if (isRazorFile(filePath) && !awaitRazorCompletionRegistration()) {
            log.warn("Completion Razor sem registro cohost (launchedWithRazor={}, registrados={}); tentando mesmo assim",
                    launchedWithRazor, razorRegisteredMethods);
        }
        return super.complete(filePath, text, line, character);
    }

    private boolean awaitRazorCompletionRegistration() {
        if (isRazorMethodRegistered("textDocument/completion")) {
            return true;
        }
        try {
            razorCompletionRegistration.await(RAZOR_COMPLETION_REGISTRATION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean registered = isRazorMethodRegistered("textDocument/completion");
        if (!registered) {
            log.debug("Timeout aguardando registro do completion Razor; métodos cohost registrados: {}", razorRegisteredMethods);
        }
        return registered;
    }

    private boolean isRazorMethodRegistered(String method) {
        return method != null && razorRegisteredMethods.contains(method);
    }

    private Path logDirectory() {
        Resource res = resource();
        Path base = res == null ? null : res.getResourcePath();
        Path dir = base != null
                ? base.resolve("roslyn-ls-logs")
                : Path.of(System.getProperty("java.io.tmpdir", "."), "orion-roslyn-ls-logs");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.debug("Não foi possível criar o diretório de logs do Roslyn LS: {}", e.getMessage());
        }
        return dir;
    }

    @Override
    protected Optional<Path> resolveDotnetRoot() {
        return sdkService().getRoslynRuntimeRoot()
                .or(() -> sdkService().getDotnetRoot(projectPath()));
    }

    @Override
    protected Map<String, Object> extraTextDocumentCapabilities() {
        return Map.of("diagnostic", Map.of(
                "dynamicRegistration", true,
                "relatedDocumentSupport", false));
    }

    @Override
    protected void onCapabilityRegistered(String method, JsonNode registerOptions) {
        if (method == null || method.isBlank() || !isRazorDocumentSelector(registerOptions)) {
            return;
        }
        log.info("Cohosting Razor registrou {}", method);
        razorRegisteredMethods.add(method);
        if (("textDocument/didOpen".equals(method) || "textDocument/completion".equals(method))
                && razorDocsResynced.compareAndSet(false, true)) {
            resyncDocuments(RoslynLspService::isRazorUri);
        }
        if ("textDocument/completion".equals(method)) {
            razorCompletionRegistration.countDown();
            if (razorCohostReadyFired.compareAndSet(false, true)) {
                for (Runnable listener : razorCohostReadyListeners) {
                    try {
                        listener.run();
                    } catch (Exception e) {
                        log.debug("Falha ao notificar cohosting Razor pronto: {}", e.getMessage());
                    }
                }
            }
        }
    }

    @Override
    protected void onCapabilityUnregistered(String method) {
        if (method == null || method.isBlank()) {
            return;
        }
        razorRegisteredMethods.remove(method);
        if ("textDocument/completion".equals(method)) {
            razorCompletionRegistration = new CountDownLatch(1);
        }
    }

    @Override
    protected void registerServerNotificationHandlers(LspJsonRpcClient rpc) {
        rpc.onNotification("workspace/projectInitializationComplete", params -> {
            markLoadFinished();
            resyncDocuments(RoslynLspService::isRazorUri);
        });
    }

    @Override
    protected void afterInitialized() {
        LspJsonRpcClient rpc = rpc();
        if (rpc == null) {
            return;
        }
        rpc.sendNotification("workspace/didChangeConfiguration",
                Map.of("settings", Map.of("razor", razorSettingsBlock())));
        Path target = loadTarget();
        if (target != null && Files.isRegularFile(target) && isSolution(target)) {
            rpc.sendNotification("solution/open", Map.of("solution", toUri(target)));
        }
        List<Path> projects = TargetFramework.findProjectFiles(projectPath());
        if (!projects.isEmpty()) {
            List<String> uris = new ArrayList<>(projects.size());
            for (Path project : projects) {
                uris.add(toUri(project));
            }
            rpc.sendNotification("project/open", Map.of("projects", uris));
        }
    }

    private static boolean isSolution(Path path) {
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sln") || name.endsWith(".slnx");
    }

    private static boolean isRazorFile(Path filePath) {
        if (filePath == null || filePath.getFileName() == null) {
            return false;
        }
        String name = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".cshtml") || name.endsWith(".razor");
    }

    private static boolean isRazorDocumentSelector(JsonNode registerOptions) {
        JsonNode selector = registerOptions == null ? null : registerOptions.get("documentSelector");
        if (selector == null || !selector.isArray()) {
            return false;
        }
        for (JsonNode filter : selector) {
            String language = filter.path("language").asText("").toLowerCase(Locale.ROOT);
            String pattern = filter.path("pattern").asText("").toLowerCase(Locale.ROOT);
            if (language.contains("razor")
                    || pattern.contains("cshtml")
                    || pattern.contains("razor")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRazorUri(String uri) {
        String lower = uri == null ? "" : uri.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cshtml") || lower.endsWith(".razor");
    }

    @Override
    public Collection<Diagnostic> diagnose(Path filePath, String text) {
        if (!isRunning() || rpc() == null || filePath == null) {
            return Collections.emptyList();
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            JsonNode result = rpc().sendRequest("textDocument/diagnostic", Map.of(
                    "textDocument", Map.of("uri", uri)
            )).get(DIAGNOSTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            List<JsonNode> raw = extractPullDiagnostics(result);
            storeDiagnostics(uri, raw);
            List<Diagnostic> out = new ArrayList<>(raw.size());
            for (JsonNode node : raw) {
                Diagnostic diagnostic = parseDiagnostic(node);
                if (diagnostic != null) {
                    out.add(diagnostic);
                }
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static List<JsonNode> extractPullDiagnostics(JsonNode result) {
        if (result == null || result.isNull()) {
            return Collections.emptyList();
        }
        JsonNode items = result.isArray() ? result : result.get("items");
        if (items == null || !items.isArray()) {
            return Collections.emptyList();
        }
        List<JsonNode> out = new ArrayList<>(items.size());
        items.forEach(out::add);
        return out;
    }
}
