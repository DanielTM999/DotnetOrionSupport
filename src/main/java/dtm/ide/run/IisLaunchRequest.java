package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunBreakpointData;
import dtm.ide.iis.IisBinding;
import dtm.ide.iis.IisWebProject;
import lombok.Builder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Builder
public record IisLaunchRequest(Path projectFile,
                               Path workspaceRoot,
                               Path dotnet,
                               Path netcoredbg,
                               String configuration,
                               String targetFramework,
                               boolean iisExpress,
                               boolean debug,
                               String siteName,
                               String applicationPath,
                               String appPoolName,
                               List<IisBinding> bindings,
                               String launchUrl,
                               boolean launchBrowser,
                               Map<String, String> environment,
                               List<RunBreakpointData> breakpoints,
                               DotnetDebugView debugView,
                               Consumer<DotnetDapDebugSession> sessionSink,
                               boolean breakOnAllExceptions,
                               boolean stopPoolOnExit,
                               boolean autoCreateSite) {

    public IisWebProject.HostingModel hostingModel() {
        return IisWebProject.hostingModel(projectFile);
    }

    public boolean aspNetCore() {
        return IisWebProject.isAspNetCore(projectFile);
    }

    public String assemblyName() {
        return IisWebProject.assemblyName(projectFile);
    }

    public String effectiveConfiguration() {
        return configuration == null || configuration.isBlank() ? "Debug" : configuration;
    }

    public IisBinding primaryBinding() {
        if (bindings == null || bindings.isEmpty()) {
            return null;
        }
        for (IisBinding binding : bindings) {
            if ("http".equalsIgnoreCase(binding.protocol())) {
                return binding;
            }
        }
        return bindings.getFirst();
    }

    public String resolveUrl() {
        IisBinding binding = primaryBinding();
        String base = binding == null ? "http://localhost" : binding.url();
        String path = iisExpress ? "" : trimmedApplicationPath();
        String suffix = launchUrl == null ? "" : launchUrl.strip();
        if (suffix.startsWith("http://") || suffix.startsWith("https://")) {
            return suffix;
        }
        String url = base + path;
        if (suffix.isEmpty()) {
            return url;
        }
        return url + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }

    private String trimmedApplicationPath() {
        if (applicationPath == null || applicationPath.isBlank() || "/".equals(applicationPath)) {
            return "";
        }
        String value = applicationPath.strip();
        return value.startsWith("/") ? value : "/" + value;
    }
}
