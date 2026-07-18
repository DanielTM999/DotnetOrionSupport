package dtm.ide;

import dtm.ide.api.project.tree.ProjectTreeNode;
import dtm.ide.run.TargetFramework;
import dtm.ide.settings.TreeLayout;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;
import dtm.stools.utils.ImageUtils;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
            ".cs", ".csx"
    );

    static final Set<String> RAZOR_EXTENSIONS = Set.of(
            ".cshtml", ".razor"
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

    private static final Set<String> VISUAL_STUDIO_HIDDEN = Set.of(
            "bin",
            "obj",
            ".vs",
            ".orion",
            ".git",
            ".idea"
    );

    private static final Set<String> SOLUTION_EXTENSIONS = Set.of(".sln", ".slnx");

    private static final Set<String> VS_PROJECT_EXTENSIONS = Set.of(".csproj", ".vbproj", ".fsproj");

    private static final Set<String> SOLUTION_LEVEL_FILES = Set.of(
            ".gitignore",
            ".gitattributes",
            ".gitmodules",
            ".editorconfig",
            ".dockerignore",
            "global.json",
            "nuget.config",
            "directory.build.props",
            "directory.build.targets",
            "directory.packages.props"
    );

    private static volatile Icon solutionIcon;
    private static volatile Icon solutionFolderIcon;
    private static volatile Icon projectIcon;
    private static volatile Icon referencesIcon;
    private static volatile Icon assemblyReferenceIcon;
    private static volatile Icon packageReferenceIcon;
    private static volatile Icon projectReferenceIcon;

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

    static ProjectTreeNode applyTreeLayout(ProjectTreeNode root, TreeLayout layout) {
        if (root == null) {
            return null;
        }
        if (layout == TreeLayout.VISUAL_STUDIO) {
            ProjectTreeNode reorganized = buildVisualStudioLayout(root);
            return reorganized != null ? reorganized : hideRootBuildArtifacts(root);
        }
        return hideRootBuildArtifacts(root);
    }

    static ProjectTreeNode buildFilesystemTree(Path path) {
        if (path == null) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        ProjectTreeNode node = ProjectTreeNode.of(normalized);
        if (!Files.isDirectory(normalized)) {
            return node;
        }
        List<ProjectTreeNode> children = new ArrayList<>();
        try (Stream<Path> entries = Files.list(normalized)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (!isVisibleFilesystemPath(entry)) {
                    continue;
                }
                ProjectTreeNode child = buildFilesystemTree(entry);
                if (child != null) {
                    children.add(child);
                }
            }
        } catch (Exception ignored) {
        }
        sortNodes(children);
        node.children(children);
        return node;
    }

    private static boolean isVisibleFilesystemPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        if (VISUAL_STUDIO_HIDDEN.contains(path.getFileName().toString())) {
            return false;
        }
        try {
            return !Files.isHidden(path);
        } catch (IOException ignored) {
            return false;
        }
    }

    static Path findSolutionOrProjectFile(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        Path projectFile = null;
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                if (hasExtension(entry, SOLUTION_EXTENSIONS)) {
                    return entry;
                }
                if (projectFile == null && hasExtension(entry, VS_PROJECT_EXTENSIONS)) {
                    projectFile = entry;
                }
            }
        } catch (Exception ignored) {
        }
        return projectFile;
    }

    private static ProjectTreeNode buildVisualStudioLayout(ProjectTreeNode root) {
        Path rootPath = root.getPath();
        if (rootPath == null) {
            return null;
        }

        deepHide(root, VISUAL_STUDIO_HIDDEN);

        if (!containsProject(root)) {
            return hideRootBuildArtifacts(root);
        }

        Path solutionFile = findSolutionFile(root);
        Path rootProjectFile = findProjectFile(root);

        if (solutionFile != null) {
            DotnetSolutionModel model = DotnetSolutionModel.parse(solutionFile);
            if (model != null && model.hasProjects()) {
                return buildFromSolutionModel(rootPath, solutionFile, model, root);
            }
        }

        if (rootProjectFile != null) {
            if (solutionFile == null) {
                return buildRootProjectNode(root, rootPath, rootProjectFile);
            }
            return buildSolutionWithRootProject(root, rootPath, rootProjectFile, solutionFile);
        }

        ProjectTreeNode newRoot = ProjectTreeNode.of(
                rootPath,
                solutionFile != null ? labelOf(solutionFile) : labelOf(rootPath)
        );
        if (solutionFile != null) {
            applyLayoutIcon(newRoot, solutionIcon());
        }

        List<ProjectTreeNode> children = new ArrayList<>();
        for (ProjectTreeNode child : root.getChildren()) {
            Path childPath = child.getPath();
            if (childPath == null || childPath.getFileName() == null) {
                continue;
            }
            if (Files.isDirectory(childPath)) {
                List<ProjectTreeNode> projects = new ArrayList<>();
                collectTopmostProjects(child, projects);
                if (projects.isEmpty()) {
                    children.add(child);
                } else {
                    for (ProjectTreeNode project : projects) {
                        children.add(buildProjectNode(project, findProjectFile(project), solutionFile));
                    }
                }
            } else {
                if (solutionFile != null && samePath(childPath, solutionFile)) {
                    continue;
                }
                children.add(child);
            }
        }

        sortNodes(children);
        newRoot.children(children);
        return newRoot;
    }

    private static ProjectTreeNode buildFromSolutionModel(Path rootPath, Path solutionFile,
                                                          DotnetSolutionModel model, ProjectTreeNode root) {
        ProjectTreeNode newRoot = ProjectTreeNode.of(rootPath, labelOf(solutionFile));
        applyLayoutIcon(newRoot, solutionIcon());

        List<ProjectTreeNode> children = new ArrayList<>();
        for (DotnetSolutionModel.Entry entry : model.roots()) {
            ProjectTreeNode node = buildModelEntry(entry, rootPath, solutionFile);
            if (node != null) {
                children.add(node);
            }
        }
        List<Path> projectDirs = new ArrayList<>();
        collectProjectDirs(model.roots(), projectDirs);
        for (ProjectTreeNode child : root.getChildren()) {
            Path childPath = child.getPath();
            if (childPath == null || samePath(childPath, solutionFile)) {
                continue;
            }
            if (Files.isRegularFile(childPath) && !isInsideAnyProject(childPath, projectDirs)) {
                children.add(child);
            }
        }

        sortNodes(children);
        newRoot.children(children);
        return newRoot;
    }

    private static void collectProjectDirs(List<DotnetSolutionModel.Entry> entries, List<Path> out) {
        for (DotnetSolutionModel.Entry entry : entries) {
            if (entry.folder) {
                collectProjectDirs(entry.children, out);
            } else if (entry.projectFile != null && entry.projectFile.getParent() != null) {
                out.add(entry.projectFile.getParent().toAbsolutePath().normalize());
            }
        }
    }

    private static boolean isInsideAnyProject(Path file, List<Path> projectDirs) {
        Path normalized = file.toAbsolutePath().normalize();
        for (Path dir : projectDirs) {
            if (normalized.startsWith(dir)) {
                return true;
            }
        }
        return false;
    }

    private static ProjectTreeNode buildModelEntry(DotnetSolutionModel.Entry entry, Path rootPath, Path solutionFile) {
        if (entry.folder) {
            return buildSolutionFolderNode(entry, rootPath, solutionFile);
        }
        return buildProjectNodeFromFile(entry.projectFile, solutionFile);
    }

    private static ProjectTreeNode buildSolutionFolderNode(DotnetSolutionModel.Entry entry, Path rootPath,
                                                           Path solutionFile) {
        ProjectTreeNode node = ProjectTreeNode.of(solutionFolderPath(rootPath, entry.guid), entry.name, true);
        applyLayoutIcon(node, solutionFolderIcon());

        List<ProjectTreeNode> children = new ArrayList<>();
        for (DotnetSolutionModel.Entry child : entry.children) {
            ProjectTreeNode childNode = buildModelEntry(child, rootPath, solutionFile);
            if (childNode != null) {
                children.add(childNode);
            }
        }
        for (Path file : entry.files) {
            if (file != null && Files.exists(file)) {
                children.add(ProjectTreeNode.of(file));
            }
        }
        sortNodes(children);
        node.children(children);
        return node;
    }

    private static ProjectTreeNode buildProjectNodeFromFile(Path projectFile, Path solutionFile) {
        if (projectFile == null || !Files.isRegularFile(projectFile)) {
            return null;
        }
        Path projectDir = projectFile.getParent();
        ProjectTreeNode folderNode = projectDir != null && Files.isDirectory(projectDir)
                ? buildFilesystemTree(projectDir)
                : ProjectTreeNode.of(projectFile);
        return buildProjectNode(folderNode, projectFile, solutionFile);
    }

    private static Path solutionFolderPath(Path rootPath, String guid) {
        String leaf = guid == null ? "folder" : guid.replaceAll("[^A-Za-z0-9]", "_");
        return rootPath.resolve(".orion-tree").resolve("solution").resolve(leaf);
    }

    private static ProjectTreeNode buildSolutionWithRootProject(ProjectTreeNode root, Path rootPath,
                                                               Path projectFile, Path solutionFile) {
        ProjectTreeNode newRoot = ProjectTreeNode.of(rootPath, labelOf(solutionFile));
        applyLayoutIcon(newRoot, solutionIcon());

        List<ProjectTreeNode> projectChildren = new ArrayList<>();
        List<ProjectTreeNode> solutionChildren = new ArrayList<>();
        for (ProjectTreeNode child : root.getChildren()) {
            Path childPath = child.getPath();
            if (childPath == null) {
                continue;
            }
            if (samePath(childPath, projectFile) || samePath(childPath, solutionFile)) {
                continue;
            }
            if (isSolutionLevelFile(childPath)) {
                solutionChildren.add(child);
            } else {
                projectChildren.add(child);
            }
        }

        addReferencesNode(projectChildren, projectFile);
        sortNodes(projectChildren);
        ProjectTreeNode projectNode = ProjectTreeNode.of(projectFile, labelOf(projectFile));
        applyLayoutIcon(projectNode, projectIcon());
        projectNode.children(projectChildren);

        solutionChildren.add(projectNode);
        sortNodes(solutionChildren);
        newRoot.children(solutionChildren);
        return newRoot;
    }

    private static boolean isSolutionLevelFile(Path path) {
        if (path == null || path.getFileName() == null || !Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (SOLUTION_LEVEL_FILES.contains(name)) {
            return true;
        }
        return name.startsWith("readme") || name.startsWith("license") || name.startsWith("licence");
    }

    private static ProjectTreeNode buildRootProjectNode(ProjectTreeNode root, Path rootPath, Path projectFile) {
        ProjectTreeNode node = ProjectTreeNode.of(rootPath, labelOf(projectFile));
        applyLayoutIcon(node, projectIcon());
        List<ProjectTreeNode> children = new ArrayList<>(root.getChildren());
        addReferencesNode(children, projectFile);
        sortNodes(children);
        node.children(children);
        return node;
    }

    private static ProjectTreeNode buildProjectNode(ProjectTreeNode projectFolder, Path projectFile, Path solutionFile) {
        Path nodePath = projectFile != null ? projectFile : projectFolder.getPath();
        ProjectTreeNode node = ProjectTreeNode.of(nodePath, labelOf(nodePath));

        List<ProjectTreeNode> children = new ArrayList<>();
        for (ProjectTreeNode child : projectFolder.getChildren()) {
            Path childPath = child.getPath();
            if (childPath == null) {
                continue;
            }
            if (projectFile != null && samePath(childPath, projectFile)) {
                continue;
            }
            if (solutionFile != null && samePath(childPath, solutionFile)) {
                continue;
            }
            children.add(child);
        }

        addReferencesNode(children, projectFile);
        sortNodes(children);
        node.children(children);
        applyLayoutIcon(node, projectIcon());
        return node;
    }

    private static void addReferencesNode(List<ProjectTreeNode> children, Path projectFile) {
        if (projectFile == null) {
            return;
        }
        Path virtualRoot = virtualReferencePath(projectFile, "root");
        ProjectTreeNode referencesNode = ProjectTreeNode.of(virtualRoot, "References", true);
        applyLayoutIcon(referencesNode, referencesIcon());

        List<DotnetProjectReference> references = DotnetProjectReference.read(projectFile);
        List<ProjectTreeNode> referenceNodes = new ArrayList<>();
        for (int i = 0; i < references.size(); i++) {
            DotnetProjectReference reference = references.get(i);
            Path path = reference.target() != null
                    ? reference.target()
                    : virtualReferencePath(projectFile, "item-" + i);
            ProjectTreeNode node = ProjectTreeNode.of(path, reference.label(), true);
            applyLayoutIcon(node, referenceIcon(reference.kind()));
            referenceNodes.add(node);
        }
        referenceNodes.sort(Comparator.comparing(ProjectTreeNode::getLabel, String.CASE_INSENSITIVE_ORDER));
        referencesNode.children(referenceNodes);
        children.add(referencesNode);
    }

    private static Path virtualReferencePath(Path projectFile, String leaf) {
        Path directory = projectFile.getParent();
        Path base = directory != null ? directory : projectFile.toAbsolutePath().getParent();
        String projectName = projectFile.getFileName() != null ? projectFile.getFileName().toString() : "project";
        return base.resolve(".orion-tree").resolve(projectName).resolve("references").resolve(leaf);
    }

    private static void collectTopmostProjects(ProjectTreeNode node, List<ProjectTreeNode> out) {
        if (node == null) {
            return;
        }
        if (isProjectFolder(node)) {
            out.add(node);
            return;
        }
        for (ProjectTreeNode child : node.getChildren()) {
            if (child != null && child.getPath() != null && Files.isDirectory(child.getPath())) {
                collectTopmostProjects(child, out);
            }
        }
    }

    private static boolean containsProject(ProjectTreeNode node) {
        if (node == null) {
            return false;
        }
        if (isProjectFolder(node)) {
            return true;
        }
        for (ProjectTreeNode child : node.getChildren()) {
            if (child != null && child.getPath() != null && Files.isDirectory(child.getPath())
                    && containsProject(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProjectFolder(ProjectTreeNode node) {
        return findProjectFile(node) != null;
    }

    private static Path findProjectFile(ProjectTreeNode node) {
        if (node == null) {
            return null;
        }
        for (ProjectTreeNode child : node.getChildren()) {
            Path path = child.getPath();
            if (path != null && Files.isRegularFile(path) && hasExtension(path, VS_PROJECT_EXTENSIONS)) {
                return path;
            }
        }
        return null;
    }

    private static Path findSolutionFile(ProjectTreeNode root) {
        for (ProjectTreeNode child : root.getChildren()) {
            Path path = child.getPath();
            if (path != null && Files.isRegularFile(path) && hasExtension(path, SOLUTION_EXTENSIONS)) {
                return path;
            }
        }
        return null;
    }

    private static boolean hasExtension(Path path, Set<String> extensions) {
        String extension = extensionOf(path);
        return extension != null && extensions.contains(extension);
    }

    private static void deepHide(ProjectTreeNode node, Set<String> names) {
        if (node == null) {
            return;
        }
        node.removeIf(child -> {
            Path path = child.getPath();
            return path != null && path.getFileName() != null
                    && names.contains(path.getFileName().toString());
        }, true);
    }

    private static void sortNodes(List<ProjectTreeNode> nodes) {
        nodes.sort(Comparator
                .comparing((ProjectTreeNode node) -> !isContainerNode(node))
                .thenComparing(node -> labelOf(node.getPath()), String.CASE_INSENSITIVE_ORDER));
    }

    private static boolean isContainerNode(ProjectTreeNode node) {
        if (node == null) {
            return false;
        }
        if (node.hasCustomChildren()) {
            return true;
        }
        Path path = node.getPath();
        return path != null && Files.isDirectory(path);
    }

    private static void applyLayoutIcon(ProjectTreeNode node, Icon icon) {
        if (icon != null) {
            node.icon(icon);
        }
    }

    private static Icon solutionIcon() {
        Icon icon = solutionIcon;
        if (icon == null) {
            icon = loadBundledIcon("imgs/dotnet/slnSlnx.svg");
            solutionIcon = icon;
        }
        return icon;
    }

    private static Icon projectIcon() {
        Icon icon = projectIcon;
        if (icon == null) {
            icon = loadBundledIcon("imgs/dotnet/csProj.svg");
            projectIcon = icon;
        }
        return icon;
    }

    private static Icon solutionFolderIcon() {
        Icon icon = solutionFolderIcon;
        if (icon == null) {
            icon = UIManager.getIcon("Tree.closedIcon");
            if (icon == null) {
                icon = UIManager.getIcon("FileView.directoryIcon");
            }
            solutionFolderIcon = icon;
        }
        return icon;
    }

    private static Icon referencesIcon() {
        Icon icon = referencesIcon;
        if (icon == null) {
            icon = loadBundledIcon("imgs/dotnet/references.svg");
            referencesIcon = icon;
        }
        return icon;
    }

    private static Icon referenceIcon(DotnetProjectReference.Kind kind) {
        return switch (kind) {
            case ASSEMBLY -> assemblyReferenceIcon();
            case PACKAGE -> packageReferenceIcon();
            case PROJECT -> projectReferenceIcon();
        };
    }

    private static Icon assemblyReferenceIcon() {
        Icon icon = assemblyReferenceIcon;
        if (icon == null) {
            icon = loadBundledIcon("imgs/dotnet/assemblyReference.svg");
            assemblyReferenceIcon = icon;
        }
        return icon;
    }

    private static Icon packageReferenceIcon() {
        Icon icon = packageReferenceIcon;
        if (icon == null) {
            icon = loadBundledIcon("imgs/dotnet/packageReference.svg");
            packageReferenceIcon = icon;
        }
        return icon;
    }

    private static Icon projectReferenceIcon() {
        Icon icon = projectReferenceIcon;
        if (icon == null) {
            icon = loadBundledIcon("imgs/dotnet/projectReference.svg");
            projectReferenceIcon = icon;
        }
        return icon;
    }

    private static Icon loadBundledIcon(String resource) {
        try {
            return ImageUtils.getIconByResource(DotnetProjectConventions.class, resource)
                    .map(icon -> ImageUtils.resizeIcon(icon, 20, 20))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String labelOf(Path path) {
        if (path == null) {
            return "";
        }
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    static Collection<FoldRule> foldRules(Path filePath) {
        if (isCSharpLike(filePath)) {
            return List.of(
                    FoldRule.pair('{', '}'),
                    FoldRule.pair("/*", "*/"),
                    FoldRule.pair("#region", "#endregion")
            );
        }
        if (isRazorLike(filePath)) {
            return List.of(
                    FoldRule.xmlTags(),
                    FoldRule.pair('{', '}')
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

    static boolean isRazorLike(Path filePath) {
        String extension = extensionOf(filePath);
        return extension != null && RAZOR_EXTENSIONS.contains(extension);
    }

    static boolean isProjectXmlLike(Path filePath) {
        String extension = extensionOf(filePath);
        return extension != null && PROJECT_XML_EXTENSIONS.contains(extension);
    }

    static boolean isHighlightable(Path filePath) {
        return isCSharpLike(filePath) || isRazorLike(filePath);
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
