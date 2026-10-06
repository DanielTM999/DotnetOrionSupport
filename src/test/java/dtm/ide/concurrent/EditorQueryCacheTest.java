package dtm.ide.concurrent;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EditorQueryCacheTest {
    @Test
    void replacesPendingVersionAndNeverPublishesItsResult() throws Exception {
        try (var cache = new EditorQueryCache<String>()) {
            var started = new CountDownLatch(1);
            var refreshes = new AtomicInteger();
            var old = cache.request(Path.of("file.cs"), 1, () -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "old";
            }, refreshes::incrementAndGet);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var latest = cache.request(Path.of("file.cs"), 2, () -> "new", refreshes::incrementAndGet);
            assertEquals("new", latest.get(2, TimeUnit.SECONDS));
            assertTrue(old.isCancelled());
            assertSame(latest, cache.request(Path.of("file.cs"), 2, () -> "wrong", () -> {}));
            cache.clear();
            assertEquals(1, refreshes.get());
        }
    }

    @Test
    void closeCancelsWorkAndRejectsNewRequests() throws Exception {
        var cache = new EditorQueryCache<String>();
        var started = new CountDownLatch(1);
        var result = cache.request(Path.of("file.cs"), 1, () -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "old";
        }, () -> fail("Closed query published"));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        cache.close();
        assertTrue(result.isCancelled());
        assertTrue(cache.request(Path.of("file.cs"), 2, () -> "new", () -> {}).isCompletedExceptionally());
    }
}
