package dtm.ide.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class TargetFramework {

    private static final Pattern TARGET_FRAMEWORK =
            Pattern.compile("<TargetFramework>\\s*([^<\\s]+)\\s*</TargetFramework>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_FRAMEWORKS =
            Pattern.compile("<TargetFrameworks>\\s*([^<]+)</TargetFrameworks>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_FRAMEWORK_VERSION =
            Pattern.compile("<TargetFrameworkVersion>\\s*([^<\\s]+)\\s*</TargetFrameworkVersion>", Pattern.CASE_INSENSITIVE);

    private static final Pattern NET_FRAMEWORK_TFM = Pattern.compile("net\\d{2,3}");
    private static final Pattern NET_MODERN_TFM = Pattern.compile("net\\d+\\.\\d+.*");
    private static final Pattern NET_MODERN_MAJOR = Pattern.compile("net(\\d+)\\.\\d+.*");
    private static final Pattern NETCOREAPP_MAJOR = Pattern.compile("netcoreapp(\\d+)\\.\\d+.*");

    private static final Pattern SLN_PROJECT = Pattern.compile(
            "Project\\(\"\\{[^}]*\\}\"\\)\\s*=\\s*\"[^\"]*\",\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SLNX_PROJECT = Pattern.compile(
            "<Project\\s+[^>]*Path\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static final Set<String> PROJECT_EXTENSIONS = Set.of(".csproj", ".vbproj", ".fsproj");

    private TargetFramework() {
    }

    public static boolean isNetFramework(String tfm) {
        if (tfm == null) {
            return false;
        }
        String t = tfm.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return false;
        }
        if (t.startsWith("v")) {
            return true;
        }
        if (t.startsWith("netframework")) {
            return true;
        }
        return NET_FRAMEWORK_TFM.matcher(t).matches();
    }

    public static boolean isModern(String tfm) {
        if (tfm == null) {
            return false;
        }
        String t = tfm.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("netcoreapp") || NET_MODERN_TFM.matcher(t).matches();
    }

    public static boolean isNetStandard(String tfm) {
        if (tfm == null) {
            return false;
        }
        return tfm.trim().toLowerCase(Locale.ROOT).startsWith("netstandard");
    }

    public static boolean isRunnableModernOnHost(String tfm) {
        return isRunnableModernOnHost(tfm, isWindows());
    }

    static boolean isRunnableModernOnHost(String tfm, boolean windows) {
        if (!isModern(tfm) || isNetStandard(tfm)) {
            return false;
        }
        String t = tfm.trim().toLowerCase(Locale.ROOT);
        int dash = t.indexOf('-');
        if (dash < 0) {
            return true;
        }
        String platform = t.substring(dash + 1);
        return platform.startsWith("windows") && windows;
    }

    public static OptionalInt modernMajor(String tfm) {
        if (tfm == null) {
            return OptionalInt.empty();
        }
        String t = tfm.trim().toLowerCase(Locale.ROOT);
        Matcher modern = NET_MODERN_MAJOR.matcher(t);
        if (modern.matches()) {
            return parseMajor(modern.group(1));
        }
        Matcher coreApp = NETCOREAPP_MAJOR.matcher(t);
        if (coreApp.matches()) {
            return parseMajor(coreApp.group(1));
        }
        return OptionalInt.empty();
    }

    public static OptionalInt requiredModernMajor(Path projectRoot) {
        int major = -1;
        for (String tfm : resolveTfms(projectRoot)) {
            OptionalInt current = modernMajor(tfm);
            if (current.isPresent()) {
                major = Math.max(major, current.getAsInt());
            }
        }
        return major < 0 ? OptionalInt.empty() : OptionalInt.of(major);
    }

    public static Optional<String> selectRunnableModernTfm(Path projectRoot) {
        return selectRunnableModernTfm(resolveTfms(projectRoot), isWindows());
    }

    static Optional<String> selectRunnableModernTfm(List<String> tfms, boolean windows) {
        String selected = null;
        int selectedMajor = -1;
        for (String tfm : tfms == null ? List.<String>of() : tfms) {
            if (!isRunnableModernOnHost(tfm, windows)) {
                continue;
            }
            int major = modernMajor(tfm).orElse(0);
            if (selected == null || major > selectedMajor) {
                selected = tfm.trim();
                selectedMajor = major;
            }
        }
        return Optional.ofNullable(selected);
    }

    public static Optional<String> selectRunnableTfm(Path projectRoot) {
        return selectRunnableTfm(resolveTfms(projectRoot), isWindows());
    }

    static Optional<String> selectRunnableTfm(List<String> tfms, boolean windows) {
        Optional<String> modern = selectRunnableModernTfm(tfms, windows);
        if (modern.isPresent()) {
            return modern;
        }
        if (tfms != null) {
            for (String tfm : tfms) {
                if (windows && isNetFramework(tfm)) {
                    return Optional.of(tfm.trim());
                }
            }
        }
        return Optional.empty();
    }

    public static boolean canRunAnyOnHost(Path projectRoot) {
        List<String> tfms = resolveTfms(projectRoot);
        if (tfms.isEmpty()) {
            return true;
        }
        return selectRunnableTfm(tfms, isWindows()).isPresent();
    }

    private static OptionalInt parseMajor(String value) {
        try {
            return OptionalInt.of(Integer.parseInt(value));
        } catch (Exception e) {
            return OptionalInt.empty();
        }
    }

    public static List<String> readTfms(Path projectFile) {
        List<String> tfms = new ArrayList<>();
        if (projectFile == null || !Files.isRegularFile(projectFile)) {
            return tfms;
        }
        String content;
        try {
            content = Files.readString(projectFile);
        } catch (Exception e) {
            return tfms;
        }
        Matcher single = TARGET_FRAMEWORK.matcher(content);
        if (single.find()) {
            tfms.add(single.group(1).trim());
        }
        Matcher multi = TARGET_FRAMEWORKS.matcher(content);
        if (multi.find()) {
            for (String part : multi.group(1).split(";")) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    tfms.add(value);
                }
            }
        }
        Matcher legacy = TARGET_FRAMEWORK_VERSION.matcher(content);
        if (legacy.find()) {
            tfms.add(legacy.group(1).trim());
        }
        return tfms;
    }

    public static Path findPrimaryProjectFile(Path projectRoot) {
        if (projectRoot == null) {
            return null;
        }
        Path dir = Files.isDirectory(projectRoot) ? projectRoot : projectRoot.getParent();
        if (dir == null) {
            return null;
        }

        Path top = firstProjectIn(dir);
        if (top != null) {
            return top;
        }

        try (Stream<Path> walk = Files.walk(dir, 3)) {
            return walk.filter(Files::isRegularFile)
                    .filter(TargetFramework::isProjectFile)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public static List<Path> findProjectFiles(Path projectRoot) {
        if (projectRoot == null) {
            return List.of();
        }
        Path dir = Files.isDirectory(projectRoot) ? projectRoot : projectRoot.getParent();
        if (dir == null) {
            return List.of();
        }
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        for (Path solution : solutionsIn(dir)) {
            result.addAll(parseSolutionProjects(solution));
        }
        if (result.isEmpty()) {
            try (Stream<Path> walk = Files.walk(dir, 4)) {
                walk.filter(Files::isRegularFile)
                        .filter(TargetFramework::isProjectFile)
                        .forEach(result::add);
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(result);
    }

    public static List<Path> findProjectFilesInSolution(Path solution) {
        if (solution == null || !isSolutionFile(solution)) {
            return List.of();
        }
        return parseSolutionProjects(solution);
    }

    private static List<Path> solutionsIn(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isRegularFile)
                    .filter(TargetFramework::isSolutionFile)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<Path> parseSolutionProjects(Path solution) {
        List<Path> projects = new ArrayList<>();
        Path base = solution.getParent();
        if (base == null) {
            return projects;
        }
        String content;
        try {
            content = Files.readString(solution);
        } catch (Exception e) {
            return projects;
        }
        boolean slnx = solution.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".slnx");
        Matcher matcher = (slnx ? SLNX_PROJECT : SLN_PROJECT).matcher(content);
        while (matcher.find()) {
            String relative = matcher.group(1).trim().replace('\\', '/');
            Path resolved = base.resolve(relative).normalize();
            if (isProjectFile(resolved)) {
                projects.add(resolved);
            }
        }
        return projects;
    }

    private static boolean isSolutionFile(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sln") || name.endsWith(".slnx");
    }

    public static List<String> resolveTfms(Path projectRoot) {
        Path projectFile = findPrimaryProjectFile(projectRoot);
        return projectFile == null ? List.of() : readTfms(projectFile);
    }

    public static boolean hasRunnableModernTarget(Path projectRoot) {
        return selectRunnableModernTfm(projectRoot).isPresent();
    }

    public static boolean targetsNetFramework(Path projectRoot) {
        for (String tfm : resolveTfms(projectRoot)) {
            if (isNetFramework(tfm)) {
                return true;
            }
        }
        return false;
    }

    public static boolean canRunOnHost(Path projectRoot) {
        List<String> tfms = resolveTfms(projectRoot);
        if (tfms.isEmpty()) {

            return true;
        }
        return canRunWithDotnet(projectRoot);
    }

    public static boolean canRunWithDotnet(Path projectRoot) {
        return selectRunnableModernTfm(projectRoot).isPresent();
    }

    public static boolean isNetFrameworkOnly(Path projectRoot) {
        List<String> tfms = resolveTfms(projectRoot);
        if (tfms.isEmpty()) {
            return false;
        }
        Set<String> seen = new LinkedHashSet<>(tfms);
        boolean anyFramework = false;
        for (String tfm : seen) {
            if (isModern(tfm)) {
                return false;
            }
            if (isNetFramework(tfm)) {
                anyFramework = true;
            }
        }
        return anyFramework;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path firstProjectIn(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isRegularFile)
                    .filter(TargetFramework::isProjectFile)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isProjectFile(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && PROJECT_EXTENSIONS.contains(name.substring(dot));
    }
}
