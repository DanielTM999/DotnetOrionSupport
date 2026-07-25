package dtm.ide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DotnetStartupProject {

    private static final Map<Path, Path> STARTUP = new ConcurrentHashMap<>();

    private DotnetStartupProject() {
    }

    public static void set(Path root, Path projectFile) {
        if (root == null) {
            return;
        }
        Path key = normalize(root);
        if (projectFile == null) {
            STARTUP.remove(key);
            return;
        }
        STARTUP.put(key, normalize(projectFile));
    }

    public static Path get(Path root) {
        if (root == null) {
            return null;
        }
        Path projectFile = STARTUP.get(normalize(root));
        return projectFile != null && Files.isRegularFile(projectFile) ? projectFile : null;
    }

    public static boolean is(Path root, Path projectFile) {
        if (root == null || projectFile == null) {
            return false;
        }
        Path current = STARTUP.get(normalize(root));
        return current != null && current.equals(normalize(projectFile));
    }

    public static void clear(Path root) {
        if (root != null) {
            STARTUP.remove(normalize(root));
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
