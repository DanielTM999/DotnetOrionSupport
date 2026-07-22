package dtm.ide.iis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dtm.ide.run.LaunchSettings;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public final class IisLaunchSettings {

    public record Settings(boolean windowsAuthentication,
                           boolean anonymousAuthentication,
                           String iisExpressApplicationUrl,
                           int sslPort,
                           String iisApplicationUrl) {

        public static Settings defaults(String projectName) {
            return new Settings(false, true, defaultIisExpressUrl(projectName), 0, "");
        }
    }

    public record ProfileSpec(String name,
                              String commandName,
                              String applicationUrl,
                              String launchUrl,
                              boolean launchBrowser,
                              Map<String, String> environmentVariables) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SSL_PORT_BASE = 44300;
    private static final int SSL_PORT_RANGE = 100;
    private static final int HTTP_PORT_BASE = 30000;
    private static final int HTTP_PORT_RANGE = 20000;

    private IisLaunchSettings() {
    }

    public static Path file(Path projectFile) {
        Path directory = projectFile == null ? null : projectFile.getParent();
        if (directory == null) {
            return null;
        }
        return directory.resolve("Properties").resolve("launchSettings.json");
    }

    public static Settings read(Path projectFile) {
        Path file = file(projectFile);
        if (file == null || !Files.isRegularFile(file)) {
            return Settings.defaults(IisWebProject.projectName(projectFile));
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            JsonNode iisSettings = root.path("iisSettings");
            if (iisSettings.isMissingNode() || !iisSettings.isObject()) {
                return Settings.defaults(IisWebProject.projectName(projectFile));
            }
            return new Settings(
                    iisSettings.path("windowsAuthentication").asBoolean(false),
                    iisSettings.path("anonymousAuthentication").asBoolean(true),
                    iisSettings.path("iisExpress").path("applicationUrl")
                            .asText(defaultIisExpressUrl(IisWebProject.projectName(projectFile))),
                    iisSettings.path("iisExpress").path("sslPort").asInt(0),
                    iisSettings.path("iis").path("applicationUrl").asText(""));
        } catch (Exception e) {
            log.debug("Falha ao ler iisSettings: {}", e.getMessage());
            return Settings.defaults(IisWebProject.projectName(projectFile));
        }
    }

    public static void write(Path projectFile, Settings settings, List<ProfileSpec> profiles) throws Exception {
        Path file = file(projectFile);
        if (file == null) {
            throw new IllegalStateException("Projeto inválido para gravar launchSettings.json.");
        }
        ObjectNode root = readRoot(file);
        if (settings != null) {
            ObjectNode iisSettings = objectAt(root, "iisSettings");
            iisSettings.put("windowsAuthentication", settings.windowsAuthentication());
            iisSettings.put("anonymousAuthentication", settings.anonymousAuthentication());
            ObjectNode iisExpress = objectAt(iisSettings, "iisExpress");
            if (settings.iisExpressApplicationUrl() != null && !settings.iisExpressApplicationUrl().isBlank()) {
                iisExpress.put("applicationUrl", settings.iisExpressApplicationUrl());
            }
            if (settings.sslPort() > 0) {
                iisExpress.put("sslPort", settings.sslPort());
            } else {
                iisExpress.put("sslPort", 0);
            }
            if (settings.iisApplicationUrl() != null && !settings.iisApplicationUrl().isBlank()) {
                objectAt(iisSettings, "iis").put("applicationUrl", settings.iisApplicationUrl());
            }
        }

        ObjectNode profilesNode = objectAt(root, "profiles");
        for (ProfileSpec profile : profiles == null ? List.<ProfileSpec>of() : profiles) {
            ObjectNode node = objectAt(profilesNode, profile.name());
            node.put("commandName", profile.commandName());
            node.put("launchBrowser", profile.launchBrowser());
            if (profile.launchUrl() != null && !profile.launchUrl().isBlank()) {
                node.put("launchUrl", profile.launchUrl());
            }
            if (profile.applicationUrl() != null && !profile.applicationUrl().isBlank()
                    && !LaunchSettings.COMMAND_IIS_EXPRESS.equalsIgnoreCase(profile.commandName())) {
                node.put("applicationUrl", profile.applicationUrl());
            }
            Map<String, String> environment = profile.environmentVariables();
            if (environment != null && !environment.isEmpty()) {
                ObjectNode environmentNode = objectAt(node, "environmentVariables");
                for (Map.Entry<String, String> entry : environment.entrySet()) {
                    environmentNode.put(entry.getKey(), entry.getValue());
                }
            }
        }

        Files.createDirectories(file.getParent());
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                + System.lineSeparator());
    }

    private static ObjectNode readRoot(Path file) {
        if (Files.isRegularFile(file)) {
            try {
                JsonNode existing = MAPPER.readTree(Files.readString(file));
                if (existing instanceof ObjectNode node) {
                    return node;
                }
            } catch (Exception e) {
                log.debug("launchSettings.json inválido, será recriado: {}", e.getMessage());
            }
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", "https://json.schemastore.org/launchsettings.json");
        return root;
    }

    private static ObjectNode objectAt(ObjectNode parent, String field) {
        JsonNode existing = parent.get(field);
        if (existing instanceof ObjectNode node) {
            return node;
        }
        return parent.putObject(field);
    }

    public static List<ProfileSpec> toSpecs(List<LaunchSettings.Profile> profiles) {
        List<ProfileSpec> specs = new ArrayList<>();
        for (LaunchSettings.Profile profile : profiles) {
            specs.add(new ProfileSpec(profile.name(), profile.commandName(), profile.applicationUrl(),
                    profile.launchUrl(), profile.launchBrowser(),
                    new LinkedHashMap<>(profile.env() == null ? Map.of() : profile.env())));
        }
        return specs;
    }

    public static String defaultIisExpressUrl(String projectName) {
        return "http://localhost:" + defaultHttpPort(projectName);
    }

    public static int defaultHttpPort(String projectName) {
        return HTTP_PORT_BASE + Math.floorMod(stableHash(projectName), HTTP_PORT_RANGE);
    }

    public static int defaultSslPort(String projectName) {
        return SSL_PORT_BASE + Math.floorMod(stableHash(projectName), SSL_PORT_RANGE);
    }

    private static int stableHash(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        int hash = 7;
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            hash = hash * 31 + c;
        }
        return hash;
    }

    public static List<IisBinding> bindingsOf(Settings settings, String projectName) {
        List<IisBinding> bindings = new ArrayList<>();
        String applicationUrl = settings == null || settings.iisExpressApplicationUrl() == null
                || settings.iisExpressApplicationUrl().isBlank()
                ? defaultIisExpressUrl(projectName)
                : settings.iisExpressApplicationUrl();
        for (String url : applicationUrl.split(";")) {
            IisBinding binding = fromUrl(url.strip());
            if (binding != null) {
                bindings.add(binding);
            }
        }
        if (settings != null && settings.sslPort() > 0) {
            bindings.add(new IisBinding("https", "*", String.valueOf(settings.sslPort()), "localhost"));
        }
        if (bindings.isEmpty()) {
            bindings.add(new IisBinding("http", "*", String.valueOf(defaultHttpPort(projectName)), "localhost"));
        }
        return bindings;
    }

    public record IisTarget(IisBinding binding, String applicationPath) {
    }

    public static IisTarget iisTarget(Settings settings, String projectName) {
        String url = settings == null || settings.iisApplicationUrl() == null
                || settings.iisApplicationUrl().isBlank()
                ? "http://localhost/" + projectName
                : settings.iisApplicationUrl();
        try {
            java.net.URI uri = java.net.URI.create(url.strip());
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            String host = uri.getHost() == null ? "localhost" : uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : Integer.parseInt(IisBinding.defaultPort(scheme));
            IisBinding binding = new IisBinding(scheme, "*", String.valueOf(port), host);
            return new IisTarget(binding, IisService.normalizePath(uri.getPath()));
        } catch (Exception e) {
            return new IisTarget(new IisBinding("http", "*", "80", "localhost"),
                    IisService.normalizePath("/" + projectName));
        }
    }

    public static IisBinding fromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.strip());
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            String host = uri.getHost() == null ? "localhost" : uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : Integer.parseInt(IisBinding.defaultPort(scheme));
            String hostName = "localhost".equalsIgnoreCase(host) || "*".equals(host) ? "localhost" : host;
            return new IisBinding(scheme, "*", String.valueOf(port), hostName);
        } catch (Exception e) {
            return null;
        }
    }
}
