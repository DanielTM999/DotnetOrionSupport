package dtm.ide.project;

import java.nio.file.Path;

/** Common selection used by build/test tools; persisted run configurations remain authoritative. */
public record WorkspaceTarget(Path target, String configuration, String framework) {
    public WorkspaceTarget {
        if (target != null) target = target.toAbsolutePath().normalize();
        configuration = configuration == null || configuration.isBlank() ? "Debug" : configuration;
        framework = framework == null ? "" : framework;
    }
}
