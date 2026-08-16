package dtm.ide.run;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
final class DotnetHotReloadAgent implements AutoCloseable {

    private static final String CACHE_VERSION = "v5";
    private static final Pattern SDK_LINE = Pattern.compile("^\\s*([^\\s]+)\\s+\\[(.+)]\\s*$");

    private final Path dotnet;
    private final Path projectFile;
    private final Path assemblyFile;
    private final Path cwd;
    private final String targetFramework;
    private final String configuration;
    private final OutputStream output;
    private final Path resourceRoot;

    private Process process;
    private BufferedReader reader;
    private Writer writer;

    DotnetHotReloadAgent(Path dotnet, Path projectFile, Path assemblyFile, Path cwd,
                         String targetFramework, String configuration, OutputStream output,
                         Path resourceRoot) {
        this.dotnet = dotnet;
        this.projectFile = projectFile;
        this.assemblyFile = assemblyFile;
        this.cwd = cwd;
        this.targetFramework = targetFramework == null ? "" : targetFramework;
        this.configuration = configuration == null || configuration.isBlank() ? "Debug" : configuration;
        this.output = output;
        this.resourceRoot = resourceRoot;
    }

    synchronized void prewarm() {
        try {
            ensureStarted();
        } catch (Exception e) {
            log.debug("[hot reload] prewarm do agente falhou: {}", oneLine(e.getMessage()));
        }
    }

    synchronized DotnetHotReloadResult apply(Path activeFile, String activeText) {
        try {
            ensureStarted();
            String command = buildApplyCommand(activeFile, activeText);
            writer.write(command);
            writer.write('\n');
            writer.flush();
            String line = reader.readLine();
            if (line == null) {
                closeProcess();
                return DotnetHotReloadResult.error("Agente de Hot Reload encerrou sem resposta.");
            }
            return parseResult(line);
        } catch (Exception e) {
            closeProcess();
            return DotnetHotReloadResult.error("Falha no agente de Hot Reload: " + oneLine(e.getMessage()));
        }
    }

    private void ensureStarted() throws IOException, InterruptedException {
        if (process != null && process.isAlive() && reader != null && writer != null) {
            return;
        }
        Path agentDll = ensureBuilt();
        Path deltaDir = cwd.resolve(".orion").resolve("orion-hot-reload").resolve("deltas");
        Files.createDirectories(deltaDir);
        String assemblyName = assemblyFile.getFileName().toString().replaceFirst("(?i)\\.dll$", "");
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().normalize().toString());
        command.add("exec");
        command.add(agentDll.toAbsolutePath().normalize().toString());
        command.add(projectFile.toAbsolutePath().normalize().toString());
        command.add(assemblyFile.toAbsolutePath().normalize().toString());
        command.add(deltaDir.toAbsolutePath().normalize().toString());
        command.add(configuration);
        command.add(targetFramework);
        command.add(assemblyName);

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(false);
        applyDotnetEnv(builder);
        process = builder.start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread stderr = new Thread(() -> pumpStderr(process.getErrorStream()), "dotnet-hotreload-agent-stderr");
        stderr.setDaemon(true);
        stderr.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("Agente de Hot Reload encerrou durante inicializacao.");
            }
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null && line.startsWith("READY\t")) {
                    writeOutput("[hot reload] agente Roslyn pronto." + System.lineSeparator());
                    return;
                }
                DotnetHotReloadResult result = parseResult(line);
                throw new IOException(result.message());
            }
            Thread.sleep(80);
        }
        throw new IOException("Tempo esgotado ao inicializar o agente de Hot Reload.");
    }

    private Path ensureBuilt() throws IOException, InterruptedException {
        if (resourceRoot == null) {
            throw new IOException("Diretorio de resources indisponivel para o agente de Hot Reload.");
        }
        SdkLayout sdk = resolveSdkLayout();
        Path root = resourceRoot.resolve("hotreload-agent").resolve(CACHE_VERSION);
        Path sourceDir = root.resolve("src");
        Files.createDirectories(sourceDir);
        copyResource("/hotreload/OrionHotReloadAgent/Program.cs", sourceDir.resolve("Program.cs"));
        Files.writeString(sourceDir.resolve("OrionHotReloadAgent.csproj"), projectFileText(sdk), StandardCharsets.UTF_8);
        Path dll = sourceDir.resolve("bin").resolve("Release").resolve(sdk.targetFramework()).resolve("OrionHotReloadAgent.dll");
        if (!Files.isRegularFile(dll)) {
            List<String> command = List.of(dotnet.toAbsolutePath().normalize().toString(), "build", "-c", "Release", "--nologo");
            ProcessBuilder builder = new ProcessBuilder(command).directory(sourceDir.toFile()).redirectErrorStream(true);
            applyDotnetEnv(builder);
            Process build = builder.start();
            String logText;
            try (InputStream in = build.getInputStream()) {
                logText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!build.waitFor(90, TimeUnit.SECONDS)) {
                build.destroyForcibly();
                throw new IOException("Build do agente de Hot Reload excedeu o tempo limite.");
            }
            if (build.exitValue() != 0 || !Files.isRegularFile(dll)) {
                throw new IOException("Build do agente de Hot Reload falhou: " + oneLine(logText));
            }
        }
        copyBuildHost(sdk.formatDir(), dll.getParent());
        return dll;
    }

    private SdkLayout resolveSdkLayout() throws IOException, InterruptedException {
        String version = runDotnet(List.of("--version")).trim();
        String target = "net" + majorOf(version) + ".0";
        String sdkList = runDotnet(List.of("--list-sdks"));
        Path sdkDir = null;
        for (String line : sdkList.split("\\R")) {
            Matcher matcher = SDK_LINE.matcher(line);
            if (matcher.matches() && matcher.group(1).equals(version)) {
                sdkDir = Path.of(matcher.group(2)).resolve(matcher.group(1));
                break;
            }
        }
        if (sdkDir == null && dotnet.getParent() != null) {
            Path candidate = dotnet.toAbsolutePath().normalize().getParent().resolve("sdk").resolve(version);
            if (Files.isDirectory(candidate)) {
                sdkDir = candidate;
            }
        }
        if (sdkDir == null || !Files.isDirectory(sdkDir)) {
            throw new IOException("Nao foi possivel localizar o SDK .NET usado pelo Hot Reload (" + version + ").");
        }
        Path format = sdkDir.resolve("DotnetTools").resolve("dotnet-format");
        if (!Files.isDirectory(format)) {
            throw new IOException("dotnet-format nao encontrado no SDK: " + format);
        }
        return new SdkLayout(sdkDir, format, target);
    }

    private String projectFileText(SdkLayout sdk) {
        List<String> refs = List.of(
                "Microsoft.CodeAnalysis.dll",
                "Microsoft.CodeAnalysis.CSharp.dll",
                "Microsoft.CodeAnalysis.CSharp.Features.dll",
                "Microsoft.CodeAnalysis.Features.dll",
                "Microsoft.CodeAnalysis.Workspaces.dll",
                "Microsoft.CodeAnalysis.CSharp.Workspaces.dll",
                "Microsoft.CodeAnalysis.Workspaces.MSBuild.dll",
                "Microsoft.CodeAnalysis.Workspaces.MSBuild.Contracts.dll",
                "Microsoft.Build.dll",
                "Microsoft.Build.Framework.dll",
                "Microsoft.Build.Utilities.Core.dll",
                "Microsoft.DiaSymReader.dll",
                "Microsoft.Extensions.Logging.Abstractions.dll",
                "Microsoft.Extensions.Logging.dll",
                "Microsoft.Extensions.Options.dll",
                "Microsoft.Extensions.Primitives.dll",
                "System.Composition.AttributedModel.dll",
                "System.Composition.Convention.dll",
                "System.Composition.Hosting.dll",
                "System.Composition.Runtime.dll",
                "System.Composition.TypedParts.dll");
        StringBuilder xml = new StringBuilder();
        xml.append("<Project Sdk=\"Microsoft.NET.Sdk\">\n");
        xml.append("  <PropertyGroup>\n");
        xml.append("    <OutputType>Exe</OutputType>\n");
        xml.append("    <TargetFramework>").append(sdk.targetFramework()).append("</TargetFramework>\n");
        xml.append("    <Nullable>enable</Nullable>\n");
        xml.append("    <ImplicitUsings>enable</ImplicitUsings>\n");
        xml.append("  </PropertyGroup>\n");
        xml.append("  <ItemGroup>\n");
        for (String ref : refs) {
            Path hint = sdk.formatDir().resolve(ref);
            if (Files.isRegularFile(hint)) {
                String name = ref.substring(0, ref.length() - 4);
                xml.append("    <Reference Include=\"").append(escapeXml(name)).append("\" HintPath=\"")
                        .append(escapeXml(hint.toString())).append("\" Private=\"true\" />\n");
            }
        }
        xml.append("  </ItemGroup>\n");
        xml.append("</Project>\n");
        return xml.toString();
    }

    private String runDotnet(List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(dotnet.toAbsolutePath().normalize().toString());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        applyDotnetEnv(builder);
        Process p = builder.start();
        String text;
        try (InputStream in = p.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("dotnet " + String.join(" ", args) + " falhou: " + oneLine(text));
        }
        return text;
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = DotnetHotReloadAgent.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Recurso nao encontrado: " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyBuildHost(Path formatDir, Path targetDir) throws IOException {
        Path source = formatDir.resolve("BuildHost-netcore");
        if (!Files.isDirectory(source)) {
            return;
        }
        Path target = targetDir.resolve("BuildHost-netcore");
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path item : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(item);
                Path dest = target.resolve(relative.toString());
                if (Files.isDirectory(item)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(item, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String buildApplyCommand(Path activeFile, String activeText) {
        if (activeFile == null || activeText == null) {
            return "APPLY";
        }
        String lower = activeFile.getFileName() == null ? "" : activeFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".cs")) {
            return "APPLY";
        }
        String file = Base64.getEncoder().encodeToString(activeFile.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
        String text = Base64.getEncoder().encodeToString(activeText.getBytes(StandardCharsets.UTF_8));
        return "APPLY\t" + file + "\t" + text;
    }

    private DotnetHotReloadResult parseResult(String line) {
        if (line == null || line.isBlank()) {
            return DotnetHotReloadResult.error("Resposta vazia do agente de Hot Reload.");
        }
        String[] parts = line.split("\\t", 2);
        String status = parts[0].trim().toUpperCase(Locale.ROOT);
        String message = parts.length > 1 ? parts[1].trim() : "";
        return switch (status) {
            case "OK" -> DotnetHotReloadResult.applied(message);
            case "NOCHANGES" -> DotnetHotReloadResult.noChanges(message.isBlank() ? "sem alteracoes" : message);
            case "BLOCKED" -> DotnetHotReloadResult.blocked(message.isBlank() ? "Alteracao nao suportada por Hot Reload." : message);
            default -> DotnetHotReloadResult.error(message.isBlank() ? line : message);
        };
    }

    private void applyDotnetEnv(ProcessBuilder builder) {
        builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        builder.environment().put("DOTNET_MODIFIABLE_ASSEMBLIES", "debug");
        Path root = dotnetRoot();
        if (root == null) {
            return;
        }
        String value = root.toString();
        builder.environment().put("DOTNET_ROOT", value);
        builder.environment().put("DOTNET_ROOT_X64", value);
        builder.environment().put("DOTNET_ROOT(x86)", value);
        builder.environment().put("DOTNET_HOST_PATH", dotnet.toAbsolutePath().normalize().toString());
        String pathKey = pathEnvName(builder.environment());
        String path = builder.environment().get(pathKey);
        builder.environment().put(pathKey, value + java.io.File.pathSeparator + (path == null ? "" : path));
    }

    private Path dotnetRoot() {
        return dotnet == null ? null : dotnet.toAbsolutePath().normalize().getParent();
    }

    private static String pathEnvName(Map<String, String> env) {
        for (String key : env.keySet()) {
            if ("PATH".equalsIgnoreCase(key)) {
                return key;
            }
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "Path" : "PATH";
    }

    private static int majorOf(String version) {
        try {
            return Math.max(8, Integer.parseInt(version.split("\\.")[0]));
        } catch (Exception e) {
            return 8;
        }
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void pumpStderr(InputStream stderr) {
        try (stderr) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stderr.read(buffer)) != -1) {
                String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                log.debug("[hot reload agent] {}", text.stripTrailing());
            }
        } catch (IOException ignored) {
        }
    }

    private void writeOutput(String text) {
        try {
            synchronized (output) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (writer != null) {
                writer.write("EXIT\n");
                writer.flush();
            }
        } catch (IOException ignored) {
        }
        closeProcess();
    }

    private void closeProcess() {
        Process p = process;
        process = null;
        reader = null;
        writer = null;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(1200, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }

    private static String oneLine(String value) {
        return value == null || value.isBlank()
                ? "erro desconhecido"
                : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
    }

    private record SdkLayout(Path sdkDir, Path formatDir, String targetFramework) {
    }
}
