package dtm.ide.test;

import java.nio.file.Path;

/** Execution identity includes the report, preventing collisions across projects/frameworks. */
public record TestResult(Path report, String id, String name, String displayName, Outcome outcome,
                         String duration, String message, String stackTrace) {
    public enum Outcome { PASSED, FAILED, SKIPPED, UNKNOWN }
}
