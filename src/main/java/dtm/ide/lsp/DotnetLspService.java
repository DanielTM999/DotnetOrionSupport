package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.api.extension.Resource;
import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.sdk.DotnetSdkService;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.Command;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
public final class DotnetLspService {

    public enum State {
        NOT_STARTED, STARTING, READY, STOPPED, ERROR
    }

    private static final long REQUEST_TIMEOUT_MS = 4000;
    private static final long COMPLETION_TIMEOUT_MS = 10000;

    private static final long INIT_TIMEOUT_MS = 120000;

    private static final Set<SymbolKind> CALLABLE_KINDS = EnumSet.of(
            SymbolKind.METHOD, SymbolKind.FUNCTION, SymbolKind.CONSTRUCTOR, SymbolKind.OPERATOR, SymbolKind.PROPERTY);

    private static final Set<String> CSHARP_KEYWORDS = Set.of(
            "if", "else", "for", "foreach", "while", "do", "switch", "case", "catch", "return",
            "using", "lock", "fixed", "sizeof", "typeof", "nameof", "new", "throw", "await",
            "in", "is", "as", "default", "checked", "unchecked", "stackalloc", "when", "with",
            "and", "or", "not");

    private final DotnetSdkService sdkService;
    private final Resource resource;
    private final ExecutorService executor = Executors.newCachedThreadPool(daemonFactory("dotnet-lsp"));
    private final Object processLock = new Object();
    private final AtomicBoolean intentionalStop = new AtomicBoolean(false);
    private final List<Consumer<String>> diagnosticsPublishedListeners = new ArrayList<>();

    private volatile State state = State.NOT_STARTED;
    private volatile String lastError;
    private volatile Path projectPath;
    private volatile Process process;
    private volatile Future<?> stderrPump;
    private volatile Future<?> watcher;
    private volatile LspJsonRpcClient client;

    private volatile boolean definitionSupported;
    private volatile boolean referencesSupported;
    private volatile boolean documentSymbolSupported;
    private volatile boolean renameSupported;
    private volatile boolean formattingSupported;
    private volatile boolean callHierarchySupported;
    private volatile boolean codeActionSupported;

    private final Map<String, AtomicInteger> documentVersions = new ConcurrentHashMap<>();
    private final Map<String, String> openedContent = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> diagnosticsByUri = new ConcurrentHashMap<>();

    public DotnetLspService(Resource resource, DotnetSdkService sdkService) {
        this.sdkService = sdkService;
        this.resource = resource;
    }

    public void bindProject(Path projectPath) {
        this.projectPath = projectPath == null ? null : projectPath.toAbsolutePath().normalize();
    }

    public State getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive() && state == State.READY;
    }

    public void addDiagnosticsPublishedListener(Consumer<String> listener) {
        if (listener != null) {
            synchronized (diagnosticsPublishedListeners) {
                diagnosticsPublishedListeners.add(listener);
            }
        }
    }

    public void start() {
        synchronized (processLock) {
            intentionalStop.set(false);
            if (isRunning()) {
                return;
            }
            doStart();
        }
    }

    public void stop() {
        synchronized (processLock) {
            intentionalStop.set(true);
            doStop();
            state = State.STOPPED;
        }
    }

    public List<AutoCompleteItem> complete(Path filePath, String text, int line, int character) {
        if (!canUseLsp(filePath)) {
            return Collections.emptyList();
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            JsonNode result = client.sendRequest("textDocument/completion", Map.of(
                    "textDocument", Map.of("uri", uri),
                    "position", LspJsonRpcClient.position(line, character),
                    "context", Map.of("triggerKind", 1)
            )).get(COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return parseCompletionResult(result);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public HoverInfo hover(Path filePath, String text, int line, int character) {
        if (!canUseLsp(filePath)) {
            return null;
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            JsonNode result = client.sendRequest("textDocument/hover", Map.of(
                    "textDocument", Map.of("uri", uri),
                    "position", LspJsonRpcClient.position(line, character)
            )).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return parseHoverResult(result);
        } catch (Exception e) {
            return null;
        }
    }

    public HoverInfo diagnosticHover(Path filePath, int line, int character) {
        Diagnostic diagnostic = diagnosticAt(filePath, line, character);
        if (diagnostic == null || diagnostic.message() == null || diagnostic.message().isBlank()) {
            return null;
        }
        return new HoverInfo(
                escapeHtml(diagnostic.message()),
                true,
                diagnostic.startLine(),
                diagnostic.startCol(),
                diagnostic.endLine(),
                diagnostic.endCol()
        );
    }

    public List<Location> definitions(Path filePath, String text, int line, int character) {
        if (!canUseLsp(filePath) || !definitionSupported) {
            return Collections.emptyList();
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            JsonNode result = client.sendRequest("textDocument/definition", Map.of(
                    "textDocument", Map.of("uri", uri),
                    "position", LspJsonRpcClient.position(line, character)
            )).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return resolveMetadataLocations(parseLocations(result));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<Location> resolveMetadataLocations(List<Location> locations) {
        List<Location> out = new ArrayList<>(locations.size());
        for (Location loc : locations) {
            if (loc == null) {
                continue;
            }
            if (isMetadataUri(loc.uri())) {
                Location resolved = resolveMetadataLocation(loc);
                if (resolved != null) {
                    out.add(resolved);
                }
            } else {
                out.add(loc);
            }
        }
        return out;
    }

    private static boolean isMetadataUri(String uri) {
        return uri != null && (uri.contains("$metadata$") || uri.contains("%24metadata%24"));
    }

    private Location resolveMetadataLocation(Location loc) {
        try {
            String decoded = java.net.URLDecoder.decode(loc.uri(), StandardCharsets.UTF_8).replace('\\', '/');
            String project = between(decoded, "/Project/", "/Assembly/");
            String assemblyPath = between(decoded, "/Assembly/", "/Symbol/");
            String typePath = after(decoded, "/Symbol/");
            if (typePath.toLowerCase(Locale.ROOT).endsWith(".cs")) {
                typePath = typePath.substring(0, typePath.length() - 3);
            }
            if (assemblyPath.isBlank() || typePath.isBlank()) {
                return null;
            }
            String assembly = assemblyPath.replace('/', '.');
            String type = typePath.replace('/', '.');
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("ProjectName", project);
            params.put("AssemblyName", assembly);
            params.put("TypeName", type);
            params.put("Language", "C#");
            params.put("Timeout", 5000);
            JsonNode response = client.sendRequest("o#/metadata", params)
                    .get(REQUEST_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
            String source = response == null ? "" : response.path("Source").asText("");
            if (source.isBlank()) {
                return null;
            }
            String sourceName = response.path("SourceName").asText(type + ".cs");
            Path file = writeMetadataFile(assembly, type, sourceName, source);
            return new Location(file.toUri().toString(), loc.range());
        } catch (Exception e) {
            log.debug("Falha ao resolver metadados decompilados de {}: {}", loc.uri(), e.getMessage());
            return null;
        }
    }

    private Path writeMetadataFile(String assembly, String type, String sourceName, String source) throws IOException {
        Path cacheDir = metadataCacheDir();
        Files.createDirectories(cacheDir);
        String fileName = sanitizeFileName(assembly + "." + type) + ".cs";
        Path file = cacheDir.resolve(fileName);

        if (!Files.exists(file) || !Objects.equals(safeRead(file), source)) {
            Files.writeString(file, source, StandardCharsets.UTF_8);
        }
        return file;
    }

    private Path metadataCacheDir() {
        Path base = resource == null ? null : resource.getResourcePath();
        if (base != null) {
            return base.resolve("metadata-cache");
        }
        return Path.of(System.getProperty("java.io.tmpdir", "."), "orion-dotnet-metadata");
    }

    public void clearMetadataCache() {
        Path cacheDir = metadataCacheDir();
        if (cacheDir == null || !Files.isDirectory(cacheDir)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(cacheDir)) {
            for (Path file : entries) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.debug("Falha ao remover arquivo decompilado {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("Falha ao limpar cache de decompilados: {}", e.getMessage());
        }
    }

    private static String safeRead(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeFileName(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._+-]", "_");
        return sanitized.length() > 180 ? sanitized.substring(0, 180) : sanitized;
    }

    private static String between(String text, String start, String end) {
        int i = text.indexOf(start);
        if (i < 0) {
            return "";
        }
        int from = i + start.length();
        int j = text.indexOf(end, from);
        return j < 0 ? "" : text.substring(from, j);
    }

    private static String after(String text, String marker) {
        int i = text.indexOf(marker);
        return i < 0 ? "" : text.substring(i + marker.length());
    }

    public List<Location> references(Path filePath, String text, int line, int character) {
        if (!canUseLsp(filePath) || !referencesSupported) {
            return Collections.emptyList();
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            JsonNode result = client.sendRequest("textDocument/references", Map.of(
                    "textDocument", Map.of("uri", uri),
                    "position", LspJsonRpcClient.position(line, character),
                    "context", Map.of("includeDeclaration", true)
            )).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return parseLocations(result);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<Location> callSiteReferences(Path filePath, String text, int line, int character) {
        if (!canUseLsp(filePath) || !referencesSupported) {
            return Collections.emptyList();
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            JsonNode result = client.sendRequest("textDocument/references", Map.of(
                    "textDocument", Map.of("uri", uri),
                    "position", LspJsonRpcClient.position(line, character),
                    "context", Map.of("includeDeclaration", false)
            )).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return parseLocations(result);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<DocumentSymbol> documentSymbols(Path filePath, String text) {
        if (!canUseLsp(filePath) || !documentSymbolSupported) {
            return Collections.emptyList();
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            JsonNode result = client.sendRequest("textDocument/documentSymbol", Map.of(
                    "textDocument", Map.of("uri", uri)
            )).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return parseDocumentSymbols(result);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<TextEdit> rename(Path filePath, String text, int line, int character, String newName) {
        DotnetWorkspaceEdit edit = renameWorkspace(filePath, text, line, character, newName);
        return edit.editsFor(filePath);
    }

    public DotnetWorkspaceEdit renameWorkspace(Path filePath, String text, int line, int character, String newName) {
        if (!canUseLsp(filePath) || !renameSupported || newName == null || newName.isBlank()) {
            return new DotnetWorkspaceEdit(Map.of());
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            JsonNode result = client.sendRequest("textDocument/rename", Map.of(
                    "textDocument", Map.of("uri", uri),
                    "position", LspJsonRpcClient.position(line, character),
                    "newName", newName
            )).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return parseWorkspaceEdit(result);
        } catch (Exception e) {
            return new DotnetWorkspaceEdit(Map.of());
        }
    }

    public String format(Path filePath, String text, int tabSize, boolean insertSpaces) {
        if (!canUseLsp(filePath) || !formattingSupported) {
            return null;
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            JsonNode result = client.sendRequest("textDocument/formatting", Map.of(
                    "textDocument", Map.of("uri", uri),
                    "options", Map.of("tabSize", tabSize, "insertSpaces", insertSpaces)
            )).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            List<TextEdit> edits = parseTextEdits(result);
            return edits.isEmpty() ? null : applyEdits(text == null ? "" : text, edits);
        } catch (Exception e) {
            return null;
        }
    }

    public Collection<Diagnostic> diagnose(Path filePath, String text) {
        if (!canUseLsp(filePath)) {
            return Collections.emptyList();
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            List<JsonNode> raw = diagnosticsByUri.get(normalizeUriKey(uri));
            if (raw == null) {
                return Collections.emptyList();
            }
            List<Diagnostic> out = new ArrayList<>(raw.size());
            for (JsonNode node : raw) {
                Diagnostic d = parseDiagnostic(node);
                if (d != null) {
                    out.add(d);
                }
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public Diagnostic diagnosticAt(Path filePath, int line, int character) {
        if (filePath == null) {
            return null;
        }
        List<JsonNode> raw = diagnosticsByUri.get(normalizeUriKey(toUri(filePath)));
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Diagnostic first = null;
        for (JsonNode node : raw) {
            Diagnostic d = parseDiagnostic(node);
            if (d == null || !containsPosition(d, line, character)) {
                continue;
            }
            if (d.severity() == DiagnosticSeverity.ERROR) {
                return d;
            }
            if (first == null) {
                first = d;
            }
        }
        return first;
    }

    public List<CodeAction> codeActions(Path filePath, String text, Range range, List<Diagnostic> diagnostics) {
        if (!canUseLsp(filePath) || !codeActionSupported || range == null) {
            return Collections.emptyList();
        }
        String uri = toUri(filePath);
        try {
            syncDocument(uri, filePath, text);
            JsonNode result = client.sendRequest("textDocument/codeAction", Map.of(
                    "textDocument", Map.of("uri", uri),
                    "range", rangeToLsp(range),
                    "context", Map.of("diagnostics", rawDiagnosticsFor(uri, range))
            )).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return parseCodeActionResult(result, uri);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<JsonNode> rawDiagnosticsFor(String uri, Range range) {
        List<JsonNode> raw = diagnosticsByUri.get(normalizeUriKey(uri));
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode node : raw) {
            Diagnostic parsed = parseDiagnostic(node);
            if (parsed != null && rangeIntersectsDiagnostic(range, parsed)) {
                out.add(node);
            }
        }
        return out;
    }

    private static List<CodeAction> parseCodeActionResult(JsonNode result, String requestUri) {
        if (result == null || result.isNull() || !result.isArray()) {
            return Collections.emptyList();
        }
        List<CodeAction> actions = new ArrayList<>();
        for (JsonNode item : result) {
            CodeAction action = parseCodeAction(item, requestUri);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    private static CodeAction parseCodeAction(JsonNode item, String requestUri) {
        if (item == null || item.isNull()) {
            return null;
        }
        String title = textOrEmpty(item.get("title"));
        if (title.isBlank()) {
            return null;
        }
        List<dtm.stools.component.panels.editor.code.api.TextEdit> edits =
                parseWorkspaceEdit(item.get("edit"), requestUri);
        JsonNode commandNode = item.get("command");
        Command command = null;
        if (commandNode != null && commandNode.isObject()) {
            String commandId = textOrEmpty(commandNode.get("command"));
            String commandTitle = textOrEmpty(commandNode.get("title"));
            if (!commandId.isBlank()) {
                command = new Command(commandId, commandTitle.isBlank() ? title : commandTitle,
                        commandArguments(commandNode.get("arguments")));
            }
        }
        if (edits.isEmpty() && command == null) {
            return null;
        }
        return new CodeAction(
                title,
                mapCodeActionKind(textOrEmpty(item.get("kind"))),
                edits,
                command,
                item.path("isPreferred").asBoolean(false)
        );
    }

    private static List<Object> commandArguments(JsonNode arguments) {
        if (arguments == null || !arguments.isArray() || arguments.isEmpty()) {
            return List.of();
        }
        List<Object> out = new ArrayList<>(arguments.size());
        arguments.forEach(out::add);
        return out;
    }

    private static List<dtm.stools.component.panels.editor.code.api.TextEdit> parseWorkspaceEdit(
            JsonNode edit, String requestUri) {
        if (edit == null || edit.isNull()) {
            return Collections.emptyList();
        }
        List<dtm.stools.component.panels.editor.code.api.TextEdit> edits = new ArrayList<>();
        JsonNode changes = edit.get("changes");
        if (changes != null && changes.isObject()) {
            addTextEdits(changes.get(requestUri), edits);
        }
        JsonNode documentChanges = edit.get("documentChanges");
        if (documentChanges != null && documentChanges.isArray()) {
            for (JsonNode change : documentChanges) {
                JsonNode textDocument = change.get("textDocument");
                String uri = textOrEmpty(textDocument == null ? null : textDocument.get("uri"));
                if (Objects.equals(uri, requestUri)) {
                    addTextEdits(change.get("edits"), edits);
                }
            }
        }
        return edits;
    }

    private static void addTextEdits(JsonNode editsNode, List<dtm.stools.component.panels.editor.code.api.TextEdit> out) {
        if (editsNode == null || !editsNode.isArray()) {
            return;
        }
        for (JsonNode edit : editsNode) {
            Range range = parseRange(edit.get("range"));
            if (range != null) {
                out.add(dtm.stools.component.panels.editor.code.api.TextEdit.replace(range, textOrEmpty(edit.get("newText"))));
            }
        }
    }

    private static CodeAction.CodeActionKind mapCodeActionKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return CodeAction.CodeActionKind.OTHER;
        }
        String n = kind.toLowerCase(Locale.ROOT);
        if (n.startsWith("quickfix")) {
            return CodeAction.CodeActionKind.QUICK_FIX;
        }
        if (n.startsWith("refactor.extract")) {
            return CodeAction.CodeActionKind.REFACTOR_EXTRACT;
        }
        if (n.startsWith("refactor.inline")) {
            return CodeAction.CodeActionKind.REFACTOR_INLINE;
        }
        if (n.startsWith("refactor.rewrite")) {
            return CodeAction.CodeActionKind.REFACTOR_REWRITE;
        }
        if (n.startsWith("refactor")) {
            return CodeAction.CodeActionKind.REFACTOR;
        }
        if (n.startsWith("source.organizeimports")) {
            return CodeAction.CodeActionKind.SOURCE_ORGANIZE_IMPORTS;
        }
        if (n.startsWith("source.fixall")) {
            return CodeAction.CodeActionKind.SOURCE_FIX_ALL;
        }
        if (n.startsWith("source")) {
            return CodeAction.CodeActionKind.SOURCE;
        }
        return CodeAction.CodeActionKind.OTHER;
    }

    private static Map<String, Object> rangeToLsp(Range range) {
        return Map.of(
                "start", LspJsonRpcClient.position(range.start().line(), range.start().col()),
                "end", LspJsonRpcClient.position(range.end().line(), range.end().col())
        );
    }

    private static boolean rangeIntersectsDiagnostic(Range range, Diagnostic diagnostic) {
        if (range == null || diagnostic == null) {
            return false;
        }
        int rangeStart = positionKey(range.start().line(), range.start().col());
        int rangeEnd = positionKey(range.end().line(), range.end().col());
        int diagStart = positionKey(diagnostic.startLine(), diagnostic.startCol());
        int diagEnd = positionKey(diagnostic.endLine(), diagnostic.endCol());
        return diagStart <= rangeEnd && diagEnd >= rangeStart;
    }

    private static boolean containsPosition(Diagnostic d, int line, int col) {
        int pos = positionKey(line, col);
        return pos >= positionKey(d.startLine(), d.startCol()) && pos <= positionKey(d.endLine(), d.endCol());
    }

    private static int positionKey(int line, int col) {
        return Math.max(0, line) * 1_000_000 + Math.max(0, col);
    }

    public boolean isCallHierarchySupported() {
        return referencesSupported && documentSymbolSupported;
    }

    public List<CallHierarchyItem> prepareCallHierarchy(Path filePath, String text, int line, int character) {
        if (!canUseLsp(filePath)) {
            return Collections.emptyList();
        }
        String safe = text == null ? currentText(filePath) : text;
        Position pos = Position.of(line, character);

        if (documentSymbolSupported) {
            List<DocumentSymbol> path = pathTo(documentSymbols(filePath, safe), pos);
            for (DocumentSymbol s : path) {
                if (rangeContains(s.selectionRange(), pos)) {
                    return List.of(toItem(filePath, s));
                }
            }
        }

        if (definitionSupported) {
            for (Location def : definitions(filePath, safe, line, character)) {
                Path target = pathFromUri(def.uri());
                if (target == null || def.range() == null) {
                    continue;
                }
                CallHierarchyItem item = itemAt(target, currentText(target), def.range().start());
                if (item != null) {
                    return List.of(item);
                }
            }
        }

        IdentifierToken token = identifierAt(safe, line, character);
        if (token != null) {
            return List.of(new CallHierarchyItem(token.name(), "", SymbolKind.METHOD, filePath,
                    token.range(), token.range(), Map.of("emulated", Boolean.TRUE)));
        }

        if (documentSymbolSupported) {
            List<DocumentSymbol> path = pathTo(documentSymbols(filePath, safe), pos);
            DocumentSymbol enclosing = lastCallable(path);
            if (enclosing == null && !path.isEmpty()) {
                enclosing = path.get(path.size() - 1);
            }
            if (enclosing != null) {
                return List.of(toItem(filePath, enclosing));
            }
        }
        return Collections.emptyList();
    }

    public List<CallHierarchyCall> incomingCalls(CallHierarchyItem item) {
        if (item == null || item.filePath() == null || !canUseLsp(item.filePath())
                || !referencesSupported || !documentSymbolSupported) {
            return Collections.emptyList();
        }
        Path file = item.filePath();
        Position namePos = (item.selectionRange() != null ? item.selectionRange() : item.range()).start();
        List<Location> refs = callSiteReferences(file, currentText(file), namePos.line(), namePos.col());
        if (refs.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Path, List<DocumentSymbol>> symbolCache = new HashMap<>();
        Map<String, CallHierarchyItem> callerItems = new LinkedHashMap<>();
        Map<String, List<Range>> callerRanges = new LinkedHashMap<>();
        for (Location ref : refs) {
            Path refFile = pathFromUri(ref.uri());
            Range refRange = ref.range();
            if (refFile == null || refRange == null) {
                continue;
            }
            List<DocumentSymbol> symbols = symbolCache.computeIfAbsent(refFile,
                    f -> documentSymbols(f, currentText(f)));
            DocumentSymbol caller = enclosingCallable(symbols, refRange.start());
            if (caller != null && compare(caller.selectionRange().start(), refRange.start()) == 0) {
                continue;
            }
            String key = caller != null ? symbolKey(refFile, caller) : refFile.toString() + "#file";
            callerItems.putIfAbsent(key, caller != null ? toItem(refFile, caller) : fileItem(refFile));
            callerRanges.computeIfAbsent(key, k -> new ArrayList<>()).add(refRange);
        }
        return buildCalls(callerItems, callerRanges);
    }

    public List<CallHierarchyCall> outgoingCalls(CallHierarchyItem item) {
        if (item == null || item.filePath() == null || item.range() == null
                || !canUseLsp(item.filePath()) || !definitionSupported || !documentSymbolSupported) {
            return Collections.emptyList();
        }
        Path file = item.filePath();
        String text = currentText(file);
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        List<CallSite> sites = findCallSites(text, item.range(), item.selectionRange());
        if (sites.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Path, List<DocumentSymbol>> symbolCache = new HashMap<>();
        Map<String, CallHierarchyItem> targetItems = new LinkedHashMap<>();
        Map<String, List<Range>> targetRanges = new LinkedHashMap<>();
        for (CallSite site : sites) {
            List<Location> defs = definitions(file, text, site.pos().line(), site.pos().col());
            for (Location def : defs) {
                Path target = pathFromUri(def.uri());
                if (target == null || def.range() == null) {
                    continue;
                }
                List<DocumentSymbol> symbols = symbolCache.computeIfAbsent(target,
                        f -> documentSymbols(f, currentText(f)));
                DocumentSymbol sym = symbolAtName(symbols, def.range().start());
                if (sym == null || !CALLABLE_KINDS.contains(sym.kind())) {
                    continue;
                }
                String key = symbolKey(target, sym);
                targetItems.putIfAbsent(key, toItem(target, sym));
                targetRanges.computeIfAbsent(key, k -> new ArrayList<>()).add(site.range());
                break;
            }
        }
        return buildCalls(targetItems, targetRanges);
    }

    private CallHierarchyItem itemAt(Path file, String text, Position pos) {
        List<DocumentSymbol> path = pathTo(documentSymbols(file, text), pos);
        DocumentSymbol target = null;
        for (DocumentSymbol s : path) {
            if (rangeContains(s.selectionRange(), pos)) {
                target = s;
            }
        }
        if (target == null) {
            target = lastCallable(path);
        }
        if (target == null && !path.isEmpty()) {
            target = path.get(path.size() - 1);
        }
        return target == null ? null : toItem(file, target);
    }

    private static CallHierarchyItem toItem(Path file, DocumentSymbol symbol) {
        return new CallHierarchyItem(
                symbol.name(),
                symbol.detail(),
                symbol.kind(),
                file,
                symbol.range(),
                symbol.selectionRange(),
                Map.of("emulated", Boolean.TRUE)
        );
    }

    private static CallHierarchyItem fileItem(Path file) {
        Range zero = Range.of(0, 0, 0, 0);
        Path name = file.getFileName();
        return new CallHierarchyItem(name == null ? file.toString() : name.toString(),
                "top-level", SymbolKind.FILE, file, zero, zero, Map.of("emulated", Boolean.TRUE));
    }

    private IdentifierToken identifierAt(String text, int line, int character) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int[] lineStarts = lineStartOffsets(text);
        int offset = offsetOf(lineStarts, Position.of(line, character));
        if (offset < 0 || offset > text.length()) {
            return null;
        }
        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        int end = offset;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        if (start >= end || !Character.isJavaIdentifierStart(text.charAt(start))) {
            return null;
        }
        String name = text.substring(start, end);
        if (CSHARP_KEYWORDS.contains(name)) {
            return null;
        }
        return new IdentifierToken(name, new Range(positionOf(lineStarts, start), positionOf(lineStarts, end)));
    }

    private static List<CallHierarchyCall> buildCalls(Map<String, CallHierarchyItem> items,
                                                      Map<String, List<Range>> ranges) {
        List<CallHierarchyCall> calls = new ArrayList<>(items.size());
        for (Map.Entry<String, CallHierarchyItem> entry : items.entrySet()) {
            calls.add(new CallHierarchyCall(entry.getValue(), ranges.getOrDefault(entry.getKey(), List.of())));
        }
        return calls;
    }

    private static String symbolKey(Path file, DocumentSymbol symbol) {
        Position start = symbol.selectionRange().start();
        return file.toString() + '#' + start.line() + ':' + start.col() + '#' + symbol.name();
    }

    private static List<DocumentSymbol> pathTo(List<DocumentSymbol> symbols, Position pos) {
        if (symbols == null) {
            return List.of();
        }
        for (DocumentSymbol s : symbols) {
            if (rangeContains(s.range(), pos)) {
                List<DocumentSymbol> path = new ArrayList<>();
                path.add(s);
                path.addAll(pathTo(s.children(), pos));
                return path;
            }
        }
        return List.of();
    }

    private static DocumentSymbol enclosingCallable(List<DocumentSymbol> symbols, Position pos) {
        List<DocumentSymbol> path = pathTo(symbols, pos);
        DocumentSymbol callable = lastCallable(path);
        if (callable != null) {
            return callable;
        }
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }

    private static DocumentSymbol lastCallable(List<DocumentSymbol> path) {
        DocumentSymbol callable = null;
        for (DocumentSymbol s : path) {
            if (CALLABLE_KINDS.contains(s.kind())) {
                callable = s;
            }
        }
        return callable;
    }

    private static DocumentSymbol symbolAtName(List<DocumentSymbol> symbols, Position pos) {
        List<DocumentSymbol> path = pathTo(symbols, pos);
        for (int i = path.size() - 1; i >= 0; i--) {
            if (rangeContains(path.get(i).selectionRange(), pos)) {
                return path.get(i);
            }
        }
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }

    private List<CallSite> findCallSites(String text, Range bodyRange, Range selectionRange) {
        int[] lineStarts = lineStartOffsets(text);
        int end = Math.min(offsetOf(lineStarts, bodyRange.end()), text.length());
        int start = offsetOf(lineStarts, (selectionRange != null ? selectionRange : bodyRange).end());
        int brace = text.indexOf('{', start);
        if (brace >= 0 && brace < end) {
            start = brace + 1;
        }
        List<CallSite> sites = new ArrayList<>();
        int i = Math.max(0, start);
        while (i < end) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < end && text.charAt(i + 1) == '/') {
                int nl = text.indexOf('\n', i);
                i = nl < 0 ? end : nl;
            } else if (c == '/' && i + 1 < end && text.charAt(i + 1) == '*') {
                int e = text.indexOf("*/", i + 2);
                i = e < 0 ? end : e + 2;
            } else if (c == '"') {
                i = skipQuoted(text, i, end, '"');
            } else if (c == '\'') {
                i = skipQuoted(text, i, end, '\'');
            } else if (Character.isJavaIdentifierStart(c)) {
                int s = i++;
                while (i < end && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                int j = i;
                while (j < end && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                if (j < end && text.charAt(j) == '(' && !CSHARP_KEYWORDS.contains(text.substring(s, i))) {
                    sites.add(new CallSite(positionOf(lineStarts, s),
                            new Range(positionOf(lineStarts, s), positionOf(lineStarts, i))));
                }
            } else {
                i++;
            }
        }
        return sites;
    }

    private static int skipQuoted(String text, int i, int end, char quote) {
        i++;
        while (i < end) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote || c == '\n') {
                return i + 1;
            }
            i++;
        }
        return end;
    }

    private static int[] lineStartOffsets(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = starts.get(i);
        }
        return arr;
    }

    private static int offsetOf(int[] lineStarts, Position pos) {
        int line = Math.min(Math.max(pos.line(), 0), lineStarts.length - 1);
        return lineStarts[line] + Math.max(pos.col(), 0);
    }

    private static Position positionOf(int[] lineStarts, int offset) {
        int lo = 0;
        int hi = lineStarts.length - 1;
        int line = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStarts[mid] <= offset) {
                line = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return Position.of(line, offset - lineStarts[line]);
    }

    private static boolean rangeContains(Range range, Position pos) {
        return range != null && pos != null
                && compare(range.start(), pos) <= 0 && compare(pos, range.end()) <= 0;
    }

    private static int compare(Position a, Position b) {
        return a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.col(), b.col());
    }

    private static Path pathFromUri(String uri) {
        try {
            return Path.of(URI.create(uri)).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentText(Path file) {
        if (file == null) {
            return "";
        }
        String opened = openedContent.get(toUri(file));
        if (opened != null) {
            return opened;
        }
        String disk = safeRead(file);
        return disk == null ? "" : disk;
    }

    private record CallSite(Position pos, Range range) {
    }

    private record IdentifierToken(String name, Range range) {
    }

    private void doStart() {
        if (projectPath == null) {
            recordError("Projeto não vinculado ao LSP.");
            return;
        }
        if (sdkService == null) {
            recordError("Toolchain .NET não vinculada ao LSP.");
            return;
        }
        Optional<Path> omnisharp = sdkService.getOmniSharpPath();
        if (omnisharp.isEmpty()) {
            recordError("OmniSharp não encontrado na toolchain .NET do plugin.");
            return;
        }
        try {
            state = State.STARTING;
            List<String> command = new ArrayList<>();
            command.add(omnisharp.get().toAbsolutePath().toString());
            command.add("-lsp");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(projectPath.toFile());
            builder.redirectErrorStream(false);

            Optional<Path> dotnetRoot = sdkService.getDotnetRoot(projectPath);
            if (dotnetRoot.isPresent()) {
                String root = dotnetRoot.get().toAbsolutePath().toString();
                builder.environment().put("DOTNET_ROOT", root);
                builder.environment().put("DOTNET_ROLL_FORWARD", "LatestMajor");
                String pathSep = System.getProperty("path.separator", ":");
                String currentPath = builder.environment().getOrDefault("PATH", System.getenv("PATH"));
                builder.environment().put("PATH", currentPath == null ? root : root + pathSep + currentPath);
            }

            process = builder.start();
            stderrPump = executor.submit(() -> pumpStderr(process.getErrorStream()));
            client = new LspJsonRpcClient(process.getInputStream(), process.getOutputStream());
            registerNotificationHandlers();
            watcher = executor.submit(this::watchProcess);
            performHandshake();
            documentVersions.clear();
            openedContent.clear();
            diagnosticsByUri.clear();
            lastError = null;
            state = State.READY;
            log.info("OmniSharp iniciado em {}", projectPath);
        } catch (Exception e) {
            recordError("Falha ao iniciar OmniSharp: " + safeMessage(e));
            doStop();
        }
    }

    private void doStop() {
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
            process = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
        if (stderrPump != null) {
            stderrPump.cancel(true);
            stderrPump = null;
        }
        if (watcher != null) {
            watcher.cancel(true);
            watcher = null;
        }
        documentVersions.clear();
        openedContent.clear();
        diagnosticsByUri.clear();
        definitionSupported = false;
        referencesSupported = false;
        documentSymbolSupported = false;
        renameSupported = false;
        formattingSupported = false;
        callHierarchySupported = false;
        codeActionSupported = false;
    }

    private void watchProcess() {
        Process p = process;
        if (p == null) {
            return;
        }
        try {
            int exit = p.waitFor();
            synchronized (processLock) {
                if (process == p) {
                    doStop();
                    state = intentionalStop.get() ? State.STOPPED : State.ERROR;
                    if (!intentionalStop.get()) {
                        lastError = "OmniSharp terminou inesperadamente (código " + exit + ").";
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void performHandshake() throws Exception {
        Map<String, Object> initParams = new LinkedHashMap<>();
        initParams.put("processId", ProcessHandle.current().pid());
        initParams.put("rootUri", toUri(projectPath));
        initParams.put("rootPath", projectPath.toAbsolutePath().toString());
        initParams.put("capabilities", clientCapabilities());
        initParams.put("clientInfo", Map.of("name", "DotnetOrionSupport", "version", "1.0.0"));
        initParams.put("trace", "off");

        initParams.put("initializationOptions", Map.of(
                "RoslynExtensionsOptions", Map.of(
                        "enableDecompilationSupport", true,
                        "enableImportCompletion", true,
                        "enableAnalyzersSupport", true
                )
        ));
        JsonNode result = client.sendRequest("initialize", initParams).get(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        JsonNode capabilities = result == null ? null : result.get("capabilities");
        definitionSupported = supportsProvider(node(capabilities, "definitionProvider"));
        referencesSupported = supportsProvider(node(capabilities, "referencesProvider"));
        documentSymbolSupported = supportsProvider(node(capabilities, "documentSymbolProvider"));
        renameSupported = supportsProvider(node(capabilities, "renameProvider"));
        formattingSupported = supportsProvider(node(capabilities, "documentFormattingProvider"));
        callHierarchySupported = supportsProvider(node(capabilities, "callHierarchyProvider"));
        codeActionSupported = supportsProvider(node(capabilities, "codeActionProvider"));
        client.sendNotification("initialized", Map.of());
    }

    private static JsonNode node(JsonNode capabilities, String field) {
        return capabilities == null ? null : capabilities.get(field);
    }

    private Map<String, Object> clientCapabilities() {
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("synchronization", Map.of("dynamicRegistration", false));
        textDocument.put("completion", Map.of("completionItem",
                Map.of("snippetSupport", true, "documentationFormat", List.of("plaintext"))));
        textDocument.put("hover", Map.of("contentFormat", List.of("markdown", "plaintext")));
        textDocument.put("definition", Map.of("dynamicRegistration", false, "linkSupport", true));
        textDocument.put("references", Map.of("dynamicRegistration", false));
        textDocument.put("documentSymbol", Map.of("dynamicRegistration", false, "hierarchicalDocumentSymbolSupport", true));
        textDocument.put("rename", Map.of("dynamicRegistration", false, "prepareSupport", false));
        textDocument.put("formatting", Map.of("dynamicRegistration", false));
        textDocument.put("codeAction", Map.of("dynamicRegistration", false));
        textDocument.put("callHierarchy", Map.of("dynamicRegistration", false));
        textDocument.put("publishDiagnostics", Map.of("relatedInformation", false));
        Map<String, Object> workspace = Map.of("symbol", Map.of("dynamicRegistration", false));
        return Map.of("textDocument", textDocument, "workspace", workspace);
    }

    private void registerNotificationHandlers() {
        client.onNotification("textDocument/publishDiagnostics", params -> {
            if (params == null) {
                return;
            }
            JsonNode uriNode = params.get("uri");
            JsonNode diagnosticsNode = params.get("diagnostics");
            if (uriNode == null || diagnosticsNode == null || !diagnosticsNode.isArray()) {
                return;
            }
            List<JsonNode> diagnostics = new ArrayList<>(diagnosticsNode.size());
            diagnosticsNode.forEach(diagnostics::add);
            String uri = uriNode.asText();

            diagnosticsByUri.put(normalizeUriKey(uri), diagnostics);
            notifyDiagnosticsPublished(uri);
        });
        client.onNotification("window/logMessage", params -> {
        });
        client.onNotification("window/showMessage", params -> {
        });
    }

    private synchronized void syncDocument(String uri, Path filePath, String text) {
        String safeText = text == null ? "" : text;
        String previous = openedContent.get(uri);
        if (Objects.equals(previous, safeText)) {
            return;
        }
        if (previous == null) {
            sendDidOpen(uri, safeText);
        } else {
            sendDidChangeFull(uri, safeText);
        }
        openedContent.put(uri, safeText);
        diagnosticsByUri.remove(normalizeUriKey(uri));
    }

    private static String normalizeUriKey(String uri) {
        try {
            String s = Path.of(URI.create(uri)).toAbsolutePath().normalize().toString();
            return isWindowsOs() ? s.toLowerCase(Locale.ROOT) : s;
        } catch (Exception e) {
            return isWindowsOs() ? uri.toLowerCase(Locale.ROOT) : uri;
        }
    }

    private static boolean isWindowsOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private void sendDidOpen(String uri, String text) {
        int version = documentVersions.computeIfAbsent(uri, ignored -> new AtomicInteger()).incrementAndGet();
        client.sendNotification("textDocument/didOpen", Map.of(
                "textDocument", Map.of(
                        "uri", uri,
                        "languageId", "csharp",
                        "version", version,
                        "text", text == null ? "" : text
                )
        ));
    }

    private void sendDidChangeFull(String uri, String text) {
        int version = documentVersions.computeIfAbsent(uri, ignored -> new AtomicInteger()).incrementAndGet();
        client.sendNotification("textDocument/didChange", Map.of(
                "textDocument", Map.of("uri", uri, "version", version),
                "contentChanges", List.of(Map.of("text", text == null ? "" : text))
        ));
    }

    private List<AutoCompleteItem> parseCompletionResult(JsonNode result) {
        if (result == null || result.isNull()) {
            return Collections.emptyList();
        }
        JsonNode items = result.isArray() ? result : result.get("items");
        if (items == null || !items.isArray()) {
            return Collections.emptyList();
        }
        List<JsonNode> sorted = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            if (!textOrEmpty(item.get("label")).isBlank()) {
                sorted.add(item);
            }
        }
        sorted.sort(java.util.Comparator.comparing(DotnetLspService::completionSortKey));
        List<AutoCompleteItem> out = new ArrayList<>(sorted.size());
        for (JsonNode item : sorted) {
            String label = textOrEmpty(item.get("label"));
            String insertText = textOrEmpty(item.path("textEdit").get("newText") == null
                    ? item.get("insertText") : item.path("textEdit").get("newText"));
            if (insertText.isBlank()) {
                insertText = textOrEmpty(item.get("insertText"));
            }
            if (insertText.isBlank()) {
                insertText = label;
            }
            String detail = textOrEmpty(item.get("detail"));
            String description = extractDocumentation(item.get("documentation"));
            AutoCompleteItem.Kind kind = item.path("insertTextFormat").asInt(1) == 2
                    ? AutoCompleteItem.Kind.SNIPPET
                    : AutoCompleteItem.Kind.TEXT;
            out.add(new AutoCompleteItem(insertText, label, nullIfBlank(detail),
                    nullIfBlank(description), null, kind));
        }
        return out;
    }

    private static String completionSortKey(JsonNode item) {
        String sortText = textOrEmpty(item.get("sortText"));
        return sortText.isBlank() ? textOrEmpty(item.get("label")) : sortText;
    }

    private HoverInfo parseHoverResult(JsonNode result) {
        if (result == null || result.isNull()) {
            return null;
        }
        JsonNode contents = result.get("contents");
        String text = extractHoverText(contents);
        if (text.isBlank()) {
            return null;
        }
        return new HoverInfo(text);
    }

    private static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("\r\n", "<br>")
                .replace("\n", "<br>");
    }

    private static String extractHoverText(JsonNode contents) {
        if (contents == null || contents.isNull()) {
            return "";
        }
        if (contents.isTextual()) {
            return contents.asText();
        }
        if (contents.isObject()) {
            return textOrEmpty(contents.get("value"));
        }
        if (contents.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : contents) {
                String part = item.isObject() ? textOrEmpty(item.get("value")) : item.asText("");
                if (!part.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(part);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private List<Location> parseLocations(JsonNode result) {
        if (result == null || result.isNull()) {
            return Collections.emptyList();
        }
        List<Location> out = new ArrayList<>();
        if (result.isArray()) {
            for (JsonNode node : result) {
                Location location = parseLocation(node);
                if (location != null) {
                    out.add(location);
                }
            }
        } else {
            Location location = parseLocation(result);
            if (location != null) {
                out.add(location);
            }
        }
        return out;
    }

    private Location parseLocation(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String uri = textOrEmpty(node.get("uri"));
        JsonNode rangeNode = node.has("targetRange") ? node.get("targetRange") : node.get("range");
        Range range = parseRange(rangeNode);
        if (uri.isBlank() || range == null) {
            return null;
        }
        return new Location(uri, range);
    }

    private List<DocumentSymbol> parseDocumentSymbols(JsonNode result) {
        if (result == null || !result.isArray() || result.isEmpty()) {
            return Collections.emptyList();
        }
        if (isFlatSymbolInformation(result)) {
            return buildHierarchyFromFlat(result);
        }
        List<DocumentSymbol> symbols = new ArrayList<>();
        for (JsonNode node : result) {
            DocumentSymbol symbol = parseDocumentSymbol(node);
            if (symbol != null) {
                symbols.add(symbol);
            }
        }
        return symbols;
    }

    private static boolean isFlatSymbolInformation(JsonNode result) {
        for (JsonNode node : result) {
            if (node == null || node.isNull()) {
                continue;
            }
            boolean hierarchical = node.has("children") || node.has("selectionRange") || node.has("range");
            return !hierarchical && node.has("location");
        }
        return false;
    }

    private List<DocumentSymbol> buildHierarchyFromFlat(JsonNode result) {
        List<DocumentSymbol> leaves = new ArrayList<>();
        for (JsonNode node : result) {
            DocumentSymbol leaf = parseDocumentSymbol(node);
            if (leaf != null) {
                leaves.add(leaf);
            }
        }
        leaves.sort((a, b) -> {
            int byStart = compare(a.range().start(), b.range().start());
            return byStart != 0 ? byStart : compare(b.range().end(), a.range().end());
        });
        List<DocumentSymbol> roots = new ArrayList<>();
        Deque<DocumentSymbol> stack = new ArrayDeque<>();
        for (DocumentSymbol symbol : leaves) {
            while (!stack.isEmpty() && !containsSymbol(stack.peek(), symbol)) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(symbol);
            } else {
                stack.peek().children().add(symbol);
            }
            stack.push(symbol);
        }
        return roots;
    }

    private static boolean containsSymbol(DocumentSymbol outer, DocumentSymbol inner) {
        return compare(outer.range().start(), inner.range().start()) <= 0
                && compare(inner.range().end(), outer.range().end()) <= 0;
    }

    private DocumentSymbol parseDocumentSymbol(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String name = textOrEmpty(node.get("name"));
        if (name.isBlank()) {
            return null;
        }

        JsonNode rangeNode = node.has("range") ? node.get("range")
                : node.path("location").get("range");
        Range range = parseRange(rangeNode);
        Range selectionRange = node.has("selectionRange") ? parseRange(node.get("selectionRange")) : range;
        if (range == null) {
            range = Range.of(0, 0, 0, 0);
        }
        if (selectionRange == null) {
            selectionRange = range;
        }
        SymbolKind kind = mapSymbolKind(node.path("kind").asInt(0));
        List<DocumentSymbol> children = new ArrayList<>();
        JsonNode childrenNode = node.get("children");
        if (childrenNode != null && childrenNode.isArray()) {
            for (JsonNode child : childrenNode) {
                DocumentSymbol parsed = parseDocumentSymbol(child);
                if (parsed != null) {
                    children.add(parsed);
                }
            }
        }
        return new DocumentSymbol(name, textOrEmpty(node.get("detail")), kind, range, selectionRange, children);
    }

    private List<TextEdit> parseRenameEditsForUri(JsonNode result, String uri) {
        DotnetWorkspaceEdit workspaceEdit = parseWorkspaceEdit(result);
        Path file = pathFromUri(uri);
        return file == null ? Collections.emptyList() : workspaceEdit.editsFor(file);
    }

    private DotnetWorkspaceEdit parseWorkspaceEdit(JsonNode result) {
        if (result == null || result.isNull()) {
            return new DotnetWorkspaceEdit(Map.of());
        }
        Map<Path, List<TextEdit>> out = new LinkedHashMap<>();
        JsonNode changes = result.get("changes");
        if (changes != null && changes.isObject()) {
            changes.fields().forEachRemaining(entry -> addWorkspaceEdits(out, entry.getKey(), entry.getValue()));
        }
        JsonNode documentChanges = result.get("documentChanges");
        if (documentChanges != null && documentChanges.isArray()) {
            for (JsonNode change : documentChanges) {
                if (change == null || change.isNull()) {
                    continue;
                }
                String changeUri = textOrEmpty(change.path("textDocument").get("uri"));
                JsonNode edits = change.get("edits");
                addWorkspaceEdits(out, changeUri, edits);
            }
        }
        return new DotnetWorkspaceEdit(out);
    }

    private static void addWorkspaceEdits(Map<Path, List<TextEdit>> out, String uri, JsonNode editsNode) {
        Path file = pathFromUri(uri);
        if (file == null) {
            return;
        }
        List<TextEdit> edits = parseTextEditsStatic(editsNode);
        if (edits.isEmpty()) {
            return;
        }
        out.computeIfAbsent(file, ignored -> new ArrayList<>()).addAll(edits);
    }

    private static List<TextEdit> parseTextEditsStatic(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<TextEdit> out = new ArrayList<>(node.size());
        for (JsonNode editNode : node) {
            Range range = parseRange(editNode.get("range"));
            if (range != null) {
                out.add(new TextEdit(range, textOrEmpty(editNode.get("newText"))));
            }
        }
        return out;
    }

    private List<TextEdit> parseTextEdits(JsonNode node) {
        return parseTextEditsStatic(node);
    }

    private Diagnostic parseDiagnostic(JsonNode node) {
        JsonNode range = node.get("range");
        if (range == null) {
            return null;
        }
        JsonNode start = range.get("start");
        JsonNode end = range.get("end");
        if (start == null || end == null) {
            return null;
        }
        String source = textOrEmpty(node.get("source"));
        return new Diagnostic(
                start.path("line").asInt(0),
                start.path("character").asInt(0),
                end.path("line").asInt(start.path("line").asInt(0)),
                end.path("character").asInt(start.path("character").asInt(0)),
                mapSeverity(node.path("severity").asInt(1)),
                textOrEmpty(node.get("message")),
                source.isBlank() ? "omnisharp" : source,
                null
        );
    }

    private static Range parseRange(JsonNode rangeNode) {
        if (rangeNode == null || rangeNode.isNull()) {
            return null;
        }
        JsonNode start = rangeNode.get("start");
        JsonNode end = rangeNode.get("end");
        if (start == null || end == null) {
            return null;
        }
        return new Range(
                new Position(start.path("line").asInt(0), start.path("character").asInt(0)),
                new Position(end.path("line").asInt(0), end.path("character").asInt(0))
        );
    }

    private static String applyEdits(String text, List<TextEdit> edits) {
        String[] lines = text.split("\n", -1);
        int[] lineStart = new int[lines.length + 1];
        int acc = 0;
        for (int i = 0; i < lines.length; i++) {
            lineStart[i] = acc;
            acc += lines[i].length() + 1;
        }
        lineStart[lines.length] = acc;

        List<TextEdit> ordered = new ArrayList<>(edits);
        ordered.sort((a, b) -> {
            int cmp = Integer.compare(b.range().start().line(), a.range().start().line());
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(b.range().start().col(), a.range().start().col());
        });

        StringBuilder sb = new StringBuilder(text);
        for (TextEdit edit : ordered) {
            int startOffset = offsetOf(lineStart, lines, edit.range().start());
            int endOffset = offsetOf(lineStart, lines, edit.range().end());
            if (startOffset < 0 || endOffset < startOffset || endOffset > sb.length()) {
                continue;
            }
            sb.replace(startOffset, endOffset, edit.newText() == null ? "" : edit.newText());
        }
        return sb.toString();
    }

    private static int offsetOf(int[] lineStart, String[] lines, Position position) {
        int line = position.line();
        if (line < 0 || line >= lines.length) {
            return -1;
        }
        return lineStart[line] + Math.min(position.col(), lines[line].length());
    }

    private boolean canUseLsp(Path filePath) {
        return isRunning() && client != null && filePath != null;
    }

    private void notifyDiagnosticsPublished(String uri) {
        List<Consumer<String>> listeners;
        synchronized (diagnosticsPublishedListeners) {
            listeners = new ArrayList<>(diagnosticsPublishedListeners);
        }
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(uri);
            } catch (Exception ignored) {
            }
        }
    }

    private void pumpStderr(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[omnisharp] {}", line);
            }
        } catch (Exception ignored) {
        }
    }

    private void recordError(String message) {
        this.lastError = message;
        this.state = State.ERROR;
        log.warn("LSP .NET: {}", message);
    }

    private static boolean supportsProvider(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.isObject();
    }

    private static DiagnosticSeverity mapSeverity(int severity) {
        return switch (severity) {
            case 1 -> DiagnosticSeverity.ERROR;
            case 2 -> DiagnosticSeverity.WARNING;
            case 3 -> DiagnosticSeverity.INFO;
            default -> DiagnosticSeverity.HINT;
        };
    }

    private static SymbolKind mapSymbolKind(int kind) {
        return switch (kind) {
            case 2 -> SymbolKind.MODULE;
            case 3 -> SymbolKind.NAMESPACE;
            case 4 -> SymbolKind.PACKAGE;
            case 5 -> SymbolKind.CLASS;
            case 6 -> SymbolKind.METHOD;
            case 7 -> SymbolKind.PROPERTY;
            case 8 -> SymbolKind.FIELD;
            case 9 -> SymbolKind.CONSTRUCTOR;
            case 10 -> SymbolKind.ENUM;
            case 11 -> SymbolKind.INTERFACE;
            case 12 -> SymbolKind.FUNCTION;
            case 13 -> SymbolKind.VARIABLE;
            case 14 -> SymbolKind.CONSTANT;
            case 22 -> SymbolKind.ENUM_MEMBER;
            case 23 -> SymbolKind.STRUCT;
            case 24 -> SymbolKind.EVENT;
            case 25 -> SymbolKind.OPERATOR;
            case 26 -> SymbolKind.TYPE_PARAMETER;
            default -> SymbolKind.OTHER;
        };
    }

    private static String extractDocumentation(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return textOrEmpty(node.get("value"));
    }

    private static String textOrEmpty(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String toUri(Path filePath) {
        return filePath.toAbsolutePath().normalize().toUri().toString();
    }

    private static String safeMessage(Throwable t) {
        return t == null ? "" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicLong count = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + count.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }
}
