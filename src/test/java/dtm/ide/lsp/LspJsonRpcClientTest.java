package dtm.ide.lsp;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LspJsonRpcClientTest {
    @Test
    void eofFailsOutstandingAndFutureRequests() throws Exception {
        var input = new PipedInputStream();
        var server = new PipedOutputStream(input);
        var client = new LspJsonRpcClient(input, new ByteArrayOutputStream());
        try {
            var result = client.sendRequest("textDocument/hover", Map.of());
            server.close();
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> client.sendRequest("textDocument/hover", Map.of()).get(2, TimeUnit.SECONDS));
        } finally {
            client.close();
        }
    }

    @Test
    void cancellationIsSentToServer() throws Exception {
        var input = new PipedInputStream();
        try (var server = new PipedOutputStream(input)) {
            var output = new ByteArrayOutputStream();
            var client = new LspJsonRpcClient(input, output);
            try {
                var request = client.sendRequest("textDocument/hover", Map.of());
                request.cancel(true);
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("$/cancelRequest"));
            } finally {
                client.close();
            }
        }
    }
}
