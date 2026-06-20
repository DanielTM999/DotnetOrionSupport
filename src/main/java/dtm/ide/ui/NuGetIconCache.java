package dtm.ide.ui;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

final class NuGetIconCache {

    private static final int SIZE = 32;
    private static final Icon PENDING = new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));

    private final Map<String, Icon> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ExecutorService loaders = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "nuget-icon-loader");
        t.setDaemon(true);
        return t;
    });

    Icon iconFor(String url, Runnable onLoaded) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Icon cached = cache.get(url);
        if (cached != null) {
            return cached == PENDING ? null : cached;
        }
        cache.put(url, PENDING);
        loaders.execute(() -> {
            Icon loaded = load(url);
            cache.put(url, loaded == null ? PENDING : loaded);
            if (loaded != null && onLoaded != null) {
                SwingUtilities.invokeLater(onLoaded);
            }
        });
        return null;
    }

    Icon iconForId(String id, Supplier<String> urlResolver, Runnable onLoaded) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = "id:" + id.toLowerCase(Locale.ROOT);
        Icon cached = cache.get(key);
        if (cached != null) {
            return cached == PENDING ? null : cached;
        }
        cache.put(key, PENDING);
        loaders.execute(() -> {
            Icon loaded = null;
            try {
                String url = urlResolver.get();
                if (url != null && !url.isBlank()) {
                    loaded = load(url);
                }
            } catch (Exception ignored) {
            }
            cache.put(key, loaded == null ? PENDING : loaded);
            if (loaded != null && onLoaded != null) {
                SwingUtilities.invokeLater(onLoaded);
            }
        });
        return null;
    }

    private Icon load(String url) {
        try {
            if (!url.toLowerCase(Locale.ROOT).startsWith("http")) {
                return null;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "DotnetOrionSupport/1.0")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
            if (image == null) {
                return null;
            }
            Image scaled = image.getScaledInstance(SIZE, SIZE, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Exception e) {
            return null;
        }
    }
}
