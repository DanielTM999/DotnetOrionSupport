package dtm.ide.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.coverage.CoberturaReportReader;
import dtm.ide.coverage.CoverageReport;
import dtm.ide.run.DotnetBuild;
import dtm.ide.run.TargetFramework;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared execution/report boundary for the explorer and editor actions. */
public final class DotnetTestService {
    private static final java.util.regex.Pattern DEBUG_PROCESS_ID = java.util.regex.Pattern.compile(
            "(?:process(?:o)?\\s*(?:id|pid)|pid)\\D+(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    public enum Platform { VSTEST, MTP }
    public record Run(Process process, Path results, Platform platform, Map<Path, String> sourceHashes, List<String> command) {
        public Run { sourceHashes = Map.copyOf(sourceHashes); command = List.copyOf(command); }
    }
    public record Results(List<TestResult> tests, CoverageReport coverage, List<String> warnings) {}
    public record Discovery(List<String> names, String output) {}
    private final DotnetBuild build = new DotnetBuild();

    public List<String> discover(Path root, Path dotnet, String configuration) {
        return build.listTests(root, dotnet, configuration);
    }

    public List<String> discover(Path root, Path dotnet, String configuration, Path target, String framework) throws IOException {
        return discoverDetailed(root, dotnet, configuration, target, framework).names();
    }

    public Discovery discoverDetailed(Path root, Path dotnet, String configuration, Path target, String framework) throws IOException {
        Platform platform = platform(root);
        var args = baseArguments(dotnet, configuration, target, framework, platform);
        args.add("--list-tests");
        String output = build.captureTestCommand(root, dotnet, args);
        return new Discovery(parseDiscovery(output, platform), output);
    }

    public static List<String> parseDiscovery(String output, Platform platform) {
        List<String> names = new ArrayList<>();
        boolean header = platform == Platform.MTP;
        for (String raw : output.split("\\R")) {
            String name = raw.strip();
            if (name.toLowerCase(java.util.Locale.ROOT).contains("available:")) { header = true; continue; }
            boolean candidate = platform == Platform.VSTEST ? raw.startsWith("    ") && !name.isBlank()
                    : raw.startsWith("  ") && !name.isBlank() && Character.isJavaIdentifierStart(name.charAt(0));
            if (header && candidate
                    && !name.contains(" -> ") && !name.contains(": warning ") && !name.contains(": error ")) names.add(name);
        }
        return names.stream().distinct().toList();
    }

    public Run start(Path root, Path dotnet, String configuration, String filter, boolean debug, boolean coverage)
            throws IOException {
        return start(root, dotnet, configuration, filter, debug, coverage, null, null);
    }

    public Run start(Path root, Path dotnet, String configuration, String filter, boolean debug, boolean coverage,
                     Path target, String framework) throws IOException {
        Platform platform = platform(root);
        Path results = Files.createDirectories(root.toAbsolutePath().normalize().resolve(".orion/test-results/" + java.util.UUID.randomUUID()));
        List<String> args = platform == Platform.MTP && debug
                ? debugArguments(dotnet, configuration, target, framework)
                : baseArguments(dotnet, configuration, target, framework, platform);
        args.addAll(filterArguments(platform, target == null ? root : target, filter));
        if (platform == Platform.MTP) {
            args.addAll(List.of("--report-trx", "--results-directory", results.toString()));
            if (debug) args.add("--debug");
            if (coverage) args.addAll(List.of("--coverlet", "--coverlet-output-format", "cobertura"));
        } else {
            args.addAll(List.of("--logger", "trx;LogFilePrefix=orion", "--results-directory", results.toString()));
            if (coverage) args.addAll(List.of("--collect", "XPlat Code Coverage"));
        }
        Map<Path, String> sources = coverage ? sourceHashes(root) : Map.of();
        return new Run(build.launchTestCommand(root, dotnet, args, debug && platform == Platform.VSTEST), results, platform, sources, args);
    }

    public static List<String> debugArguments(Path dotnet, String configuration, Path target, String framework) throws IOException {
        if (target == null || !Files.isRegularFile(target) || !target.toString().endsWith("proj"))
            throw new IOException("Select one test project in Build Tools before debugging MTP tests.");
        if ((framework == null || framework.isBlank()) && TargetFramework.readTfms(target).size() > 1)
            throw new IOException("Select a target framework in Build Tools before debugging MTP tests.");
        // Native dotnet test buffers the waiting process's output. Direct run streams its debugger PID.
        var args = new ArrayList<>(List.of(dotnet.toAbsolutePath().toString(), "run", "--project", target.toAbsolutePath().toString(),
                "--configuration", configuration == null || configuration.isBlank() ? "Debug" : configuration, "--no-launch-profile"));
        if (framework != null && !framework.isBlank()) args.addAll(List.of("--framework", framework));
        args.add("--");
        return args;
    }

    public static Long debugProcessId(String line) {
        if (line == null) return null;
        var matcher = DEBUG_PROCESS_ID.matcher(line);
        if (!matcher.find()) return null;
        try { long pid = Long.parseLong(matcher.group(1)); return pid > 0 ? pid : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    public static List<String> baseArguments(Path dotnet, String configuration, Path target, String framework, Platform platform) {
        List<String> args = new ArrayList<>(List.of(dotnet.toAbsolutePath().toString(), "test", "-c",
                configuration == null || configuration.isBlank() ? "Debug" : configuration));
        if (platform == Platform.VSTEST) args.add("--nologo");
        if (target != null) {
            if (platform == Platform.MTP) args.add(target.toString().matches("(?i).*\\.slnx?") ? "--solution" : "--project");
            args.add(target.toAbsolutePath().toString());
        }
        if (framework != null && !framework.isBlank()) args.addAll(List.of("--framework", framework));
        return args;
    }

    public static List<String> filterArguments(Platform platform, Path target, String filter) throws IOException {
        if (filter == null || filter.isBlank()) return List.of();
        if (platform == Platform.VSTEST) return List.of("--filter", filter);
        List<Path> projects = Files.isRegularFile(target) && target.toString().endsWith("proj") ? List.of(target)
                : TargetFramework.findProjectFiles(target);
        boolean xunit = false, other = false;
        for (Path project : projects) {
            String source = Files.readString(project).toLowerCase(java.util.Locale.ROOT);
            xunit |= source.contains("xunit.v3");
            other |= source.contains("mstest") || source.contains("nunit");
        }
        if (!xunit) return List.of("--filter", filter);
        if (other) throw new IOException("Select one test project before filtering a mixed xUnit/MSTest/NUnit MTP solution.");
        List<String> args = new ArrayList<>(List.of("--filter-method"));
        for (String part : filter.split("\\|")) {
            if (!part.matches("FullyQualifiedName[=~][\\p{L}\\p{N}_.+`]+"))
                throw new IOException("For xUnit MTP, select a test/class in the tree. VSTest trait expressions cannot be translated safely.");
            String name = part.substring("FullyQualifiedName".length() + 1);
            args.add(part.charAt("FullyQualifiedName".length()) == '~' ? "*" + name + "*" : name);
        }
        return args;
    }

    public Results read(Run run, Path workspace) throws IOException {
        List<TestResult> tests = new ArrayList<>();
        List<CoverageReport> coverage = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        var coverageReader = new CoberturaReportReader();
        try (var paths = Files.walk(run.results())) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (run.results().relativize(path).getNameCount() > 1 && run.results().relativize(path).getName(0).toString().equals("merged")) continue;
                try {
                    if (path.toString().endsWith(".trx")) tests.addAll(new TrxReportReader().read(path));
                    if (path.getFileName().toString().matches("(?i).*cobertura(?:[._-][\\w.-]+)?\\.xml"))
                        coverage.add(coverageReader.read(path, workspace));
                } catch (IOException e) { warnings.add(e.getMessage()); }
            }
        }
        if (tests.isEmpty()) warnings.add("No TRX results were produced. Check the console and the test runner's TRX extension.");
        if (coverage.isEmpty() && (run.command().contains("--coverlet") || run.command().contains("--collect")))
            warnings.add("No Cobertura report was produced. Check the installed Coverlet extension and its compatibility with the test runner.");
        CoverageReport merged = coverageReader.merge(coverage);
        Map<Path, Map<Integer, Long>> current = new java.util.LinkedHashMap<>();
        Map<Path, String> hashes = new java.util.LinkedHashMap<>();
        merged.files().forEach((file, lines) -> {
            String hash = merged.sourceHashes().get(file);
            if (java.util.Objects.equals(run.sourceHashes().get(file), hash) && hash != null) {
                current.put(file, lines); hashes.put(file, hash);
            } else warnings.add("Coverage discarded because source changed during execution: " + file);
        });
        return new Results(List.copyOf(tests), new CoverageReport(current, hashes), List.copyOf(warnings));
    }

    private static Map<Path, String> sourceHashes(Path workspace) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Map<Path, String> hashes = new java.util.LinkedHashMap<>();
        int[] visited = {0};
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                String name = dir.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return !dir.equals(root) && (name.startsWith(".") || java.util.Set.of("bin", "obj", "node_modules", "packages", "target").contains(name))
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Coverage cancelled");
                if (++visited[0] > 10000) return java.nio.file.FileVisitResult.TERMINATE;
                if (attrs.isRegularFile() && attrs.size() <= 2_000_000 && file.toString().endsWith(".cs")) hashes.put(file, CoverageReport.hash(Files.readString(file)));
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return Map.copyOf(hashes);
    }

    public static Platform platform(Path root) throws IOException {
        String override = System.getenv("DOTNET_TEST_RUNNER");
        if (override != null && !override.isBlank()) return "Microsoft.Testing.Platform".equalsIgnoreCase(override) ? Platform.MTP : Platform.VSTEST;
        for (Path dir = Files.isDirectory(root) ? root : root.getParent(); dir != null; dir = dir.getParent()) {
            Path global = dir.resolve("global.json");
            if (Files.isRegularFile(global)) {
                var json = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS)
                        .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA).readTree(global.toFile());
                return "Microsoft.Testing.Platform".equalsIgnoreCase(json.path("test").path("runner").asText())
                        ? Platform.MTP : Platform.VSTEST;
            }
        }
        return Platform.VSTEST;
    }

    public void stop() { build.stopTests(); }
}
