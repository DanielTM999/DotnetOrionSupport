package dtm.ide.reference;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class ProjectReferenceService {

    private static final Pattern PROJECT_REFERENCE = Pattern.compile(
            "<ProjectReference\\s+[^>]*Include\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private ProjectReferenceService() {
    }

    public static List<Path> listProjectReferences(Path csproj) {
        List<Path> result = new ArrayList<>();
        if (csproj == null || !Files.isRegularFile(csproj)) {
            return result;
        }
        Path dir = csproj.getParent();
        try {
            Matcher matcher = PROJECT_REFERENCE.matcher(Files.readString(csproj));
            while (matcher.find()) {
                String relative = matcher.group(1).trim().replace('\\', '/');
                if (relative.isEmpty()) {
                    continue;
                }
                Path resolved = dir == null ? Path.of(relative) : dir.resolve(relative).normalize();
                result.add(resolved.toAbsolutePath().normalize());
            }
        } catch (Exception e) {
            log.debug("Falha ao ler ProjectReference de {}: {}", csproj, e.getMessage());
        }
        return result;
    }

    public static int addProjectReference(Path dotnet, Path targetCsproj, Path refCsproj, OutputStream log) {
        return runDotnet(dotnet, targetCsproj,
                List.of("add", targetCsproj.toString(), "reference", refCsproj.toString()), log);
    }

    public static int removeProjectReference(Path dotnet, Path targetCsproj, Path refCsproj, OutputStream log) {
        return runDotnet(dotnet, targetCsproj,
                List.of("remove", targetCsproj.toString(), "reference", refCsproj.toString()), log);
    }

    private static int runDotnet(Path dotnet, Path target, List<String> args, OutputStream log) {
        if (dotnet == null || target == null) {
            writeLine(log, "[erro] dotnet ou projeto alvo indisponível.");
            return -1;
        }
        List<String> command = new ArrayList<>();
        command.add(dotnet.toString());
        command.addAll(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            Path dir = target.getParent();
            if (dir != null) {
                builder.directory(dir.toFile());
            }
            builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
            builder.environment().put("DOTNET_NOLOGO", "1");
            writeLine(log, "> " + String.join(" ", command));
            Process process = builder.start();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (log != null) {
                        log.write(buffer, 0, read);
                        log.flush();
                    }
                }
            }
            return process.waitFor();
        } catch (Exception e) {
            writeLine(log, "[erro] " + e.getMessage());
            return -1;
        }
    }

    private static void writeLine(OutputStream out, String text) {
        if (out == null) {
            return;
        }
        try {
            out.write((text + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        }
    }
}
