package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
public final class IisWebConfig {

    public static final String LOGS_FOLDER = "logs";

    private IisWebConfig() {
    }

    public static Result applyAspNetCore(Path contentRoot, Map<String, String> environment,
                                         boolean enableStdoutLog) {
        if (contentRoot == null) {
            return Result.skipped();
        }
        Path webConfig = contentRoot.resolve("web.config");
        if (!Files.isRegularFile(webConfig)) {
            return Result.skipped();
        }
        try {
            Document document = read(webConfig);
            Element aspNetCore = firstAspNetCore(document);
            if (aspNetCore == null) {
                return Result.skipped();
            }
            boolean changed = applyEnvironment(document, aspNetCore, environment);
            boolean loggingEnabled = false;
            if (enableStdoutLog) {
                loggingEnabled = enableStdoutLog(aspNetCore);
                changed |= loggingEnabled;
            }
            if (changed) {
                write(document, webConfig);
            }
            return new Result(changed, loggingEnabled || stdoutLogEnabled(aspNetCore));
        } catch (Exception e) {
            log.debug("Falha ao ajustar web.config do IIS: {}", e.getMessage());
            return Result.skipped();
        }
    }

    public record Result(boolean changed, boolean stdoutLogEnabled) {

        static Result skipped() {
            return new Result(false, false);
        }
    }

    private static Element firstAspNetCore(Document document) {
        NodeList nodes = document.getElementsByTagName("aspNetCore");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    private static boolean applyEnvironment(Document document, Element aspNetCore,
                                            Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return false;
        }
        Element container = directChild(aspNetCore, "environmentVariables");
        if (container == null) {
            container = document.createElement("environmentVariables");
            aspNetCore.appendChild(container);
        }
        boolean changed = false;
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            changed |= setEnvironmentVariable(document, container, name,
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return changed;
    }

    private static boolean setEnvironmentVariable(Document document, Element container,
                                                  String name, String value) {
        NodeList existing = container.getElementsByTagName("environmentVariable");
        for (int i = 0; i < existing.getLength(); i++) {
            if (existing.item(i) instanceof Element element && name.equals(element.getAttribute("name"))) {
                if (value.equals(element.getAttribute("value"))) {
                    return false;
                }
                element.setAttribute("value", value);
                return true;
            }
        }
        Element element = document.createElement("environmentVariable");
        element.setAttribute("name", name);
        element.setAttribute("value", value);
        container.appendChild(element);
        return true;
    }

    private static boolean enableStdoutLog(Element aspNetCore) {
        boolean changed = false;
        if (!"true".equalsIgnoreCase(aspNetCore.getAttribute("stdoutLogEnabled"))) {
            aspNetCore.setAttribute("stdoutLogEnabled", "true");
            changed = true;
        }
        String logFile = aspNetCore.getAttribute("stdoutLogFile");
        if (logFile == null || logFile.isBlank()) {
            aspNetCore.setAttribute("stdoutLogFile", ".\\" + LOGS_FOLDER + "\\stdout");
            changed = true;
        }
        return changed;
    }

    private static boolean stdoutLogEnabled(Element aspNetCore) {
        return "true".equalsIgnoreCase(aspNetCore.getAttribute("stdoutLogEnabled"));
    }

    private static Element directChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tag.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static Document read(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    private static void write(Document document, Path target) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        try (var out = Files.newOutputStream(target)) {
            transformer.transform(new DOMSource(document), new StreamResult(out));
        }
    }
}
