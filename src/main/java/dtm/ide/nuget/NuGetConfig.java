package dtm.ide.nuget;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public final class NuGetConfig {

    private final Path projectRoot;

    public NuGetConfig(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    public List<NuGetSource> listSources() {
        Path config = effectiveConfigFile();
        if (config == null || !Files.isRegularFile(config)) {
            List<NuGetSource> defaults = new ArrayList<>();
            defaults.add(NuGetSource.nugetOrg());
            return defaults;
        }
        try {
            Document doc = parse(config);
            Map<String, String> sources = readAddEntries(doc, "packageSources");
            Map<String, String> disabled = readAddEntries(doc, "disabledPackageSources");
            List<NuGetSource> out = new ArrayList<>();
            boolean hasNugetOrg = false;
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                if (NuGetSource.isNugetOrg(entry.getKey(), entry.getValue())) {
                    if (!hasNugetOrg) {
                        out.add(NuGetSource.nugetOrg());
                        hasNugetOrg = true;
                    }
                    continue;
                }
                boolean enabled = !disabled.containsKey(entry.getKey().toLowerCase(Locale.ROOT));
                out.add(new NuGetSource(entry.getKey(), entry.getValue(), enabled));
            }
            if (!hasNugetOrg) {
                out.add(0, NuGetSource.nugetOrg());
            }
            return out;
        } catch (Exception e) {
            log.warn("Falha ao ler NuGet.Config ({}): {}", config, e.getMessage());
            List<NuGetSource> defaults = new ArrayList<>();
            defaults.add(NuGetSource.nugetOrg());
            return defaults;
        }
    }

    public void addOrUpdateSource(String name, String url) throws Exception {
        if (name == null || name.isBlank() || url == null || url.isBlank()) {
            throw new IllegalArgumentException("name/url obrigatórios para a fonte NuGet.");
        }
        rejectNugetOrgChange(name, url);
        Path config = projectConfigForWrite();
        Document doc = Files.isRegularFile(config) ? parse(config) : newConfigDocument();
        Element packageSources = ensureSection(doc, "packageSources");
        Element existing = findAddByKey(packageSources, name);
        if (existing != null) {
            existing.setAttribute("value", url);
        } else {
            Element add = doc.createElement("add");
            add.setAttribute("key", name);
            add.setAttribute("value", url);
            if (url.toLowerCase(Locale.ROOT).endsWith("index.json")) {
                add.setAttribute("protocolVersion", "3");
            }
            packageSources.appendChild(add);
        }

        removeFromSection(doc, "disabledPackageSources", name);
        write(doc, config);
    }

    public void removeSource(String name) throws Exception {
        rejectNugetOrgChange(name, null);
        Path config = projectConfigForWrite();
        if (!Files.isRegularFile(config)) {
            return;
        }
        Document doc = parse(config);
        removeFromSection(doc, "packageSources", name);
        removeFromSection(doc, "disabledPackageSources", name);
        write(doc, config);
    }

    public void setEnabled(String name, boolean enabled) throws Exception {
        rejectNugetOrgChange(name, null);
        Path config = projectConfigForWrite();
        Document doc = Files.isRegularFile(config) ? parse(config) : newConfigDocument();
        if (enabled) {
            removeFromSection(doc, "disabledPackageSources", name);
        } else {
            Element disabledSection = ensureSection(doc, "disabledPackageSources");
            if (findAddByKey(disabledSection, name) == null) {
                Element add = doc.createElement("add");
                add.setAttribute("key", name);
                add.setAttribute("value", "true");
                disabledSection.appendChild(add);
            }
        }
        write(doc, config);
    }

    private Path effectiveConfigFile() {
        Path projectConfig = findExistingProjectConfig();
        if (projectConfig != null) {
            return projectConfig;
        }
        return userConfigFile();
    }

    private static void rejectNugetOrgChange(String name, String url) {
        if (NuGetSource.isNugetOrg(name, url)) {
            throw new IllegalArgumentException("A fonte padrao nuget.org e somente leitura.");
        }
    }

    private Path findExistingProjectConfig() {
        if (projectRoot == null) {
            return null;
        }
        for (String name : List.of("nuget.config", "NuGet.Config", "NuGet.config")) {
            Path candidate = projectRoot.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Path projectConfigForWrite() {
        Path existing = findExistingProjectConfig();
        if (existing != null) {
            return existing;
        }
        return projectRoot == null ? null : projectRoot.resolve("nuget.config");
    }

    private static Path userConfigFile() {
        String home = System.getProperty("user.home", "");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = appData != null ? Path.of(appData) : Path.of(home, "AppData", "Roaming");
            return base.resolve("NuGet").resolve("NuGet.Config");
        }
        return Path.of(home, ".nuget", "NuGet", "NuGet.Config");
    }

    private static Map<String, String> readAddEntries(Document doc, String section) {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList sections = doc.getElementsByTagName(section);
        if (sections.getLength() == 0) {
            return out;
        }
        Element sectionEl = (Element) sections.item(0);
        NodeList adds = sectionEl.getElementsByTagName("add");
        for (int i = 0; i < adds.getLength(); i++) {
            Element add = (Element) adds.item(i);
            String key = add.getAttribute("key");
            String value = add.getAttribute("value");
            if (key != null && !key.isBlank()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static Element ensureSection(Document doc, String section) {
        Element root = doc.getDocumentElement();
        NodeList sections = doc.getElementsByTagName(section);
        if (sections.getLength() > 0) {
            return (Element) sections.item(0);
        }
        Element created = doc.createElement(section);
        root.appendChild(created);
        return created;
    }

    private static Element findAddByKey(Element section, String key) {
        NodeList adds = section.getElementsByTagName("add");
        for (int i = 0; i < adds.getLength(); i++) {
            Element add = (Element) adds.item(i);
            if (key.equalsIgnoreCase(add.getAttribute("key"))) {
                return add;
            }
        }
        return null;
    }

    private static void removeFromSection(Document doc, String section, String key) {
        NodeList sections = doc.getElementsByTagName(section);
        if (sections.getLength() == 0) {
            return;
        }
        Element sectionEl = (Element) sections.item(0);
        Element add = findAddByKey(sectionEl, key);
        if (add != null) {
            sectionEl.removeChild(add);
        }
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (var in = Files.newInputStream(file)) {
            Document doc = builder.parse(in);
            doc.getDocumentElement().normalize();
            return doc;
        }
    }

    private static Document newConfigDocument() throws Exception {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.newDocument();
        Element root = doc.createElement("configuration");
        doc.appendChild(root);
        Element packageSources = doc.createElement("packageSources");
        Element add = doc.createElement("add");
        add.setAttribute("key", NuGetSource.NUGET_ORG_NAME);
        add.setAttribute("value", NuGetSource.NUGET_ORG_URL);
        add.setAttribute("protocolVersion", "3");
        packageSources.appendChild(add);
        root.appendChild(packageSources);
        return doc;
    }

    private static void write(Document doc, Path file) throws Exception {
        if (file == null) {
            throw new IllegalStateException("Sem diretório de projeto para gravar o NuGet.Config.");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        stripWhitespaceNodes(doc.getDocumentElement());
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        try (var out = Files.newOutputStream(file)) {
            transformer.transform(new DOMSource(doc), new StreamResult(out));
        }
    }

    private static void stripWhitespaceNodes(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                stripWhitespaceNodes(child);
            }
        }
    }
}
