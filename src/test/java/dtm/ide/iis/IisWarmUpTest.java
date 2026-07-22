package dtm.ide.iis;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisWarmUpTest {

    private static int serve(HttpServer server, int status) {
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void reportsServiceUnavailableStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            int port = serve(server, 503);

            assertEquals(503, IisWarmUp.status("http://127.0.0.1:" + port + "/", 5000));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsSuccessStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            int port = serve(server, 200);
            String url = "http://127.0.0.1:" + port + "/";

            assertEquals(200, IisWarmUp.status(url, 5000));
            assertTrue(IisWarmUp.request(url, 5000));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsZeroWhenNothingListens() {
        assertEquals(0, IisWarmUp.status("http://127.0.0.1:1/", 500));
        assertFalse(IisWarmUp.request("http://127.0.0.1:1/", 500));
    }
}
