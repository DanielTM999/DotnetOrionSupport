package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
final class LspJsonRpcClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InputStream in;
    private final OutputStream out;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> notificationHandlers = new ConcurrentHashMap<>();
    private final Map<String, Function<JsonNode, Object>> requestHandlers = new ConcurrentHashMap<>();
    private volatile Consumer<JsonNode> errorObserver;
    private final ExecutorService executor = Executors.newCachedThreadPool(daemonFactory("dotnet-lsp-rpc"));
    private final Future<?> reader;
    private final Object writeLock = new Object();
    private volatile boolean closed;

    LspJsonRpcClient(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
        this.reader = executor.submit(this::readLoop);
    }

    CompletableFuture<JsonNode> sendRequest(String method, Object params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", MAPPER.valueToTree(params));

        try {
            writeMessage(req);
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    void sendNotification(String method, Object params) {
        ObjectNode notif = MAPPER.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", method);
        notif.set("params", MAPPER.valueToTree(params));
        try {
            writeMessage(notif);
        } catch (IOException e) {
            log.debug("Falha ao enviar notificacao LSP {}: {}", method, e.getMessage());
        }
    }

    void onNotification(String method, Consumer<JsonNode> handler) {
        notificationHandlers.put(method, handler);
    }

    void onRequest(String method, Function<JsonNode, Object> handler) {
        requestHandlers.put(method, handler);
    }

    void onError(Consumer<JsonNode> observer) {
        this.errorObserver = observer;
    }

    void close() {
        closed = true;
        try {
            in.close();
        } catch (IOException ignored) {
        }
        try {
            out.close();
        } catch (IOException ignored) {
        }
        pending.values().forEach(f -> f.completeExceptionally(new IOException("LSP client closed")));
        pending.clear();
        reader.cancel(true);
        executor.shutdownNow();
    }

    private void writeMessage(ObjectNode message) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(message);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            out.write(header);
            out.write(body);
            out.flush();
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                JsonNode message = readMessage();
                if (message == null) {
                    break;
                }
                dispatch(message);
            }
        } catch (IOException e) {
            if (!closed) {
                log.debug("Loop JSON-RPC do LSP terminou: {}", e.getMessage());
            }
        } catch (Exception e) {
            if (!closed) {
                log.warn("Loop JSON-RPC do LSP terminou com erro", e);
            }
        }
    }

    private void dispatch(JsonNode message) {
        JsonNode idNode = message.get("id");
        JsonNode methodNode = message.get("method");

        if (idNode != null && idNode.canConvertToLong() && (message.has("result") || message.has("error"))) {
            long id = idNode.asLong();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future == null) {
                return;
            }
            if (message.has("error")) {
                JsonNode error = message.get("error");
                Consumer<JsonNode> observer = errorObserver;
                if (observer != null) {
                    try {
                        observer.accept(error);
                    } catch (Exception ignored) {
                    }
                }
                future.completeExceptionally(new RuntimeException("LSP error: " + error));
            } else {
                future.complete(message.get("result"));
            }
            return;
        }

        if (methodNode != null) {
            String method = methodNode.asText();
            if (idNode != null) {
                Object result = null;
                Function<JsonNode, Object> requestHandler = requestHandlers.get(method);
                if (requestHandler != null) {
                    try {
                        result = requestHandler.apply(message.get("params"));
                    } catch (Exception e) {
                        log.debug("Handler de request LSP {} falhou: {}", method, e.getMessage());
                    }
                }
                ObjectNode response = MAPPER.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", idNode);
                response.set("result", result == null ? MAPPER.nullNode() : MAPPER.valueToTree(result));
                try {
                    writeMessage(response);
                } catch (IOException e) {
                    log.debug("Falha ao responder request LSP {}: {}", method, e.getMessage());
                }
                return;
            }
            Consumer<JsonNode> handler = notificationHandlers.get(method);
            if (handler != null) {
                try {
                    handler.accept(message.get("params"));
                } catch (Exception e) {
                    log.debug("Handler LSP {} falhou: {}", method, e.getMessage());
                }
            }
        }
    }

    private JsonNode readMessage() throws IOException {
        int contentLength = -1;
        StringBuilder line = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c == -1) {
                return null;
            }
            if (c == '\r') {
                int next = in.read();
                if (next == '\n') {
                    String header = line.toString();
                    line.setLength(0);
                    if (header.isEmpty()) {
                        break;
                    }
                    int colon = header.indexOf(':');
                    if (colon > 0) {
                        String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String value = header.substring(colon + 1).trim();
                        if (name.equals("content-length")) {
                            contentLength = Integer.parseInt(value);
                        }
                    }
                } else {
                    line.append((char) c);
                    if (next != -1) {
                        line.append((char) next);
                    }
                }
            } else {
                line.append((char) c);
            }
        }
        if (contentLength < 0) {
            throw new IOException("LSP message without Content-Length header");
        }
        byte[] body = in.readNBytes(contentLength);
        if (body.length < contentLength) {
            throw new IOException("LSP stream ended mid-message");
        }
        return MAPPER.readTree(body);
    }

    static Map<String, Object> position(int line, int character) {
        return Map.of("line", line, "character", character);
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
