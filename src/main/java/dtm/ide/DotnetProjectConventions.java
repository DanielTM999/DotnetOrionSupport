package dtm.ide;

import dtm.ide.api.project.tree.ProjectTreeNode;
import dtm.ide.run.TargetFramework;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

final class DotnetProjectConventions {

    static final String PROJECT_TYPE = ".NET / C#";
    static final String PROJECT_MARKER = ".dotnet-project";
    static final String ORION_DIR = ".orion";

    static final Set<String> PROJECT_FILES = Set.of(
            "packages.config",
            "global.json",
            "Directory.Build.props",
            "Directory.Build.targets",
            "nuget.config",
            "NuGet.Config"
    );

    static final Set<String> PROJECT_FILE_EXTENSIONS = Set.of(
            ".sln", ".slnx", ".csproj", ".vbproj", ".fsproj", ".props", ".targets"
    );

    static final Set<String> CSHARP_EXTENSIONS = Set.of(
            ".cs", ".csx", ".cshtml", ".razor"
    );

    static final Set<String> PROJECT_XML_EXTENSIONS = Set.of(
            ".csproj", ".vbproj", ".fsproj", ".props", ".targets", ".config"
    );

    private static final Set<String> OTHER_SOURCE_EXTENSIONS = Set.of(
            ".c", ".cc", ".cpp", ".cxx", ".h", ".hpp",
            ".java", ".kt", ".kts", ".py", ".js", ".jsx", ".ts", ".tsx",
            ".go", ".rs", ".swift", ".rb", ".php", ".dart", ".scala", ".lua"
    );

    private static final int MAX_SCAN_DEPTH = 6;

    private static final Set<String> HIDDEN_ROOT_BUILD_ARTIFACTS = Set.of(
            "bin",
            "obj",
            ".vs"
    );

    private DotnetProjectConventions() {
    }

    static boolean supports(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }

        Path projectDir = Files.isDirectory(path) ? path : path.getParent();
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return false;
        }

        if (!isDotnetProject(projectDir)) {
            return false;
        }

        if (!isWindows() && TargetFramework.isNetFrameworkOnly(projectDir)) {
            return false;
        }

        return true;
    }

    private static boolean isDotnetProject(Path projectDir) {
        if (Files.isRegularFile(projectDir.resolve(PROJECT_MARKER))) {
            return true;
        }
        for (String projectFile : PROJECT_FILES) {
            if (Files.exists(projectDir.resolve(projectFile))) {
                return true;
            }
        }
        if (hasProjectFile(projectDir)) {
            return true;
        }
        if (Files.exists(projectDir.resolve(ORION_DIR).resolve("manifest.json"))) {
            return true;
        }
        return hasCSharpMajority(projectDir);
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean hasProjectFile(Path projectDir) {
        try (Stream<Path> entries = Files.list(projectDir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (Files.isRegularFile(entry) && isProjectFile(entry)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static boolean isProjectFile(Path filePath) {
        String extension = extensionOf(filePath);
        if (extension == null) {
            return false;
        }
        if (".sln".equals(extension) || ".slnx".equals(extension) || ".csproj".equals(extension)
                || ".vbproj".equals(extension) || ".fsproj".equals(extension)) {
            return true;
        }
        return false;
    }

    private static boolean hasCSharpMajority(Path projectDir) {
        try (Stream<Path> entries = Files.walk(projectDir, MAX_SCAN_DEPTH)) {
            long csLike = 0;
            long otherSource = 0;
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (!Files.isRegularFile(entry) || entry.getFileName() == null
                        || isInIgnoredDir(projectDir, entry)) {
                    continue;
                }
                if (isCSharpLike(entry)) {
                    csLike++;
                } else if (isOtherSource(entry)) {
                    otherSource++;
                }
            }
            return csLike > 0 && csLike >= otherSource;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isInIgnoredDir(Path root, Path file) {
        Path relative = root.relativize(file);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            String part = relative.getName(i).toString();
            if (part.startsWith(".") || HIDDEN_ROOT_BUILD_ARTIFACTS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOtherSource(Path filePath) {
        String extension = extensionOf(filePath);
        return extension != null && OTHER_SOURCE_EXTENSIONS.contains(extension);
    }

    static boolean isInsideHiddenArtifact(Path projectRoot, Path path) {
        if (projectRoot == null || path == null) {
            return false;
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            return false;
        }
        Path relative = root.relativize(target);
        for (int i = 0; i < relative.getNameCount(); i++) {
            String part = relative.getName(i).toString();
            if (part.startsWith(".") || HIDDEN_ROOT_BUILD_ARTIFACTS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    static ProjectTreeNode hideRootBuildArtifacts(ProjectTreeNode node) {
        if (node == null) {
            return null;
        }
        node.removeIf(child -> {
            Path path = child.getPath();
            if (path == null || path.getFileName() == null) {
                return false;
            }
            return HIDDEN_ROOT_BUILD_ARTIFACTS.contains(path.getFileName().toString());
        }, false);
        return node;
    }

    static Collection<FoldRule> foldRules(Path filePath) {
        if (isCSharpLike(filePath)) {
            return List.of(
                    FoldRule.pair('{', '}'),
                    FoldRule.pair("/*", "*/"),
                    FoldRule.pair("#region", "#endregion")
            );
        }
        if (isProjectXmlLike(filePath)) {
            return List.of(FoldRule.xmlTags());
        }
        return null;
    }

    static boolean isCSharpLike(Path filePath) {
        String extension = extensionOf(filePath);
        return extension != null && CSHARP_EXTENSIONS.contains(extension);
    }

    static boolean isProjectXmlLike(Path filePath) {
        String extension = extensionOf(filePath);
        return extension != null && PROJECT_XML_EXTENSIONS.contains(extension);
    }

    static boolean isHighlightable(Path filePath) {
        return isCSharpLike(filePath);
    }

    static String lineCommentPrefix(Path filePath) {
        return isCSharpLike(filePath) ? "//" : null;
    }

    private static String extensionOf(Path filePath) {
        String name = filePath == null || filePath.getFileName() == null ? null : filePath.getFileName().toString();
        if (name == null) {
            return null;
        }
        String fileName = name.toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return fileName.substring(dot);
    }

    static boolean isInside(Path path, Path directory) {
        if (path == null || directory == null) {
            return false;
        }
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedDirectory);
    }

    static Path normalizePath(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    static boolean samePath(Path a, Path b) {
        return a != null && b != null && normalizePath(a).equals(normalizePath(b));
    }

    static Path pathFromUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            return Path.of(URI.create(uri));
        } catch (Exception e) {
            return null;
        }
    }
}
