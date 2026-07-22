package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
public final class IisWarmUp {

    private static final Set<String> CLR_HOST_NAMES = Set.of("dotnet.exe", "dotnet");
    private static final long POLL_INTERVAL_MS = 250;
    private static final long WORKER_LOOKUP_INTERVAL_MS = 2000;

    private IisWarmUp() {
    }

    public static boolean request(String url, int timeoutMs) {
        if (url == null || url.isBlank()) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            URL target = URI.create(url).toURL();
            connection = (HttpURLConnection) target.openConnection();
            if (connection instanceof HttpsURLConnection secure && isLoopback(target.getHost())) {
                applyPermissiveTls(secure);
            }
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(false);
            connection.getResponseCode();
            return true;
        } catch (Exception e) {
            log.debug("Warm-up de {} não respondeu: {}", url, e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("localhost") || value.equals("127.0.0.1") || value.equals("::1");
    }

    private static void applyPermissiveTls(HttpsURLConnection connection) throws Exception {
        TrustManager[] trustManagers = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new java.security.SecureRandom());
        connection.setSSLSocketFactory(context.getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> isLoopback(hostname));
    }

    public static long awaitClrProcess(ProcessHandle host, IisWebProject.HostingModel model,
                                       String assemblyName, long timeoutMs) {
        if (host == null) {
            return 0;
        }
        if (model == IisWebProject.HostingModel.IN_PROCESS) {
            return host.isAlive() ? host.pid() : 0;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long pid = findClrDescendant(host, assemblyName);
            if (pid > 0) {
                return pid;
            }
            if (!host.isAlive()) {
                return 0;
            }
            sleepQuietly();
        }
        return 0;
    }

    private static long findClrDescendant(ProcessHandle host, String assemblyName) {
        List<ProcessHandle> descendants = host.descendants().filter(ProcessHandle::isAlive).toList();
        String expected = assemblyName == null ? null : assemblyName.toLowerCase(Locale.ROOT) + ".exe";
        for (ProcessHandle handle : descendants) {
            String name = processName(handle);
            if (name == null) {
                continue;
            }
            if (CLR_HOST_NAMES.contains(name) || (expected != null && expected.equals(name))) {
                return handle.pid();
            }
        }
        return 0;
    }

    private static String processName(ProcessHandle handle) {
        String command = handle.info().command().orElse(null);
        if (command == null) {
            return null;
        }
        Path path = Path.of(command).getFileName();
        return path == null ? null : path.toString().toLowerCase(Locale.ROOT);
    }

    public static long awaitWorkerProcess(String appPoolName, IisWebProject.HostingModel model,
                                          String assemblyName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long workerPid = 0;
        while (System.currentTimeMillis() < deadline) {
            if (workerPid <= 0 || !ProcessHandle.of(workerPid).filter(ProcessHandle::isAlive).isPresent()) {
                workerPid = AppCmd.findWorkerProcessPid(appPoolName);
            }
            if (workerPid > 0) {
                if (model == IisWebProject.HostingModel.IN_PROCESS) {
                    return workerPid;
                }
                ProcessHandle worker = ProcessHandle.of(workerPid).orElse(null);
                long clr = worker == null ? 0 : findClrDescendant(worker, assemblyName);
                if (clr > 0) {
                    return clr;
                }
                sleepQuietly(POLL_INTERVAL_MS);
                continue;
            }
            sleepQuietly(WORKER_LOOKUP_INTERVAL_MS);
        }
        return 0;
    }

    private static void sleepQuietly() {
        sleepQuietly(POLL_INTERVAL_MS);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void openBrowser(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            log.debug("Falha ao abrir o navegador em {}: {}", url, e.getMessage());
        }
    }
}
