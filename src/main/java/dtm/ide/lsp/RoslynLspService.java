package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.api.extension.Resource;
import dtm.ide.run.TargetFramework;
import dtm.ide.sdk.DotnetSdkService;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
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
import java.util.concurrent.TimeUnit;

@Slf4j
public final class RoslynLspService extends AbstractLspService {

    private static final long DIAGNOSTIC_TIMEOUT_MS = 5000;

    public RoslynLspService(Resource resource, DotnetSdkService sdkService) {
        super(resource, sdkService);
    }

    @Override
    protected String serverName() {
        return "Roslyn Language Server";
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
                        "dotnet não encontrado para executar o Roslyn Language Server."));
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().toString());
        command.add(binary.toAbsolutePath().toString());
        command.add("--logLevel");
        command.add("Information");
        command.add("--extensionLogDirectory");
        command.add(logDirectory().toAbsolutePath().toString());
        command.add("--stdio");
        return command;
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
                "dynamicRegistration", false,
                "relatedDocumentSupport", false));
    }

    @Override
    protected void registerServerNotificationHandlers(LspJsonRpcClient rpc) {
        rpc.onNotification("workspace/projectInitializationComplete", params -> markLoadFinished());
    }

    @Override
    protected void afterInitialized() {
        LspJsonRpcClient rpc = rpc();
        if (rpc == null) {
            return;
        }
        Path target = loadTarget();
        if (target != null && Files.isRegularFile(target) && isSolution(target)) {
            rpc.sendNotification("solution/open", Map.of("solution", toUri(target)));
            return;
        }
        List<Path> projects = TargetFramework.findProjectFiles(projectPath());
        if (projects.isEmpty()) {
            return;
        }
        List<String> uris = new ArrayList<>(projects.size());
        for (Path project : projects) {
            uris.add(toUri(project));
        }
        rpc.sendNotification("project/open", Map.of("projects", uris));
    }

    private static boolean isSolution(Path path) {
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sln") || name.endsWith(".slnx");
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
