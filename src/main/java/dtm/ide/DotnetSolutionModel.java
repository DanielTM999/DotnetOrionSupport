package dtm.ide;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DotnetSolutionModel {

    private static final String SOLUTION_FOLDER_TYPE = "2150E333-8FDC-42A3-9474-1A3956D46DE8";

    private static final Pattern SLN_PROJECT_HEADER = Pattern.compile(
            "Project\\(\"\\{([0-9A-Fa-f-]+)\\}\"\\)\\s*=\\s*\"([^\"]*)\",\\s*\"([^\"]*)\",\\s*\"\\{([0-9A-Fa-f-]+)\\}\"");
    private static final Pattern SLN_NESTED = Pattern.compile(
            "\\{([0-9A-Fa-f-]+)\\}\\s*=\\s*\\{([0-9A-Fa-f-]+)\\}");

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private final List<Entry> roots;

    private DotnetSolutionModel(List<Entry> roots) {
        this.roots = roots;
    }

    List<Entry> roots() {
        return roots;
    }

    boolean hasProjects() {
        return anyProject(roots);
    }

    private static boolean anyProject(List<Entry> entries) {
        for (Entry entry : entries) {
            if (!entry.folder) {
                return true;
            }
            if (anyProject(entry.children)) {
                return true;
            }
        }
        return false;
    }

    static void invalidateCache() {
        CACHE.clear();
    }

    static DotnetSolutionModel parse(Path solution) {
        if (solution == null || solution.getParent() == null || !Files.isRegularFile(solution)) {
            return null;
        }
        Path key = solution.toAbsolutePath().normalize();
        String stamp = FileStamp.of(solution);
        Cached cached = CACHE.get(key);
        if (cached != null && cached.stamp.equals(stamp)) {
            return cached.model;
        }
        DotnetSolutionModel model = doParse(solution);
        CACHE.put(key, new Cached(stamp, model));
        return model;
    }

    private static DotnetSolutionModel doParse(Path solution) {
        String name = solution.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            return name.endsWith(".slnx") ? parseSlnx(solution) : parseSln(solution);
        } catch (Exception e) {
            return null;
        }
    }

    private record Cached(String stamp, DotnetSolutionModel model) {
    }

    private static DotnetSolutionModel parseSln(Path solution) throws Exception {
        Path base = solution.getParent();
        List<String> lines = Files.readAllLines(solution);

        Map<String, Entry> byGuid = new LinkedHashMap<>();
        Map<String, String> parentOf = new LinkedHashMap<>();

        Entry current = null;
        boolean inSolutionItems = false;
        boolean inNested = false;

        for (String raw : lines) {
            String line = raw.trim();
            Matcher header = SLN_PROJECT_HEADER.matcher(line);
            if (header.find()) {
                String type = header.group(1).toUpperCase(Locale.ROOT);
                String projectName = header.group(2);
                String relative = header.group(3);
                String guid = header.group(4).toUpperCase(Locale.ROOT);
                boolean folder = SOLUTION_FOLDER_TYPE.equalsIgnoreCase(type);
                Path projectFile = folder ? null : base.resolve(relative.replace('\\', '/')).normalize();
                current = new Entry(guid, projectName, folder, projectFile);
                byGuid.put(guid, current);
                inSolutionItems = false;
                continue;
            }
            if (line.startsWith("EndProject")) {
                current = null;
                inSolutionItems = false;
                continue;
            }
            if (line.startsWith("ProjectSection(SolutionItems)")) {
                inSolutionItems = true;
                continue;
            }
            if (line.startsWith("EndProjectSection")) {
                inSolutionItems = false;
                continue;
            }
            if (line.startsWith("GlobalSection(NestedProjects)")) {
                inNested = true;
                continue;
            }
            if (line.startsWith("EndGlobalSection")) {
                inNested = false;
                continue;
            }
            if (inSolutionItems && current != null && current.folder) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String item = line.substring(0, eq).trim().replace('\\', '/');
                    if (!item.isEmpty()) {
                        current.files.add(base.resolve(item).normalize());
                    }
                }
                continue;
            }
            if (inNested) {
                Matcher nested = SLN_NESTED.matcher(line);
                if (nested.find()) {
                    parentOf.put(nested.group(1).toUpperCase(Locale.ROOT),
                            nested.group(2).toUpperCase(Locale.ROOT));
                }
            }
        }

        List<Entry> roots = new ArrayList<>();
        for (Entry entry : byGuid.values()) {
            String parentGuid = parentOf.get(entry.guid);
            Entry parent = parentGuid == null ? null : byGuid.get(parentGuid);
            if (parent != null && parent.folder) {
                parent.children.add(entry);
            } else {
                roots.add(entry);
            }
        }
        return new DotnetSolutionModel(roots);
    }

    private static DotnetSolutionModel parseSlnx(Path solution) throws Exception {
        Path base = solution.getParent();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Document document = factory.newDocumentBuilder().parse(solution.toFile());
        Element root = document.getDocumentElement();
        if (root == null) {
            return null;
        }
        List<Entry> roots = new ArrayList<>();
        List<Path> rootFiles = new ArrayList<>();
        appendSlnxChildren(root, base, roots, rootFiles);
        return new DotnetSolutionModel(roots);
    }

    private static void appendSlnxChildren(Element parent, Path base, List<Entry> childrenOut, List<Path> filesOut) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            String tag = element.getTagName().toLowerCase(Locale.ROOT);
            switch (tag) {
                case "folder" -> {
                    String rawName = element.getAttribute("Name");
                    Entry folder = new Entry(rawName.toUpperCase(Locale.ROOT), folderLeafName(rawName), true, null);
                    appendSlnxChildren(element, base, folder.children, folder.files);
                    childrenOut.add(folder);
                }
                case "project" -> {
                    String relative = element.getAttribute("Path");
                    if (!relative.isBlank()) {
                        Path projectFile = base.resolve(relative.replace('\\', '/')).normalize();
                        childrenOut.add(new Entry(relative.toUpperCase(Locale.ROOT),
                                projectDisplayName(projectFile), false, projectFile));
                    }
                }
                case "file" -> {
                    String relative = element.getAttribute("Path");
                    if (!relative.isBlank()) {
                        filesOut.add(base.resolve(relative.replace('\\', '/')).normalize());
                    }
                }
                default -> { }
            }
        }
    }

    private static String folderLeafName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "Folder";
        }
        String cleaned = rawName.replace('\\', '/');
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        int slash = cleaned.lastIndexOf('/');
        String leaf = slash >= 0 ? cleaned.substring(slash + 1) : cleaned;
        return leaf.isBlank() ? "Folder" : leaf;
    }

    private static String projectDisplayName(Path projectFile) {
        Path fileName = projectFile.getFileName();
        if (fileName == null) {
            return "Project";
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static final class Entry {
        final String guid;
        final String name;
        final boolean folder;
        final Path projectFile;
        final List<Entry> children = new ArrayList<>();
        final List<Path> files = new ArrayList<>();

        Entry(String guid, String name, boolean folder, Path projectFile) {
            this.guid = guid;
            this.name = name;
            this.folder = folder;
            this.projectFile = projectFile;
        }
    }
}
