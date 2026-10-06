package dtm.ide.concurrent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

public final class EditorQueryCache<T> implements AutoCloseable {
    private final Map<Path, Entry<T>> entries = new HashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private boolean closed;

    public synchronized CompletableFuture<T> request(Path path, Object key, Supplier<T> query, Runnable refresh) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Editor queries closed"));
        Path file = path.toAbsolutePath().normalize();
        Entry<T> previous = entries.get(file);
        if (previous != null && Objects.equals(previous.key, key)) return previous.result;
        invalidate(file);
        CompletableFuture<T> result = new CompletableFuture<>();
        Entry<T> entry = new Entry<>(key, result);
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                T value = query.get();
                synchronized (EditorQueryCache.this) {
                    if (!closed && entries.get(file) == entry && !result.isCancelled()) {
                        result.complete(value);
                        refresh.run();
                    }
                }
            } catch (Throwable failure) {
                synchronized (EditorQueryCache.this) {
                    entries.remove(file, entry);
                    result.completeExceptionally(failure);
                }
                if (failure instanceof Error error) throw error;
            }
            return null;
        });
        entry.task = task;
        entries.put(file, entry);
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) task.cancel(true);
        });
        workers.execute(task);
        return result;
    }

    public synchronized void invalidate(Path path) {
        if (path == null) return;
        Entry<T> entry = entries.remove(path.toAbsolutePath().normalize());
        if (entry != null) {
            entry.result.cancel(true);
            entry.task.cancel(true);
        }
    }

    public synchronized void clear() {
        for (Path file : java.util.List.copyOf(entries.keySet())) invalidate(file);
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
        workers.shutdownNow();
    }

    private static final class Entry<T> {
        private final Object key;
        private final CompletableFuture<T> result;
        private FutureTask<Void> task;

        private Entry(Object key, CompletableFuture<T> result) {
            this.key = key;
            this.result = result;
        }
    }
}
