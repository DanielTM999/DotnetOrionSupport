package dtm.ide.build;

import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class BuildDiagnosticParser {
    private static final Pattern MESSAGE = Pattern.compile(
            "^(?:(.*?)\\s*:\\s*)?(error|warning)\\s+([A-Za-z]+\\d+)\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION = Pattern.compile("^(.*)\\((\\d+)(?:,(\\d+))?(?:,\\d+,\\d+)?\\)$");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
    private final Path root;
    private final Set<BuildDiagnostic> diagnostics = new LinkedHashSet<>();

    public BuildDiagnosticParser(Path root) { this.root = root; }

    public synchronized void accept(String line) {
        if (line == null) return;
        String clean = ANSI.matcher(line).replaceAll("").strip().replaceFirst("^\\d+>", "");
        var match = MESSAGE.matcher(clean);
        if (!match.matches()) return;
        String origin = match.group(1);
        Path file = null;
        int row = 0;
        int column = 0;
        if (origin != null) {
            var location = LOCATION.matcher(origin);
            if (location.matches()) {
                file = resolve(location.group(1));
                row = number(location.group(2));
                column = number(location.group(3));
            } else if (origin.matches("(?i).*\\.(csproj|vbproj|fsproj|sln|slnx|props|targets)$")) {
                file = resolve(origin);
            }
        }
        String message = match.group(4).replaceFirst("\\s+\\[[^\\]]+\\.(?:csproj|vbproj|fsproj)(?:[^\\]]*)\\]$", "");
        String code = match.group(3);
        diagnostics.add(new BuildDiagnostic(file, row, column,
                match.group(2).equalsIgnoreCase("error") ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING,
                code, message, code.startsWith("NU") ? "NuGet" : "MSBuild"));
    }

    private Path resolve(String path) {
        try {
            Path file = Path.of(path.strip());
            return (file.isAbsolute() || root == null ? file : root.resolve(file)).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) { return null; }
    }

    private static int number(String text) {
        try { return text == null ? 0 : Integer.parseInt(text); }
        catch (NumberFormatException ignored) { return 0; }
    }

    public synchronized List<BuildDiagnostic> diagnostics() { return List.copyOf(diagnostics); }
}
