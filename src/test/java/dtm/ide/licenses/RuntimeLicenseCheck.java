package dtm.ide.licenses;

import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.util.XmlDocuments;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/** Checks runtime metadata and packaged notices using the plugin's existing Java dependencies. */
public final class RuntimeLicenseCheck {
    private static final Set<String> ALLOWED = Set.of(
            "MIT", "MIT License", "Apache-2.0", "The Apache Software License, Version 2.0",
            "The Apache License, Version 2.0", "Apache License, Version 2.0", "BSD-2-Clause", "BSD-3-Clause",
            "The BSD 2-Clause License", "The BSD 3-Clause License", "ISC", "Public Domain");

    public record Dependency(String artifact, List<String> licenses, List<String> sources) {}

    private RuntimeLicenseCheck() {}

    public static List<Dependency> checkReport(Path path) throws IOException {
        var nodes = XmlDocuments.read(path).getElementsByTagName("dependency");
        if (nodes.getLength() == 0) throw new IOException("Empty license report");
        var inventory = new ArrayList<Dependency>();
        for (int i = 0; i < nodes.getLength(); i++) {
            var dependency = (Element) nodes.item(i);
            String coordinate = value(dependency, "groupId") + ":" + value(dependency, "artifactId")
                    + ":" + value(dependency, "version");
            var licenses = dependency.getElementsByTagName("license");
            var names = new ArrayList<String>();
            var sources = new ArrayList<String>();
            for (int j = 0; j < licenses.getLength(); j++) {
                var license = (Element) licenses.item(j);
                names.add(value(license, "name"));
                sources.add(value(license, "url"));
            }
            if (names.isEmpty() || !ALLOWED.containsAll(names))
                throw new IOException("Missing or unapproved license: " + coordinate + ": " + names);
            inventory.add(new Dependency(coordinate, List.copyOf(names), List.copyOf(sources)));
        }
        return List.copyOf(inventory);
    }

    private static String value(Element element, String name) {
        var nodes = element.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    public static void checkJar(Path path) throws Exception {
        try (var archive = new ZipFile(path.toFile())) {
            for (String required : List.of("licenses/licenses.xml", "licenses/THIRD-PARTY.txt",
                    "licenses/project/LICENSE", "licenses/components.json")) requireEntry(archive, required);
            if (archive.stream().anyMatch(entry -> entry.getName().startsWith("org/junit/")
                    || entry.getName().startsWith("org/jacoco/") || entry.getName().startsWith("org/testng/")))
                throw new IOException("Test-only tooling was bundled in the plugin");
            var mapper = new ObjectMapper();
            try (var stream = archive.getInputStream(archive.getEntry("licenses/components.json"))) {
                var catalog = mapper.readTree(stream);
                for (String section : List.of("downloadedComponents", "bundledAssets", "manualRuntimeNotices")) {
                    var components = catalog.path(section);
                    if (!components.isArray() || components.isEmpty())
                        throw new IOException("Missing component catalog: " + section);
                    for (var component : components) {
                        if (!ALLOWED.contains(component.path("license").asText()))
                            throw new IOException("Unapproved component license: " + component);
                        requireEntry(archive, component.path("notice").asText());
                        if (section.equals("bundledAssets")) {
                            String asset = component.path("path").asText();
                            requireEntry(archive, asset);
                            try (var bytes = archive.getInputStream(archive.getEntry(asset))) {
                                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes()));
                                if (!digest.equals(component.path("sha256").asText()))
                                    throw new IOException("Bundled asset changed without provenance review: " + asset);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void requireEntry(ZipFile archive, String name) throws IOException {
        var entry = archive.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing notice or asset in JAR: " + name);
    }

    public static void main(String[] args) throws Exception {
        Path report = Path.of("target/classes/licenses/licenses.xml");
        Path jar = null, inventory = null;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
            switch (args[i]) {
                case "--report" -> report = Path.of(args[i + 1]);
                case "--jar" -> jar = Path.of(args[i + 1]);
                case "--inventory" -> inventory = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        var dependencies = checkReport(report);
        if (jar != null) checkJar(jar);
        if (inventory != null) {
            Files.createDirectories(inventory.toAbsolutePath().getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(inventory.toFile(), dependencies);
        }
        System.out.println("Permissive runtime licenses verified: " + dependencies.size() + " dependencies");
    }
}
