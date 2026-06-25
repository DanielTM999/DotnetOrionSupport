package dtm.ide.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LaunchSettings {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LaunchSettings() {
    }

    public record Profile(String name, String commandName, List<String> args,
                          Map<String, String> env, String applicationUrl, String workingDirectory) {

        public boolean isProjectCommand() {
            return commandName == null || commandName.isBlank() || commandName.equalsIgnoreCase("Project");
        }

        public Map<String, String> effectiveEnv() {
            Map<String, String> result = new LinkedHashMap<>(env == null ? Map.of() : env);
            if (applicationUrl != null && !applicationUrl.isBlank() && !result.containsKey("ASPNETCORE_URLS")) {
                result.put("ASPNETCORE_URLS", applicationUrl.trim());
            }
            return result;
        }
    }

    public static Path locate(Path projectFile) {
        if (projectFile == null) {
            return null;
        }
        Path dir = Files.isDirectory(projectFile) ? projectFile : projectFile.getParent();
        if (dir == null) {
            return null;
        }
        Path candidate = dir.resolve("Properties").resolve("launchSettings.json");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    public static List<Profile> read(Path projectFile) {
        Path file = locate(projectFile);
        if (file == null) {
            return List.of();
        }
        try {
            JsonNode profiles = MAPPER.readTree(Files.readString(file)).path("profiles");
            if (!profiles.isObject()) {
                return List.of();
            }
            List<Profile> result = new ArrayList<>();
            profiles.fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                result.add(new Profile(
                        entry.getKey(),
                        node.path("commandName").asText(""),
                        splitArgs(node.path("commandLineArgs").asText("")),
                        readEnv(node.path("environmentVariables")),
                        node.path("applicationUrl").asText(""),
                        node.path("workingDirectory").asText("")));
            });
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static List<Profile> runnableProfiles(Path projectFile) {
        List<Profile> result = new ArrayList<>();
        for (Profile profile : read(projectFile)) {
            if (profile.isProjectCommand()) {
                result.add(profile);
            }
        }
        return result;
    }

    public static Profile findProfile(Path projectFile, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Profile profile : read(projectFile)) {
            if (name.equals(profile.name())) {
                return profile;
            }
        }
        return null;
    }

    private static Map<String, String> readEnv(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> env = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText("")));
        return env;
    }

    static List<String> splitArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }
}
