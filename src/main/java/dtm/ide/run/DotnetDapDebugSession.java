package dtm.ide.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.ide.api.project.editor.BreakpointIde;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
final class DotnetDapDebugSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final java.util.Set<String> LOGGED_COMMANDS = java.util.Set.of(
            "initialize", "attach", "launch", "setBreakpoints", "configurationDone", "disconnect");

    private static final java.util.Set<String> LOGGED_EVENTS = java.util.Set.of(
            "initialized", "stopped", "continued", "terminated", "exited", "breakpoint");

    private final Path netcoredbg;
    private final Path dotnet;
    private final Path program;
    private final Path cwd;
    private final List<String> programArgs;
    private final DotnetDebugView view;
    private final OutputStream programOut;
    private final DotnetBuild.DeferredOutputStream debuggeeStdin;

    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<Integer, String> pendingRequests = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<JsonNode>> pendingResults = new ConcurrentHashMap<>();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean initializedEventSeen = new AtomicBoolean(false);
    private final AtomicBoolean debugStartAccepted = new AtomicBoolean(false);
    private final AtomicBoolean configurationSent = new AtomicBoolean(false);

    private final Map<Path, NavigableSet<Integer>> liveBreakpoints = new ConcurrentHashMap<>();

    private final boolean useAttach = true;
    private volatile Process process;
    private volatile Process debuggee;
    private volatile OutputStream dapIn;
    private final Path startupHook;
    private volatile Path gateFile;
    private volatile int threadId;
    private volatile int frameId = -1;
    private final List<DebugFrame> callStack = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Map<String, String> launchEnv = Map.of();
    private final AtomicLong stoppedTicket = new AtomicLong();
    private volatile List<String> exceptionBreakpointFilters = List.of();

    DotnetDapDebugSession(Path netcoredbg, Path dotnet, Path program, Path cwd, List<String> programArgs,
                          List<RunBreakpointData> breakpoints, DotnetDebugView view, OutputStream programOut,
                          DotnetBuild.DeferredOutputStream debuggeeStdin, Path startupHook) {
        this.netcoredbg = netcoredbg;
        this.dotnet = dotnet;
        this.program = program;
        this.cwd = cwd;
        this.programArgs = programArgs == null ? List.of() : programArgs;
        this.view = view;
        this.programOut = programOut;
        this.debuggeeStdin = debuggeeStdin;
        this.startupHook = startupHook;
        seedBreakpoints(breakpoints);
    }

    void setLaunchEnv(Map<String, String> env) {
        this.launchEnv = env == null ? Map.of() : env;
    }

    private void seedBreakpoints(List<RunBreakpointData> breakpoints) {
        if (breakpoints == null) {
            return;
        }
        for (RunBreakpointData data : breakpoints) {
            Path file = data.getFile();
            List<BreakpointIde> bps = data.getBreakpoints();
            if (file == null || bps == null) {
                continue;
            }
            NavigableSet<Integer> lines = liveBreakpoints.computeIfAbsent(
                    file.toAbsolutePath().normalize(), k -> new TreeSet<>());
            for (BreakpointIde bp : bps) {
                if (bp != null && bp.active() && bp.line() >= 0) {
                    lines.add(bp.line());
                }
            }
        }
    }

    void start() throws IOException {
        ProcessBuilder adapterBuilder = new ProcessBuilder(netcoredbg.toString(), "--interpreter=vscode").directory(cwd.toFile());
        applyDotnetEnv(adapterBuilder);
        process = adapterBuilder.start();
        dapIn = process.getOutputStream();
        Thread reader = new Thread(this::readLoop, "dotnet-dap-reader");
        reader.setDaemon(true);
        reader.start();
        Thread errReader = new Thread(() -> pumpToProgramOut(process.getErrorStream()), "dotnet-dap-stderr");
        errReader.setDaemon(true);
        errReader.start();
        process.onExit().thenAccept(p -> dlog("[debug] netcoredbg encerrou (codigo " + p.exitValue() + ")."));

        if (useAttach) {
            startInferior();
        }

        ObjectNode init = MAPPER.createObjectNode();
        init.put("adapterID", "coreclr");
        init.put("clientID", "orion-dotnet");
        init.put("linesStartAt1", true);
        init.put("columnsStartAt1", true);
        init.put("pathFormat", "path");
        init.put("supportsVariableType", true);
        init.put("supportsRunInTerminalRequest", false);
        try {
            JsonNode capabilities = sendRequestForResult("initialize", init).get(5, TimeUnit.SECONDS);
            captureExceptionBreakpointFilters(capabilities);
        } catch (Exception e) {
            throw new IOException("Falha ao inicializar netcoredbg: " + e.getMessage(), e);
        }
        if (useAttach) {
            sendAttach();
        } else {
            sendLaunch();
        }
    }

    void awaitTermination() throws InterruptedException {
        terminated.await();
        Process p = process;
        if (p != null && !p.isAlive()) {
            dlog("[debug] netcoredbg encerrou (codigo " + p.exitValue() + ").");
        }
        Process d = debuggee;
        if (d != null && !d.isAlive()) {
            dlog("[debug] programa encerrou (codigo " + d.exitValue() + ").");
        }
    }

    void resume() {
        sendThreadRequest("continue");
    }

    void stepOver() {
        sendThreadRequest("next");
    }

    void stepInto() {
        sendThreadRequest("stepIn");
    }

    void stepOut() {
        sendThreadRequest("stepOut");
    }

    void pause() {
        sendThreadRequest("pause");
    }

    void restart() {
        sendRequest("restart", MAPPER.createObjectNode());
    }

    void terminate() {
        if (closed.getAndSet(true)) {
            return;
        }
        try {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("terminateDebuggee", true);
            sendRequest("disconnect", args);
        } catch (Exception ignored) {
        }
        Process d = debuggee;
        if (d != null) {
            try {
                d.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Exception ignored) {
            }
            d.destroyForcibly();
        }
        Process p = process;
        if (p != null) {
            try {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Exception ignored) {
            }
            p.destroyForcibly();
        }
        cleanupGate();
        terminated.countDown();
    }

    boolean isStopped() {
        return frameId >= 0;
    }

    DebugVar evaluate(String expression) {
        if (!ensureStoppedFrame()) {
            return null;
        }
        int frame = frameId;
        if (expression == null || expression.isBlank() || frame < 0) {
            return null;
        }
        ObjectNode args = MAPPER.createObjectNode();
        args.put("expression", expression);
        args.put("frameId", frame);
        args.put("context", "watch");
        try {
            JsonNode body = sendRequestForResult("evaluate", args).get(1500, TimeUnit.MILLISECONDS);
            String result = body.path("result").asText(null);
            if (result == null) {
                return null;
            }
            return new DebugVar(expression, result, body.path("type").asText(""),
                    body.path("variablesReference").asInt(0));
        } catch (Exception e) {
            return null;
        }
    }

    List<DebugCompletion> completions(String text, int column) {
        List<DebugCompletion> result = new ArrayList<>();
        if (!ensureStoppedFrame()) {
            return result;
        }
        int frame = frameId;
        if (text == null || frame < 0) {
            return result;
        }
        ObjectNode args = MAPPER.createObjectNode();
        args.put("frameId", frame);
        args.put("text", text);
        args.put("column", Math.max(1, column));
        args.put("line", 1);
        try {
            JsonNode body = sendRequestForResult("completions", args).get(1200, TimeUnit.MILLISECONDS);
            for (JsonNode target : body.path("targets")) {
                String label = target.path("label").asText("");
                if (label.isBlank()) {
                    continue;
                }
                String insertText = target.path("text").asText(label);
                String detail = firstNonBlank(target.path("detail").asText(""), target.path("type").asText(""));
                String kind = target.path("type").asText("");
                result.add(new DebugCompletion(label, insertText, detail, kind,
                        target.path("start").asInt(0), target.path("length").asInt(0)));
            }
        } catch (Exception e) {
            log.debug("Falha ao completar expressao de debug: {}", e.getMessage());
        }
        return result;
    }

    List<DebugScope> scopes() {
        if (!ensureStoppedFrame()) {
            return List.of();
        }
        int frame = frameId;
        if (frame < 0) {
            return List.of();
        }
        List<DebugScope> result = scopesForFrame(frame);
        if (!result.isEmpty()) {
            return result;
        }
        if (refreshStoppedFrame()) {
            int refreshedFrame = frameId;
            if (refreshedFrame >= 0) {
                result = scopesForFrame(refreshedFrame);
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        for (DebugFrame candidate : callStack) {
            if (candidate == null || candidate.id() < 0 || candidate.id() == frameId || !candidate.userCode()) {
                continue;
            }
            result = scopesForFrame(candidate.id());
            if (!result.isEmpty()) {
                frameId = candidate.id();
                return result;
            }
        }
        for (DebugFrame candidate : callStack) {
            if (candidate == null || candidate.id() < 0 || candidate.id() == frameId) {
                continue;
            }
            result = scopesForFrame(candidate.id());
            if (!result.isEmpty()) {
                frameId = candidate.id();
                return result;
            }
        }
        return List.of();
    }

    private List<DebugScope> scopesForFrame(int frame) {
        List<DebugScope> result = new ArrayList<>();
        if (frame < 0) {
            return result;
        }
        try {
            ObjectNode scopeArgs = MAPPER.createObjectNode();
            scopeArgs.put("frameId", frame);
            JsonNode body = sendRequestForResult("scopes", scopeArgs).get(1500, TimeUnit.MILLISECONDS);
            for (JsonNode scope : body.path("scopes")) {
                int ref = scope.path("variablesReference").asInt(0);
                String name = scope.path("name").asText("");
                String lower = name.toLowerCase(Locale.ROOT);
                if (ref <= 0 || lower.contains("register") || lower.contains("static") || lower.contains("global")) {
                    continue;
                }
                result.add(new DebugScope(name, ref));
            }
        } catch (Exception e) {
            log.debug("Falha ao ler escopos de debug: {}", e.getMessage());
        }
        return result;
    }

    List<DebugVar> variables(int variablesReference) {
        List<DebugVar> result = new ArrayList<>();
        if (variablesReference <= 0) {
            return result;
        }
        try {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("variablesReference", variablesReference);
            JsonNode body = sendRequestForResult("variables", args).get(1500, TimeUnit.MILLISECONDS);
            for (JsonNode v : body.path("variables")) {
                result.add(new DebugVar(
                        v.path("name").asText(""),
                        v.path("value").asText(""),
                        v.path("type").asText(""),
                        v.path("variablesReference").asInt(0)));
            }
        } catch (Exception e) {
            log.debug("Falha ao expandir variavel de debug: {}", e.getMessage());
        }
        return result;
    }

    List<DebugFrame> callStack() {
        ensureStoppedFrame();
        return new ArrayList<>(callStack);
    }

    void selectFrame(int selectedFrameId) {
        if (selectedFrameId >= 0) {
            frameId = selectedFrameId;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private static String firstNonBlank(String first, String second, String third) {
        String value = firstNonBlank(first, second);
        return value == null || value.isBlank() ? (third == null ? "" : third) : value;
    }

    private static String firstNonBlank(String first, String second, String third, String fourth) {
        String value = firstNonBlank(first, second, third);
        return value == null || value.isBlank() ? (fourth == null ? "" : fourth) : value;
    }

    private void captureExceptionBreakpointFilters(JsonNode capabilities) {
        List<String> filters = new ArrayList<>();
        for (JsonNode filter : capabilities.path("exceptionBreakpointFilters")) {
            String id = filter.path("filter").asText("");
            if (!id.isBlank()) {
                filters.add(id);
            }
        }
        exceptionBreakpointFilters = filters;
        if (!filters.isEmpty()) {
            dlog("[debug] exception filters: " + filters);
        }
    }

    private CompletableFuture<JsonNode> sendExceptionBreakpoints() {
        ObjectNode args = MAPPER.createObjectNode();
        ArrayNode filters = args.putArray("filters");
        List<String> preferred = preferredExceptionFilters();
        preferred.forEach(filters::add);
        dlog("[debug] exception breakpoints: " + preferred);
        return sendRequestForResult("setExceptionBreakpoints", args)
                .exceptionally(e -> {
                    log.debug("[debug] setExceptionBreakpoints ignorado: {}", e.getMessage());
                    return MAPPER.createObjectNode();
                });
    }

    private List<String> preferredExceptionFilters() {
        List<String> supported = exceptionBreakpointFilters;
        List<String> result = new ArrayList<>();
        for (String filter : supported) {
            String lower = filter.toLowerCase(Locale.ROOT);
            if ((lower.contains("uncaught") || lower.contains("unhandled"))
                    && !lower.contains("user")) {
                result.add(filter);
            }
        }
        if (result.isEmpty()) {
            for (String filter : supported) {
                String lower = filter.toLowerCase(Locale.ROOT);
                if (lower.contains("uncaught") || lower.contains("unhandled")) {
                    result.add(filter);
                }
            }
        }
        return result.isEmpty() ? List.of("uncaught") : result;
    }

    void applyBreakpointChange(Path file, int line0Based, boolean added) {
        if (file == null || line0Based < 0) {
            return;
        }
        Path key = file.toAbsolutePath().normalize();
        NavigableSet<Integer> lines = liveBreakpoints.computeIfAbsent(key, k -> new TreeSet<>());
        synchronized (lines) {
            if (added) {
                lines.add(line0Based);
            } else {
                lines.remove(line0Based);
            }
        }
        sendBreakpointsForFile(key, lines);
    }

    private void sendThreadRequest(String command) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("threadId", threadId);
        sendRequest(command, args);
    }

    private void sendRequest(String command, ObjectNode args) {
        dispatchRequest(command, args, null);
    }

    private CompletableFuture<JsonNode> sendRequestForResult(String command, ObjectNode args) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        dispatchRequest(command, args, future);
        return future;
    }

    private synchronized void dispatchRequest(String command, ObjectNode args, CompletableFuture<JsonNode> resultFuture) {
        OutputStream out = dapIn;
        int id = seq.getAndIncrement();
        if (out == null) {
            if (resultFuture != null) {
                resultFuture.completeExceptionally(new IOException("Sessao de debug encerrada."));
            }
            return;
        }
        pendingRequests.put(id, command);
        if (resultFuture != null) {
            pendingResults.put(id, resultFuture);
        }
        ObjectNode request = MAPPER.createObjectNode();
        request.put("seq", id);
        request.put("type", "request");
        request.put("command", command);
        request.set("arguments", args == null ? MAPPER.createObjectNode() : args);
        try {
            byte[] body = MAPPER.writeValueAsBytes(request);
            out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
            if (LOGGED_COMMANDS.contains(command)) {
                dlog(">> " + command);
            }
        } catch (IOException e) {
            pendingResults.remove(id);
            if (resultFuture != null) {
                resultFuture.completeExceptionally(e);
            }
            log.debug("Falha ao enviar requisicao DAP {}: {}", command, e.getMessage());
        }
    }

    private void readLoop() {
        try {
            InputStream in = process.getInputStream();
            while (true) {
                int length = readContentLength(in);
                if (length < 0) {
                    break;
                }
                byte[] body = in.readNBytes(length);
                if (body.length < length) {
                    break;
                }
                dispatch(MAPPER.readTree(body));
            }
        } catch (Exception e) {
            log.debug("Leitura DAP encerrada: {}", e.getMessage());
        } finally {
            dlog("[debug] conexão com o netcoredbg encerrada.");
            Process p = process;
            dlog("[debug] stream DAP fechado; netcoredbg vivo="
                    + (p != null && p.isAlive())
                    + (p != null && !p.isAlive() ? " codigo=" + p.exitValue() : "") + ".");
            terminated.countDown();
        }
    }

    private int readContentLength(InputStream in) throws IOException {
        StringBuilder header = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            header.append((char) c);
            if (header.length() >= 4 && header.substring(header.length() - 4).equals("\r\n\r\n")) {
                break;
            }
        }
        if (c == -1) {
            return -1;
        }
        int length = -1;
        for (String line : header.toString().split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(line.substring("content-length:".length()).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return length;
    }

    private void dispatch(JsonNode msg) {
        String type = msg.path("type").asText();
        switch (type) {
            case "response" -> handleResponse(msg);
            case "event" -> handleEvent(msg);
            default -> {
            }
        }
    }

    private void handleResponse(JsonNode msg) {
        int requestSeq = msg.path("request_seq").asInt();
        String command = pendingRequests.remove(requestSeq);
        if (command == null) {
            command = msg.path("command").asText();
        }
        boolean success = msg.path("success").asBoolean(true);
        if ("configurationDone".equals(command)) {
            releaseStartupGate();
        }
        CompletableFuture<JsonNode> future = pendingResults.remove(requestSeq);
        if (future != null) {
            if (success) {
                future.complete(msg.path("body"));
            } else {
                future.completeExceptionally(new RuntimeException(
                        msg.path("message").asText("falha")));
            }
        }
        if (!success) {
            String reason = msg.path("message").asText("");
            log.debug("DAP {} falhou: {}", command, reason);
            if (LOGGED_COMMANDS.contains(command)) {
                dlog("<< " + command + " FALHOU: " + reason);
            }
            return;
        }
        if (LOGGED_COMMANDS.contains(command)) {
            dlog("<< " + command + " ok");
        }
        if ("attach".equals(command) || "launch".equals(command)) {
            debugStartAccepted.set(true);
            sendConfigurationIfReady();
        }
        if ("stackTrace".equals(command) && future == null) {
            JsonNode frames = msg.path("body").path("stackFrames");
            applyStackTrace(frames, true, null);
        }
    }

    private void handleEvent(JsonNode msg) {
        String event = msg.path("event").asText();
        if (LOGGED_EVENTS.contains(event)) {
            dlog("<< event " + event);
        }
        switch (event) {
            case "initialized" -> {
                initializedEventSeen.set(true);
                sendConfigurationIfReady();
            }
            case "stopped" -> {
                long ticket = stoppedTicket.incrementAndGet();
                JsonNode stoppedBody = msg.path("body");
                int stoppedThread = msg.path("body").path("threadId").asInt(0);
                if (stoppedThread > 0) {
                    threadId = stoppedThread;
                    requestStoppedStackTrace(stoppedThread, ticket, stoppedBody);
                } else if (threadId > 0) {
                    requestStoppedStackTrace(threadId, ticket, stoppedBody);
                } else {
                    requestStoppedThreads(ticket, stoppedBody);
                }
            }
            case "continued" -> {
                stoppedTicket.incrementAndGet();
                frameId = -1;
                callStack.clear();
                if (view != null) {
                    view.onDebugCleared();
                }
            }
            case "output" -> routeOutput(msg.path("body"));
            case "exited", "terminated" -> {
                stoppedTicket.incrementAndGet();
                terminated.countDown();
            }
            default -> {
            }
        }
    }

    private void requestStoppedThreads(long ticket, JsonNode stoppedBody) {
        sendRequestForResult("threads", MAPPER.createObjectNode())
                .thenAccept(body -> {
                    if (ticket != stoppedTicket.get()) {
                        return;
                    }
                    int resolvedThread = 0;
                    for (JsonNode thread : body.path("threads")) {
                        resolvedThread = thread.path("id").asInt(0);
                        if (resolvedThread > 0) {
                            break;
                        }
                    }
                    if (resolvedThread <= 0) {
                        log.debug("[debug] stopped sem threadId e sem threads disponiveis.");
                        return;
                    }
                    threadId = resolvedThread;
                    requestStoppedStackTrace(resolvedThread, ticket, stoppedBody);
                })
                .exceptionally(e -> {
                    log.debug("[debug] falha ao resolver threads no stopped: {}", e.getMessage());
                    return null;
                });
    }

    private void requestStoppedStackTrace(int stoppedThreadId, long ticket, JsonNode stoppedBody) {
        if (stoppedThreadId <= 0) {
            return;
        }
        CompletableFuture<DebugExceptionInfo> exceptionInfo = resolveExceptionInfo(stoppedThreadId, stoppedBody);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("threadId", stoppedThreadId);
        args.put("startFrame", 0);
        args.put("levels", 50);
        sendRequestForResult("stackTrace", args)
                .thenCombine(exceptionInfo, (body, exception) -> new StoppedStack(body, exception))
                .thenAccept(stop -> {
                    if (ticket != stoppedTicket.get()) {
                        return;
                    }
                    applyStackTrace(stop.body().path("stackFrames"), true, stop.exceptionInfo());
                })
                .exceptionally(e -> {
                    log.debug("[debug] falha ao carregar stackTrace no stopped: {}", e.getMessage());
                    return null;
                });
    }

    private CompletableFuture<DebugExceptionInfo> resolveExceptionInfo(int stoppedThreadId, JsonNode stoppedBody) {
        if (!isExceptionStop(stoppedBody)) {
            return CompletableFuture.completedFuture(null);
        }
        DebugExceptionInfo fallback = fallbackExceptionInfo(stoppedBody);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("threadId", stoppedThreadId);
        return sendRequestForResult("exceptionInfo", args)
                .thenApply(body -> {
                    JsonNode details = body.path("details");
                    String typeName = firstNonBlank(
                            details.path("fullTypeName").asText(""),
                            details.path("typeName").asText(""),
                            body.path("exceptionId").asText(""),
                            fallback.typeName());
                    String description = firstNonBlank(
                            body.path("description").asText(""),
                            fallback.description());
                    String message = firstNonBlank(
                            details.path("message").asText(""),
                            description,
                            fallback.message());
                    String stackTrace = firstNonBlank(
                            details.path("stackTrace").asText(""),
                            fallback.stackTrace());
                    String breakMode = firstNonBlank(
                            body.path("breakMode").asText(""),
                            fallback.breakMode());
                    return new DebugExceptionInfo(typeName, message, description, stackTrace, breakMode);
                })
                .exceptionally(e -> {
                    log.debug("[debug] exceptionInfo indisponivel: {}", e.getMessage());
                    return fallback;
                });
    }

    private static boolean isExceptionStop(JsonNode stoppedBody) {
        if (stoppedBody == null) {
            return false;
        }
        String reason = stoppedBody.path("reason").asText("").toLowerCase(Locale.ROOT);
        return reason.contains("exception");
    }

    private static DebugExceptionInfo fallbackExceptionInfo(JsonNode stoppedBody) {
        String text = stoppedBody == null ? "" : stoppedBody.path("text").asText("");
        String description = stoppedBody == null ? "" : stoppedBody.path("description").asText("");
        String message = firstNonBlank(text, description, "Excecao nao tratada.");
        return new DebugExceptionInfo("", message, description, "", "");
    }

    private boolean ensureStoppedFrame() {
        if (frameId >= 0) {
            return true;
        }
        return refreshStoppedFrame();
    }

    private boolean refreshStoppedFrame() {
        int resolvedThread = threadId;
        if (resolvedThread <= 0) {
            resolvedThread = resolveStoppedThread();
            if (resolvedThread > 0) {
                threadId = resolvedThread;
            }
        }
        if (resolvedThread <= 0) {
            return false;
        }
        ObjectNode args = MAPPER.createObjectNode();
        args.put("threadId", resolvedThread);
        args.put("startFrame", 0);
        args.put("levels", 50);
        try {
            JsonNode body = sendRequestForResult("stackTrace", args).get(1200, TimeUnit.MILLISECONDS);
            applyStackTrace(body.path("stackFrames"), false, null);
        } catch (Exception e) {
            log.debug("[debug] falha ao garantir frame atual: {}", e.getMessage());
        }
        return frameId >= 0;
    }

    private int resolveStoppedThread() {
        try {
            JsonNode body = sendRequestForResult("threads", MAPPER.createObjectNode()).get(1200, TimeUnit.MILLISECONDS);
            for (JsonNode thread : body.path("threads")) {
                int id = thread.path("id").asInt(0);
                if (id > 0) {
                    return id;
                }
            }
        } catch (Exception e) {
            log.debug("[debug] falha ao resolver thread atual: {}", e.getMessage());
        }
        return 0;
    }

    private void applyStackTrace(JsonNode frames, boolean notifyView, DebugExceptionInfo exceptionInfo) {
        if (frames == null || !frames.isArray() || frames.isEmpty()) {
            return;
        }
        JsonNode selectedFrame = preferredFrame(frames);
        frameId = selectedFrame.path("id").asInt(frameId);
        captureCallStack(frames);
        if (notifyView) {
            paintFrame(selectedFrame, exceptionInfo);
        }
    }

    private JsonNode preferredFrame(JsonNode frames) {
        JsonNode firstSourceFrame = null;
        for (JsonNode frame : frames) {
            if (isUserCodeFrame(frame)) {
                return frame;
            }
            if (firstSourceFrame == null && hasSourceLocation(frame)) {
                firstSourceFrame = frame;
            }
        }
        return firstSourceFrame == null ? frames.path(0) : firstSourceFrame;
    }

    private static boolean isUserCodeFrame(JsonNode frame) {
        return hasSourceLocation(frame)
                && frame.path("presentationHint").asText("").isEmpty()
                && frame.path("source").path("presentationHint").asText("").isEmpty();
    }

    private static boolean hasSourceLocation(JsonNode frame) {
        return frame != null
                && !frame.path("source").path("path").asText("").isBlank()
                && frame.path("line").asInt(0) > 0;
    }

    private void sendConfigurationIfReady() {
        if (!initializedEventSeen.get() || !debugStartAccepted.get()
                || !configurationSent.compareAndSet(false, true)) {
            return;
        }
        List<CompletableFuture<JsonNode>> configurationRequests = new ArrayList<>(sendAllBreakpoints());
        configurationRequests.add(sendExceptionBreakpoints());
        if (configurationRequests.isEmpty()) {
            sendRequest("configurationDone", null);
            return;
        }
        CompletableFuture.allOf(configurationRequests.toArray(CompletableFuture[]::new))
                .orTimeout(3, TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.debug("[debug] liberando configuracao apos falha/timeout em breakpoints: {}",
                                error.getMessage());
                    }
                    sendRequest("configurationDone", null);
                });
    }

    private void startInferior() throws IOException {
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().normalize().toString());
        command.add(program.toAbsolutePath().normalize().toString());
        command.addAll(programArgs);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        applyDotnetEnv(builder);
        Map<String, String> env = launchEnv;
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }
        applyStartupGate(builder);
        Process child = builder.start();
        debuggee = child;
        dlog("[debug] programa iniciado pid=" + child.pid() + (gateFile != null ? " (aguardando debugger)." : "."));
        if (debuggeeStdin != null) {
            debuggeeStdin.bind(child.getOutputStream());
        }
        Thread pump = new Thread(() -> pumpToProgramOut(child.getInputStream()), "dotnet-debuggee-out");
        pump.setDaemon(true);
        pump.start();
        Thread exitWatch = new Thread(() -> {
            try {
                int code = child.waitFor();
                log.info("[debug] inferior encerrou exitCode={}", code);
                dlog("[debug] programa encerrou (código " + code + ").");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            terminated.countDown();
        }, "dotnet-debuggee-exit");
        exitWatch.setDaemon(true);
        exitWatch.start();
        log.info("[debug] inferior lancado para attach pid={} cmd={}", child.pid(), command);
    }

    private void applyStartupGate(ProcessBuilder builder) {
        if (startupHook == null || !Files.isRegularFile(startupHook)) {
            return;
        }
        try {
            Path gate = Files.createTempDirectory("orion-dbg").resolve("release.gate");
            Files.deleteIfExists(gate);
            gateFile = gate;
            String hook = startupHook.toAbsolutePath().normalize().toString();
            String existing = builder.environment().get("DOTNET_STARTUP_HOOKS");
            builder.environment().put("DOTNET_STARTUP_HOOKS",
                    existing == null || existing.isBlank() ? hook : hook + File.pathSeparator + existing);
            builder.environment().put("ORION_DEBUG_WAIT_FILE", gate.toString());
        } catch (IOException e) {
            log.debug("[debug] falha ao preparar gate de inicializacao: {}", e.getMessage());
            gateFile = null;
        }
    }

    private void releaseStartupGate() {
        Path gate = gateFile;
        if (gate == null) {
            return;
        }
        try {
            Files.write(gate, new byte[0]);
        } catch (IOException e) {
            log.debug("[debug] falha ao liberar gate de inicializacao: {}", e.getMessage());
        }
    }

    private void cleanupGate() {
        Path gate = gateFile;
        gateFile = null;
        if (gate == null) {
            return;
        }
        try {
            Files.deleteIfExists(gate);
            Path dir = gate.getParent();
            if (dir != null) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException ignored) {
        }
    }

    private void sendAttach() {
        Process child = debuggee;
        if (child == null || !child.isAlive()) {
            safeWriteProgram("[debug] processo do programa não está ativo para anexar." + System.lineSeparator());
            terminate();
            return;
        }
        ObjectNode attach = MAPPER.createObjectNode();
        attach.put("processId", (int) child.pid());
        dlog(">> attach pid=" + child.pid());
        sendRequest("attach", attach);
    }

    private void sendLaunch() {
        ObjectNode launch = MAPPER.createObjectNode();
        launch.put("name", ".NET Debug");
        launch.put("type", "coreclr");
        launch.put("request", "launch");
        launch.put("program", program.toAbsolutePath().normalize().toString());
        launch.put("cwd", cwd.toAbsolutePath().normalize().toString());
        launch.put("stopAtEntry", false);
        launch.put("justMyCode", true);
        launch.put("console", "internalConsole");
        if (!programArgs.isEmpty()) {
            ArrayNode args = launch.putArray("args");
            programArgs.forEach(args::add);
        }
        ObjectNode envNode = launch.putObject("env");
        Path root = dotnetRoot();
        if (root != null) {
            String value = root.toString();
            envNode.put("DOTNET_ROOT", value);
            envNode.put("DOTNET_ROOT_X64", value);
            envNode.put("DOTNET_ROOT(x86)", value);
            envNode.put("DOTNET_HOST_PATH", dotnet.toAbsolutePath().normalize().toString());
            String path = System.getenv(pathEnvName());
            String mergedPath = value + java.io.File.pathSeparator + (path == null ? "" : path);
            envNode.put(pathEnvName(), mergedPath);
            if (isWindows()) {
                envNode.put("PATH", mergedPath);
            }
        }
        envNode.put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        envNode.put("DOTNET_MODIFIABLE_ASSEMBLIES", "debug");
        launchEnv.forEach(envNode::put);
        sendRequest("launch", launch);
    }

    private void applyDotnetEnv(ProcessBuilder builder) {
        builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        builder.environment().put("DOTNET_MODIFIABLE_ASSEMBLIES", "debug");
        Path root = dotnetRoot();
        if (root != null) {
            String value = root.toString();
            builder.environment().put("DOTNET_ROOT", value);
            builder.environment().put("DOTNET_ROOT_X64", value);
            builder.environment().put("DOTNET_ROOT(x86)", value);
            builder.environment().put("DOTNET_HOST_PATH", dotnet.toAbsolutePath().normalize().toString());
            String pathKey = pathEnvName(builder.environment());
            String path = builder.environment().get(pathKey);
            builder.environment().put(pathKey, value + java.io.File.pathSeparator + (path == null ? "" : path));
        }
    }

    private Path dotnetRoot() {
        return dotnet == null ? null : dotnet.toAbsolutePath().normalize().getParent();
    }

    private static String pathEnvName() {
        return pathEnvName(System.getenv());
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

    private void pumpToProgramOut(InputStream in) {
        byte[] buffer = new byte[4096];
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                synchronized (programOut) {
                    programOut.write(buffer, 0, read);
                    programOut.flush();
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void dlog(String text) {
        log.debug("[dap] {}", text);
    }

    private void safeWriteProgram(String text) {
        try {
            synchronized (programOut) {
                programOut.write(text.getBytes(StandardCharsets.UTF_8));
                programOut.flush();
            }
        } catch (IOException ignored) {}
    }

    private List<CompletableFuture<JsonNode>> sendAllBreakpoints() {
        if (liveBreakpoints.isEmpty()) {
            log.warn("[debug] nenhum breakpoint inicial para enviar.");
            return List.of();
        }
        List<CompletableFuture<JsonNode>> requests = new ArrayList<>();
        for (Map.Entry<Path, NavigableSet<Integer>> entry : liveBreakpoints.entrySet()) {
            requests.add(sendBreakpointsForFile(entry.getKey(), entry.getValue()));
        }
        return requests;
    }

    private CompletableFuture<JsonNode> sendBreakpointsForFile(Path file, NavigableSet<Integer> lines) {
        List<Integer> snapshot;
        synchronized (lines) {
            snapshot = new ArrayList<>(lines);
        }
        ObjectNode args = MAPPER.createObjectNode();
        ObjectNode source = MAPPER.createObjectNode();
        source.put("path", file.toString());
        source.put("name", file.getFileName() == null ? file.toString() : file.getFileName().toString());
        args.set("source", source);
        ArrayNode bps = args.putArray("breakpoints");
        for (int line : snapshot) {
            bps.addObject().put("line", line + 1);
        }
        log.info("[debug] setBreakpoints arquivo={} linhas={}", file, snapshot);
        return sendRequestForResult("setBreakpoints", args);
    }

    private void captureCallStack(JsonNode frames) {
        callStack.clear();
        if (frames == null || !frames.isArray()) {
            return;
        }
        for (JsonNode frame : frames) {
            String path = frame.path("source").path("path").asText("");
            boolean userCode = isUserCodeFrame(frame);
            callStack.add(new DebugFrame(
                    frame.path("id").asInt(0),
                    frame.path("name").asText(""),
                    path,
                    frame.path("line").asInt(0),
                    userCode));
        }
    }

    private void paintFrame(JsonNode frame, DebugExceptionInfo exceptionInfo) {
        if (view == null || frame == null || !hasSourceLocation(frame)) {
            return;
        }
        String path = frame.path("source").path("path").asText(null);
        int line = frame.path("line").asInt(0);
        try {
            view.onDebugStopped(Path.of(path), line, exceptionInfo);
        } catch (Exception e) {
            log.warn("[debug] falha ao destacar linha: {}", e.getMessage());
        }
    }

    private record StoppedStack(JsonNode body, DebugExceptionInfo exceptionInfo) {
    }

    private void routeOutput(JsonNode body) {
        String category = body.path("category").asText("");
        String text = body.path("output").asText("");
        if (text.isEmpty() || "telemetry".equals(category)) {
            return;
        }
        try {
            synchronized (programOut) {
                programOut.write(text.getBytes(StandardCharsets.UTF_8));
                programOut.flush();
            }
        } catch (IOException e) {
            log.warn("[debug] falha ao escrever no painel: {}", e.getMessage());
        }
    }
}
