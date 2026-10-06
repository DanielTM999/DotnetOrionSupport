package dtm.ide.build;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record BuildRequest(Path target, String action, String configuration, String framework, String msbuildTarget) {
    private static final Set<String> ACTIONS = Set.of("restore", "build", "rebuild", "clean", "test", "pack", "publish", "msbuild");

    public BuildRequest {
        if (target == null) throw new IllegalArgumentException("Missing build target");
        target = target.toAbsolutePath().normalize();
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Unknown build action: " + action);
        configuration = configuration == null || configuration.isBlank() ? "Debug" : configuration;
        framework = framework == null ? "" : framework.strip();
        msbuildTarget = msbuildTarget == null ? "" : msbuildTarget.strip();
        if (action.equals("msbuild") && !msbuildTarget.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid MSBuild target");
        }
    }

    public List<String> arguments() {
        List<String> args = new ArrayList<>();
        args.add(action.equals("rebuild") ? "build" : action);
        if (action.equals("rebuild")) args.add("--no-incremental");
        if (action.equals("msbuild")) {
            args.add("-target:" + msbuildTarget);
            args.add("-property:Configuration=" + configuration);
            if (!framework.isBlank()) args.add("-property:TargetFramework=" + framework);
        } else {
            if (!action.equals("restore")) {
                args.add("--configuration");
                args.add(configuration);
            }
            if (!framework.isBlank()) {
                if (action.equals("restore")) args.add("-property:TargetFramework=" + framework);
                else {
                    args.add("--framework");
                    args.add(framework);
                }
            }
        }
        return List.copyOf(args);
    }
}
