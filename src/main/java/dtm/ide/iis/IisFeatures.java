package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class IisFeatures {

    public static final String SECTION_ANONYMOUS =
            "system.webServer/security/authentication/anonymousAuthentication";
    public static final String SECTION_WINDOWS =
            "system.webServer/security/authentication/windowsAuthentication";
    public static final String SECTION_BASIC =
            "system.webServer/security/authentication/basicAuthentication";
    public static final String SECTION_DIRECTORY_BROWSE = "system.webServer/directoryBrowse";
    public static final String SECTION_DEFAULT_DOCUMENT = "system.webServer/defaultDocument";

    public record Settings(boolean anonymousAuthentication,
                           boolean windowsAuthentication,
                           boolean basicAuthentication,
                           boolean directoryBrowse,
                           boolean defaultDocument,
                           List<String> documents) {

        public static Settings defaults() {
            return new Settings(true, false, false, false, true,
                    List.of("Default.htm", "Default.asp", "index.htm", "index.html"));
        }
    }

    private IisFeatures() {
    }

    public static Settings read(String target) {
        return new Settings(
                readEnabled(target, SECTION_ANONYMOUS, true),
                readEnabled(target, SECTION_WINDOWS, false),
                readEnabled(target, SECTION_BASIC, false),
                readEnabled(target, SECTION_DIRECTORY_BROWSE, false),
                readEnabled(target, SECTION_DEFAULT_DOCUMENT, true),
                readDocuments(target));
    }

    public static IisService.Result apply(String target, Settings current, Settings desired) {
        List<String> failures = new ArrayList<>();
        applyToggle(target, SECTION_ANONYMOUS, current.anonymousAuthentication(),
                desired.anonymousAuthentication(), failures);
        applyToggle(target, SECTION_WINDOWS, current.windowsAuthentication(),
                desired.windowsAuthentication(), failures);
        applyToggle(target, SECTION_BASIC, current.basicAuthentication(),
                desired.basicAuthentication(), failures);
        applyToggle(target, SECTION_DIRECTORY_BROWSE, current.directoryBrowse(),
                desired.directoryBrowse(), failures);
        applyToggle(target, SECTION_DEFAULT_DOCUMENT, current.defaultDocument(),
                desired.defaultDocument(), failures);
        applyDocuments(target, current.documents(), desired.documents(), failures);

        if (failures.isEmpty()) {
            return IisService.Result.ok();
        }
        return IisService.Result.fail(String.join(System.lineSeparator(), failures));
    }

    private static void applyToggle(String target, String section, boolean current, boolean desired,
                                    List<String> failures) {
        if (current == desired) {
            return;
        }
        IisProcess.Result result = AppCmd.write(List.of("set", "config", target,
                "/section:" + section, "/enabled:" + desired, "/commit:apphost"));
        if (!result.ok()) {
            failures.add(shortName(section) + ": " + firstLine(result.output()));
        }
    }

    private static void applyDocuments(String target, List<String> current, List<String> desired,
                                       List<String> failures) {
        for (String document : current) {
            if (!desired.contains(document)) {
                IisProcess.Result result = AppCmd.write(List.of("set", "config", target,
                        "/section:" + SECTION_DEFAULT_DOCUMENT,
                        "/-files.[value='" + document + "']", "/commit:apphost"));
                if (!result.ok()) {
                    failures.add("defaultDocument -" + document + ": " + firstLine(result.output()));
                }
            }
        }
        for (String document : desired) {
            if (!current.contains(document)) {
                IisProcess.Result result = AppCmd.write(List.of("set", "config", target,
                        "/section:" + SECTION_DEFAULT_DOCUMENT,
                        "/+files.[value='" + document + "']", "/commit:apphost"));
                if (!result.ok()) {
                    failures.add("defaultDocument +" + document + ": " + firstLine(result.output()));
                }
            }
        }
    }

    static boolean readEnabled(String target, String section, boolean fallback) {
        IisProcess.Result result = AppCmd.read(List.of("list", "config", target, "/section:" + section));
        if (!result.ok()) {
            return fallback;
        }
        return parseEnabled(result.output(), fallback);
    }

    static boolean parseEnabled(String xml, boolean fallback) {
        Element root = AppCmd.parse(xml);
        if (root == null) {
            return fallback;
        }
        String enabled = root.getAttribute("enabled");
        if (enabled == null || enabled.isBlank()) {
            Element nested = firstWithEnabled(root);
            enabled = nested == null ? null : nested.getAttribute("enabled");
        }
        if (enabled == null || enabled.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(enabled.strip());
    }

    private static Element firstWithEnabled(Element root) {
        NodeList children = root.getElementsByTagName("*");
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) {
                String enabled = element.getAttribute("enabled");
                if (enabled != null && !enabled.isBlank()) {
                    return element;
                }
            }
        }
        return null;
    }

    static List<String> readDocuments(String target) {
        IisProcess.Result result = AppCmd.read(
                List.of("list", "config", target, "/section:" + SECTION_DEFAULT_DOCUMENT));
        return result.ok() ? parseDocuments(result.output()) : List.of();
    }

    static List<String> parseDocuments(String xml) {
        Element root = AppCmd.parse(xml);
        if (root == null) {
            return List.of();
        }
        List<String> documents = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("add");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                String value = element.getAttribute("value");
                if (value != null && !value.isBlank() && !documents.contains(value)) {
                    documents.add(value.strip());
                }
            }
        }
        return documents;
    }

    private static String shortName(String section) {
        int slash = section.lastIndexOf('/');
        return slash < 0 ? section : section.substring(slash + 1);
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "falha desconhecida";
        }
        return output.strip().split("\\R")[0].strip();
    }
}
