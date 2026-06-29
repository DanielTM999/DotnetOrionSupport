package dtm.ide;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the dependency declarations which can be represented in the project tree. */
final class DotnetProjectReference {

    enum Kind {
        ASSEMBLY,
        PACKAGE,
        PROJECT
    }

    private static final Pattern ASSEMBLY_VERSION = Pattern.compile(
            "(?:^|,)\\s*Version\\s*=\\s*([^,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_DIRECTORY = Pattern.compile("^\\d+(?:\\.\\d+){1,3}(?:[-+][A-Za-z0-9.-]+)?$");

    private final Kind kind;
    private final String name;
    private final String version;
    private final Path target;

    private DotnetProjectReference(Kind kind, String name, String version, Path target) {
        this.kind = kind;
        this.name = name;
        this.version = version;
        this.target = target;
    }

    Kind kind() {
        return kind;
    }

    String label() {
        return version == null || version.isBlank() ? name : name + "-" + version;
    }

    Path target() {
        return target;
    }

    static List<DotnetProjectReference> read(Path projectFile) {
        if (projectFile == null || !Files.isRegularFile(projectFile)) {
            return List.of();
        }

        Map<String, String> centralVersions = readCentralPackageVersions(projectFile.getParent());
        Map<String, DotnetProjectReference> references = new LinkedHashMap<>();
        try {
            Document document = parse(projectFile);
            readAssemblyReferences(document, projectFile, references);
            readPackageReferences(document, projectFile, centralVersions, references);
            readProjectReferences(document, projectFile, references);
        } catch (Exception ignored) {
            // An invalid project must not prevent the rest of the project tree from being displayed.
        }
        readPackagesConfig(projectFile, references);
        return new ArrayList<>(references.values());
    }

    private static void readAssemblyReferences(Document document, Path projectFile,
                                               Map<String, DotnetProjectReference> out) {
        for (Element element : elements(document, "Reference")) {
            String include = attribute(element, "Include");
            if (include == null) {
                continue;
            }
            String name = include.split(",", 2)[0].trim();
            if (name.isEmpty()) {
                continue;
            }
            String version = assemblyVersion(include);
            Path target = resolve(projectFile.getParent(), childText(element, "HintPath"));
            if (version == null) {
                version = versionFromPath(target);
            }
            put(out, new DotnetProjectReference(Kind.ASSEMBLY, name, version, target));
        }
    }

    private static void readPackageReferences(Document document, Path projectFile,
                                              Map<String, String> centralVersions,
                                              Map<String, DotnetProjectReference> out) {
        for (Element element : elements(document, "PackageReference")) {
            String name = firstNonBlank(attribute(element, "Include"), attribute(element, "Update"));
            if (name == null) {
                continue;
            }
            String version = firstNonBlank(
                    attribute(element, "VersionOverride"), childText(element, "VersionOverride"),
                    attribute(element, "Version"), childText(element, "Version"),
                    centralVersions.get(name.toLowerCase(Locale.ROOT))
            );
            put(out, new DotnetProjectReference(Kind.PACKAGE, name, cleanVersion(version), null));
        }
    }

    private static void readProjectReferences(Document document, Path projectFile,
                                              Map<String, DotnetProjectReference> out) {
        for (Element element : elements(document, "ProjectReference")) {
            String include = attribute(element, "Include");
            Path target = resolve(projectFile.getParent(), include);
            if (target == null || target.getFileName() == null) {
                continue;
            }
            String fileName = target.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String name = dot > 0 ? fileName.substring(0, dot) : fileName;
            put(out, new DotnetProjectReference(Kind.PROJECT, name, null, target));
        }
    }

    private static void readPackagesConfig(Path projectFile, Map<String, DotnetProjectReference> out) {
        Path directory = projectFile.getParent();
        Path packagesConfig = directory == null ? null : directory.resolve("packages.config");
        if (packagesConfig == null || !Files.isRegularFile(packagesConfig)) {
            return;
        }
        try {
            Document document = parse(packagesConfig);
            for (Element element : elements(document, "package")) {
                String name = attribute(element, "id");
                if (name != null) {
                    put(out, new DotnetProjectReference(
                            Kind.PACKAGE, name, cleanVersion(attribute(element, "version")), null));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Map<String, String> readCentralPackageVersions(Path directory) {
        Map<String, String> versions = new LinkedHashMap<>();
        for (Path current = directory; current != null; current = current.getParent()) {
            Path props = current.resolve("Directory.Packages.props");
            if (!Files.isRegularFile(props)) {
                continue;
            }
            try {
                Document document = parse(props);
                for (Element element : elements(document, "PackageVersion")) {
                    String name = firstNonBlank(attribute(element, "Include"), attribute(element, "Update"));
                    String version = firstNonBlank(attribute(element, "Version"), childText(element, "Version"));
                    if (name != null && version != null) {
                        versions.putIfAbsent(name.toLowerCase(Locale.ROOT), cleanVersion(version));
                    }
                }
            } catch (Exception ignored) {
            }
            break;
        }
        return versions;
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    private static List<Element> elements(Document document, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName(name);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static String childText(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equalsIgnoreCase(element.getTagName())) {
                return trimToNull(element.getTextContent());
            }
        }
        return null;
    }

    private static String attribute(Element element, String name) {
        return trimToNull(element.getAttribute(name));
    }

    private static String assemblyVersion(String include) {
        Matcher matcher = ASSEMBLY_VERSION.matcher(include);
        return matcher.find() ? cleanVersion(matcher.group(1)) : null;
    }

    private static Path resolve(Path directory, String value) {
        String path = trimToNull(value);
        if (path == null) {
            return null;
        }
        try {
            Path parsed = Path.of(path.replace('\\', java.io.File.separatorChar));
            return (parsed.isAbsolute() || directory == null ? parsed : directory.resolve(parsed))
                    .toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String versionFromPath(Path target) {
        if (target == null) {
            return null;
        }
        for (Path part : target) {
            String value = part.toString();
            if (VERSION_DIRECTORY.matcher(value).matches()) {
                return value;
            }
        }
        return null;
    }

    private static String cleanVersion(String value) {
        String version = trimToNull(value);
        return version != null && !version.contains("$(") ? version : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void put(Map<String, DotnetProjectReference> out, DotnetProjectReference reference) {
        String key = reference.kind + "\n" + reference.name.toLowerCase(Locale.ROOT);
        out.putIfAbsent(key, reference);
    }
}
