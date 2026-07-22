package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public final class IisConfig {

    public enum Kind {
        TEXT,
        BOOLEAN,
        NUMBER,
        ENUM
    }

    public record Attribute(String name, String label, Kind kind, List<String> options) {

        public static Attribute text(String name, String label) {
            return new Attribute(name, label, Kind.TEXT, List.of());
        }

        public static Attribute flag(String name, String label) {
            return new Attribute(name, label, Kind.BOOLEAN, List.of());
        }

        public static Attribute number(String name, String label) {
            return new Attribute(name, label, Kind.NUMBER, List.of());
        }

        public static Attribute options(String name, String label, String... values) {
            return new Attribute(name, label, Kind.ENUM, List.of(values));
        }
    }

    public record Collection(String title, String prefix, String elementName,
                             List<String> keys, List<String> columns) {

        public String id() {
            return (prefix == null || prefix.isBlank() ? "" : prefix) + elementName;
        }
    }

    public record Section(String id, String title, String path,
                          List<Attribute> attributes, List<Collection> collections) {

        public Section(String id, String title, String path, List<Attribute> attributes) {
            this(id, title, path, attributes, List.of());
        }

        public boolean hasCollections() {
            return collections != null && !collections.isEmpty();
        }
    }

    public record Data(Map<String, String> values, Map<String, List<Map<String, String>>> items) {

        public static Data empty() {
            return new Data(new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        public List<Map<String, String>> itemsOf(Collection collection) {
            return items.getOrDefault(collection.id(), List.of());
        }

        public boolean isEmpty() {
            if (!values.isEmpty()) {
                return false;
            }
            for (List<Map<String, String>> list : items.values()) {
                if (!list.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    private IisConfig() {
    }

    public static List<Section> catalog() {
        List<Section> sections = new ArrayList<>();

        sections.add(new Section("httpErrors", "Páginas de erro", "system.webServer/httpErrors",
                List.of(Attribute.options("errorMode", "Modo de erro",
                                "DetailedLocalOnly", "Custom", "Detailed"),
                        Attribute.options("existingResponse", "Resposta existente",
                                "Auto", "Replace", "PassThrough"),
                        Attribute.text("defaultPath", "Caminho padrão"),
                        Attribute.options("defaultResponseMode", "Modo da resposta padrão",
                                "File", "ExecuteURL", "Redirect"),
                        Attribute.text("detailedMoreInformationLink", "Link de mais informações")),
                List.of(new Collection("Erros", "", "error",
                        List.of("statusCode", "subStatusCode"),
                        List.of("statusCode", "subStatusCode", "path", "responseMode",
                                "prefixLanguageFilePath")))));

        sections.add(new Section("staticContent", "Tipos MIME", "system.webServer/staticContent",
                List.of(Attribute.text("clientCache.cacheControlMaxAge", "Cache do cliente (max-age)"),
                        Attribute.options("clientCache.cacheControlMode", "Modo de cache do cliente",
                                "NoControl", "DisableCache", "UseMaxAge", "UseExpires")),
                List.of(new Collection("Tipos MIME", "", "mimeMap",
                        List.of("fileExtension"), List.of("fileExtension", "mimeType")))));

        sections.add(new Section("httpProtocol", "Cabeçalhos de resposta", "system.webServer/httpProtocol",
                List.of(Attribute.options("allowKeepAlive", "Manter conexão", "true", "false")),
                List.of(new Collection("Cabeçalhos", "customHeaders.", "add",
                                List.of("name"), List.of("name", "value")),
                        new Collection("Redirecionamentos", "redirectHeaders.", "add",
                                List.of("name"), List.of("name", "value")))));

        sections.add(new Section("urlCompression", "Compactação", "system.webServer/urlCompression",
                List.of(Attribute.flag("doStaticCompression", "Compactar conteúdo estático"),
                        Attribute.flag("doDynamicCompression", "Compactar conteúdo dinâmico"),
                        Attribute.flag("dynamicCompressionBeforeCache", "Compactar antes do cache"))));

        sections.add(new Section("caching", "Cache de saída", "system.webServer/caching",
                List.of(Attribute.flag("enabled", "Habilitado"),
                        Attribute.flag("enableKernelCache", "Cache em modo kernel"),
                        Attribute.number("maxCacheSize", "Tamanho máximo do cache (MB)"),
                        Attribute.number("maxResponseSize", "Tamanho máximo da resposta (bytes)")),
                List.of(new Collection("Perfis", "profiles.", "add",
                        List.of("extension"),
                        List.of("extension", "policy", "kernelCachePolicy", "duration",
                                "varyByHeaders", "varyByQueryString", "location")))));

        sections.add(new Section("modules", "Módulos", "system.webServer/modules",
                List.of(Attribute.flag("runAllManagedModulesForAllRequests",
                        "Executar módulos gerenciados para todas as requisições")),
                List.of(new Collection("Módulos", "", "add",
                        List.of("name"), List.of("name", "type", "preCondition")))));

        sections.add(new Section("handlers", "Mapeamentos de manipulador", "system.webServer/handlers",
                List.of(Attribute.options("accessPolicy", "Política de acesso",
                        "Read", "Read, Script", "Read, Execute", "Read, Script, Execute")),
                List.of(new Collection("Manipuladores", "", "add",
                        List.of("name"),
                        List.of("name", "path", "verb", "modules", "scriptProcessor",
                                "resourceType", "requireAccess", "preCondition")))));

        sections.add(new Section("requestFiltering", "Filtragem de solicitações",
                "system.webServer/security/requestFiltering",
                List.of(Attribute.flag("allowDoubleEscaping", "Permitir escape duplo"),
                        Attribute.flag("allowHighBitCharacters", "Permitir caracteres de bit alto"),
                        Attribute.flag("fileExtensions.allowUnlisted", "Permitir extensões não listadas"),
                        Attribute.flag("verbs.allowUnlisted", "Permitir verbos não listados"),
                        Attribute.number("requestLimits.maxAllowedContentLength", "Tamanho máximo do conteúdo"),
                        Attribute.number("requestLimits.maxUrl", "Tamanho máximo da URL"),
                        Attribute.number("requestLimits.maxQueryString", "Tamanho máximo da query string")),
                List.of(new Collection("Extensões de nome de arquivo", "fileExtensions.", "add",
                                List.of("fileExtension"), List.of("fileExtension", "allowed")),
                        new Collection("Segmentos ocultos", "hiddenSegments.", "add",
                                List.of("segment"), List.of("segment")),
                        new Collection("URL", "denyUrlSequences.", "add",
                                List.of("sequence"), List.of("sequence")),
                        new Collection("Verbos", "verbs.", "add",
                                List.of("verb"), List.of("verb", "allowed")),
                        new Collection("Cabeçalhos", "requestLimits.headerLimits.", "add",
                                List.of("header"), List.of("header", "sizeLimit")))));

        sections.add(new Section("access", "Configurações de SSL", "system.webServer/security/access",
                List.of(Attribute.options("sslFlags", "Requisitos de SSL",
                        "None", "Ssl", "SslNegotiateCert", "SslRequireCert", "Ssl128"))));

        sections.add(new Section("directoryBrowse", "Pesquisa no diretório", "system.webServer/directoryBrowse",
                List.of(Attribute.flag("enabled", "Habilitado"),
                        Attribute.text("showFlags", "Colunas exibidas"))));

        sections.add(new Section("defaultDocument", "Documento padrão", "system.webServer/defaultDocument",
                List.of(Attribute.flag("enabled", "Habilitado")),
                List.of(new Collection("Documentos", "files.", "add",
                        List.of("value"), List.of("value")))));

        sections.add(new Section("httpLogging", "Log HTTP", "system.webServer/httpLogging",
                List.of(Attribute.flag("dontLog", "Não registrar"),
                        Attribute.flag("selectiveLogging", "Log seletivo"))));

        return sections;
    }

    public static Section custom(String path) {
        return new Section("custom", path, path, List.of(), List.of());
    }

    public static Data read(String target, Section section) {
        IisProcess.Result result = AppCmd.read(
                List.of("list", "config", target, "/section:" + section.path()));
        if (!result.ok()) {
            log.debug("Falha ao ler a seção {}: {}", section.path(), result.output());
            return Data.empty();
        }
        return parse(result.output(), section);
    }

    static Data parse(String xml, Section section) {
        Element root = AppCmd.parse(xml);
        if (root == null) {
            return Data.empty();
        }
        Map<String, String> values = new LinkedHashMap<>();
        flatten(root, "", values);

        Map<String, List<Map<String, String>>> items = new LinkedHashMap<>();
        for (Collection collection : section.collections()) {
            List<Map<String, String>> list = new ArrayList<>();
            Element container = containerOf(root, collection);
            if (container != null) {
                NodeList children = container.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element element
                            && element.getTagName().equals(collection.elementName())) {
                        Map<String, String> item = new LinkedHashMap<>();
                        NamedNodeMap attributes = element.getAttributes();
                        for (int j = 0; j < attributes.getLength(); j++) {
                            Node attribute = attributes.item(j);
                            item.put(attribute.getNodeName(), attribute.getNodeValue());
                        }
                        list.add(item);
                    }
                }
            }
            items.put(collection.id(), list);
        }
        return new Data(values, items);
    }

    private static Element containerOf(Element root, Collection collection) {
        if (collection.prefix() == null || collection.prefix().isBlank()) {
            return root;
        }
        String path = collection.prefix().endsWith(".")
                ? collection.prefix().substring(0, collection.prefix().length() - 1)
                : collection.prefix();
        Element current = root;
        for (String name : path.split("\\.")) {
            Element next = null;
            NodeList children = current.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element element && name.equals(element.getTagName())) {
                    next = element;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private static void flatten(Element element, String prefix, Map<String, String> values) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            values.put(prefix + attribute.getNodeName(), attribute.getNodeValue());
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && !"add".equals(child.getTagName())) {
                flatten(child, prefix + child.getTagName() + ".", values);
            }
        }
    }

    public static IisService.Result apply(String target, Section section, Data current, Data desired) {
        List<String> failures = new ArrayList<>();
        applyScalars(target, section, current, desired, failures);
        for (Collection collection : section.collections()) {
            applyCollection(target, section, collection, current, desired, failures);
        }
        return failures.isEmpty()
                ? IisService.Result.ok()
                : IisService.Result.fail(String.join(System.lineSeparator(), failures));
    }

    private static void applyScalars(String target, Section section, Data current, Data desired,
                                     List<String> failures) {
        List<String> arguments = new ArrayList<>(List.of("set", "config", target,
                "/section:" + section.path()));
        boolean changed = false;
        for (Map.Entry<String, String> entry : desired.values().entrySet()) {
            String previous = current.values().get(entry.getKey());
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (previous != null && previous.equals(value)) {
                continue;
            }
            if (previous == null && value.isBlank()) {
                continue;
            }
            arguments.add("/" + entry.getKey() + ":" + value);
            changed = true;
        }
        if (!changed) {
            return;
        }
        arguments.add("/commit:apphost");
        IisProcess.Result result = AppCmd.write(arguments);
        if (!result.ok()) {
            failures.add(section.title() + ": " + firstLine(result.output()));
        }
    }

    private static void applyCollection(String target, Section section, Collection collection,
                                        Data current, Data desired, List<String> failures) {
        List<Map<String, String>> before = current.itemsOf(collection);
        List<Map<String, String>> after = desired.itemsOf(collection);
        for (Map<String, String> item : before) {
            if (findByKeys(after, item, collection.keys()) == null) {
                remove(target, section, collection, item, failures);
            }
        }
        for (Map<String, String> item : after) {
            Map<String, String> previous = findByKeys(before, item, collection.keys());
            if (previous == null) {
                add(target, section, collection, item, failures);
            } else if (!sameValues(previous, item, collection.columns())) {
                remove(target, section, collection, previous, failures);
                add(target, section, collection, item, failures);
            }
        }
    }

    private static void add(String target, Section section, Collection collection,
                            Map<String, String> item, List<String> failures) {
        String descriptor = "/+" + collection.prefix() + selector(item, collection.columns());
        IisProcess.Result result = AppCmd.write(List.of("set", "config", target,
                "/section:" + section.path(), descriptor, "/commit:apphost"));
        if (!result.ok()) {
            failures.add("+" + describe(item, collection.keys()) + ": " + firstLine(result.output()));
        }
    }

    private static void remove(String target, Section section, Collection collection,
                               Map<String, String> item, List<String> failures) {
        String descriptor = "/-" + collection.prefix() + selector(item, collection.keys());
        IisProcess.Result result = AppCmd.write(List.of("set", "config", target,
                "/section:" + section.path(), descriptor, "/commit:apphost"));
        if (!result.ok()) {
            failures.add("-" + describe(item, collection.keys()) + ": " + firstLine(result.output()));
        }
    }

    static String selector(Map<String, String> item, List<String> fields) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (String field : fields) {
            String value = item.get(field);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!first) {
                builder.append(',');
            }
            builder.append(field).append("='").append(value.replace("'", "")).append('\'');
            first = false;
        }
        return builder.append(']').toString();
    }

    private static Map<String, String> findByKeys(List<Map<String, String>> items, Map<String, String> item,
                                                  List<String> keys) {
        for (Map<String, String> candidate : items) {
            if (sameValues(candidate, item, keys)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean sameValues(Map<String, String> left, Map<String, String> right, List<String> fields) {
        for (String field : fields) {
            String a = left.get(field);
            String b = right.get(field);
            String first = a == null ? "" : a.strip();
            String second = b == null ? "" : b.strip();
            if (!first.equals(second)) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Map<String, String> item, List<String> keys) {
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            String value = item.get(key);
            if (value != null && !value.isBlank()) {
                parts.add(value);
            }
        }
        return String.join("/", parts);
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "falha desconhecida";
        }
        return output.strip().split("\\R")[0].strip();
    }
}
