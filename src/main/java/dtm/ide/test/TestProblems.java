package dtm.ide.test;

import dtm.ide.build.BuildDiagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class TestProblems {
    public record Source(Path file, int line) {}
    private static final Pattern FRAME = Pattern.compile("(?:in |em )(.+?):(?:line|linha) (\\d+)");
    private TestProblems() {}

    public static Optional<Source> source(TestResult result, Path workspace) {
        if (workspace == null) return Optional.empty();
        Path root = workspace.toAbsolutePath().normalize();
        var matcher = FRAME.matcher(result.stackTrace());
        while (matcher.find()) {
            try {
                Path path = root.resolve(matcher.group(1)).normalize();
                int line = Integer.parseInt(matcher.group(2));
                if (line > 0 && path.startsWith(root) && Files.isRegularFile(path)) return Optional.of(new Source(path, line));
            } catch (RuntimeException ignored) { }
        }
        return Optional.empty();
    }

    public static List<BuildDiagnostic> diagnostics(List<TestResult> tests, Path root) {
        return tests.stream().filter(test -> test.outcome() == TestResult.Outcome.FAILED).map(test -> {
            var source = source(test, root).orElse(new Source(null, 1));
            return new BuildDiagnostic(source.file(), source.line(), 1, DiagnosticSeverity.ERROR, "TEST",
                    test.displayName() + (test.message().isBlank() ? "" : ": " + test.message()), "dotnet test");
        }).toList();
    }
}
