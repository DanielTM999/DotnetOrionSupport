package dtm.ide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class DotnetSolutionSelection {

    private static final Map<Path, Path> SELECTED = new ConcurrentHashMap<>();

    private DotnetSolutionSelection() {
    }

    public static void select(Path root, Path solution) {
        if (root == null) {
            return;
        }
        Path key = normalize(root);
        if (solution == null) {
            SELECTED.remove(key);
            return;
        }
        SELECTED.put(key, normalize(solution));
    }

    public static Path selected(Path root) {
        if (root == null) {
            return null;
        }
        return SELECTED.get(normalize(root));
    }

    public static void clear(Path root) {
        if (root != null) {
            SELECTED.remove(normalize(root));
        }
    }

    public static List<Path> listSolutions(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(DotnetSolutionSelection::isSolution)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isSolution(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sln") || name.endsWith(".slnx");
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
