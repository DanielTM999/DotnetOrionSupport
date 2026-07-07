package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class FakeLspServerMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> MEMBERS = List.of("WriteLine", "Write", "ReadLine", "ReadKey", "Clear");

    private FakeLspServerMain() {
    }

    public static void main(String[] args) throws Exception {
        InputStream in = System.in;
        OutputStream out = System.out;
        while (true) {
            JsonNode message = readMessage(in);
            if (message == null) {
                return;
            }
            JsonNode idNode = message.get("id");
            String method = message.path("method").asText("");
            if ("exit".equals(method)) {
                return;
            }
            if (idNode == null) {
                continue;
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", idNode);
            response.set("result", resultFor(method));
            writeMessage(out, response);
        }
    }

    private static JsonNode resultFor(String method) {
        switch (method) {
            case "initialize":
                return initializeResult();
            case "textDocument/completion":
                return completionResult();
            case "shutdown":
                return MAPPER.nullNode();
            default:
                return MAPPER.nullNode();
        }
    }

    private static JsonNode initializeResult() {
        ObjectNode capabilities = MAPPER.createObjectNode();
        capabilities.put("textDocumentSync", 1);
        ObjectNode completion = MAPPER.createObjectNode();
        completion.put("resolveProvider", false);
        ArrayNode triggers = completion.putArray("triggerCharacters");
        triggers.add(".");
        capabilities.set("completionProvider", completion);
        capabilities.put("definitionProvider", true);
        ObjectNode result = MAPPER.createObjectNode();
        result.set("capabilities", capabilities);
        return result;
    }

    private static JsonNode completionResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("isIncomplete", false);
        ArrayNode items = result.putArray("items");
        for (String member : MEMBERS) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("label", member);
            item.put("kind", 2);
            item.put("insertText", member);
            item.put("detail", "void Console." + member + "()");
            items.add(item);
        }
        return result;
    }

    private static void writeMessage(OutputStream out, ObjectNode message) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(message);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (out) {
            out.write(header);
            out.write(body);
            out.flush();
        }
    }

    private static JsonNode readMessage(InputStream in) throws Exception {
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
            throw new IllegalStateException("LSP message without Content-Length header");
        }
        byte[] body = in.readNBytes(contentLength);
        if (body.length < contentLength) {
            return null;
        }
        return MAPPER.readTree(body);
    }
}
