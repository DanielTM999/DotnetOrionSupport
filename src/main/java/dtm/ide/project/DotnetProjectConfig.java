package dtm.ide.project;

import dtm.ide.run.TargetFramework;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
public final class DotnetProjectConfig {

    public static final String TARGET_FRAMEWORK = "TargetFramework";
    public static final String OUTPUT_TYPE = "OutputType";
    public static final String LANG_VERSION = "LangVersion";
    public static final String NULLABLE = "Nullable";
    public static final String IMPLICIT_USINGS = "ImplicitUsings";

    private static final String[] PROPERTY_KEYS = {
            TARGET_FRAMEWORK, OUTPUT_TYPE, LANG_VERSION, NULLABLE, IMPLICIT_USINGS
    };

    private final Path projectRoot;

    public DotnetProjectConfig(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    public Optional<Path> projectFile() {
        return Optional.ofNullable(TargetFramework.findPrimaryProjectFile(projectRoot));
    }

    public Map<String, String> readProperties() {
        Map<String, String> values = new LinkedHashMap<>();
        Path csproj = projectFile().orElse(null);
        if (csproj == null || !Files.isRegularFile(csproj)) {
            return values;
        }
        try {
            Document doc = parse(csproj);
            for (String key : PROPERTY_KEYS) {
                NodeList nodes = doc.getElementsByTagName(key);
                if (nodes.getLength() > 0) {
                    values.put(key, nodes.item(0).getTextContent().trim());
                }
            }
        } catch (Exception e) {
            log.warn("Falha ao ler propriedades do csproj: {}", e.getMessage());
        }
        return values;
    }

    public void writeProperties(Map<String, String> properties) throws Exception {
        Path csproj = projectFile().orElseThrow(() ->
                new IllegalStateException("Nenhum .csproj encontrado para configurar."));
        Document doc = parse(csproj);
        Element propertyGroup = firstPropertyGroup(doc);
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                removeProperty(doc, key);
            } else {
                upsertProperty(doc, propertyGroup, key, value.trim());
            }
        }
        write(doc, csproj);
    }

    public String readSdkVersion() {
        Path globalJson = globalJsonFile();
        if (globalJson == null || !Files.isRegularFile(globalJson)) {
            return "";
        }
        try {
            String content = Files.readString(globalJson);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    public void writeSdkVersion(String version) throws Exception {
        Path globalJson = globalJsonFile();
        if (globalJson == null) {
            throw new IllegalStateException("Sem diretório de projeto para gravar global.json.");
        }
        if (version == null || version.isBlank()) {
            Files.deleteIfExists(globalJson);
            return;
        }
        String content = "{\n  \"sdk\": {\n    \"version\": \"" + version.trim() + "\"\n  }\n}\n";
        Files.writeString(globalJson, content);
    }

    private Path globalJsonFile() {
        return projectRoot == null ? null : projectRoot.resolve("global.json");
    }

    private static Element firstPropertyGroup(Document doc) {
        NodeList groups = doc.getElementsByTagName("PropertyGroup");
        if (groups.getLength() > 0) {
            return (Element) groups.item(0);
        }
        Element group = doc.createElement("PropertyGroup");
        doc.getDocumentElement().appendChild(group);
        return group;
    }

    private static void upsertProperty(Document doc, Element propertyGroup, String key, String value) {
        NodeList existing = doc.getElementsByTagName(key);
        if (existing.getLength() > 0) {
            existing.item(0).setTextContent(value);
            return;
        }
        Element element = doc.createElement(key);
        element.setTextContent(value);
        propertyGroup.appendChild(element);
    }

    private static void removeProperty(Document doc, String key) {
        NodeList existing = doc.getElementsByTagName(key);
        for (int i = existing.getLength() - 1; i >= 0; i--) {
            Element element = (Element) existing.item(i);
            element.getParentNode().removeChild(element);
        }
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (var in = Files.newInputStream(file)) {
            Document doc = factory.newDocumentBuilder().parse(in);
            doc.getDocumentElement().normalize();
            return doc;
        }
    }

    private static void write(Document doc, Path file) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        try (var out = Files.newOutputStream(file)) {
            transformer.transform(new DOMSource(doc), new StreamResult(out));
        }
    }
}
