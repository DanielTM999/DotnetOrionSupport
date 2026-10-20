package dtm.ide.editor;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A conservative second check, including literals/Razor where references can be indirect. */
public final class SafeDeleteSupport {
    public record Check(boolean complete, boolean possibleUses, String reason) {}
    private static final Set<String> SKIP = Set.of("bin", "obj", "node_modules", "target", "packages", "vendor");

    public Check check(Path root, Path declaration, int start, int end, String name, Map<Path, String> open) {
        boolean[] use = {false}, complete = {true};
        int[] count = {0};
        var pattern = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(name.replaceFirst("^@", "")) + "(?![\\p{L}\\p{N}_])");
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                    String segment = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    return !path.equals(root) && (segment.startsWith(".") || SKIP.contains(segment))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted() || ++count[0] > 10000) { complete[0] = false; return FileVisitResult.TERMINATE; }
                    if (!attrs.isRegularFile() || !path.toString().matches("(?i).*\\.(cs|razor|cshtml)")) return FileVisitResult.CONTINUE;
                    if (attrs.size() > 2_000_000) { complete[0] = false; return FileVisitResult.CONTINUE; }
                    String source = open.get(path.toAbsolutePath().normalize());
                    if (source == null) source = Files.readString(path);
                    var matcher = pattern.matcher(source);
                    boolean current = path.toAbsolutePath().normalize().equals(declaration.toAbsolutePath().normalize());
                    while (matcher.find()) if (!current || matcher.start() < start || matcher.start() >= end) {
                        use[0] = true; return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, IOException error) { complete[0] = false; return FileVisitResult.CONTINUE; }
            });
        } catch (IOException e) { complete[0] = false; }
        return new Check(complete[0], use[0], !complete[0] ? "Workspace scan was incomplete" : use[0] ? "Possible references remain in the workspace" : "");
    }
}
