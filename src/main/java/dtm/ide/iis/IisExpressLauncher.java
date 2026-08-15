package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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
import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class IisExpressLauncher {

    public record SiteSpec(String siteName, Path contentRoot, List<IisBinding> bindings, boolean aspNetCore) {

        public IisBinding primaryBinding() {
            for (IisBinding binding : bindings) {
                if ("http".equalsIgnoreCase(binding.protocol())) {
                    return binding;
                }
            }
            return bindings.isEmpty() ? null : bindings.getFirst();
        }
    }

    public record Prepared(Path configFile, String siteName, String url) {
    }

    public static final String DEFAULT_POOL = "OrionAspNetCorePool";
    private static final String CLASSIC_POOL = "Clr4IntegratedAppPool";
    private static final String ASPNETCORE_MODULE = "AspNetCoreModuleV2";

    private IisExpressLauncher() {
    }

    public static Path executable() {
        return IisEnvironment.current().iisExpress();
    }

    public static boolean available() {
        return executable() != null;
    }

    public static Prepared prepare(Path projectFile, Path workspaceRoot, SiteSpec spec) throws Exception {
        Path iisExpress = executable();
        if (iisExpress == null) {
            throw new IllegalStateException("IIS Express não está instalado nesta máquina.");
        }
        Document document = buildConfig(locateTemplate(iisExpress), spec, locateAspNetCoreModule(iisExpress));

        Path target = orionConfig(workspaceRoot, projectFile);
        Files.createDirectories(target.getParent());
        write(document, target);
        return new Prepared(target, spec.siteName(), resolveUrl(spec));
    }

    public static List<String> command(Path configFile, String siteName) {
        Path iisExpress = executable();
        List<String> command = new ArrayList<>();
        command.add(iisExpress.toAbsolutePath().toString());
        command.add("/config:" + configFile.toAbsolutePath());
        command.add("/site:" + siteName);
        command.add("/trace:error");
        return command;
    }

    private static String resolveUrl(SiteSpec spec) {
        IisBinding binding = spec.primaryBinding();
        return binding == null ? null : binding.url();
    }

    private static Path orionConfig(Path workspaceRoot, Path projectFile) {
        Path root = workspaceRoot != null ? workspaceRoot : parentOf(projectFile);
        return root.resolve(".orion")
                .resolve("iisexpress")
                .resolve("config")
                .resolve("applicationhost.config")
                .toAbsolutePath()
                .normalize();
    }

    private static Path parentOf(Path projectFile) {
        return projectFile == null ? null : projectFile.getParent();
    }

    static Document buildConfig(Path templateFile, SiteSpec spec, Path aspNetCoreModule) throws Exception {
        Document document = read(templateFile);
        if (spec.aspNetCore() && !ensureAspNetCoreModule(document, aspNetCoreModule)) {
            throw new IllegalStateException("O ASP.NET Core Module (aspnetcorev2.dll) não foi encontrado no IIS Express. "
                    + "Instale o ASP.NET Core Hosting Bundle para hospedar projetos ASP.NET Core.");
        }
        String pool = spec.aspNetCore() ? DEFAULT_POOL : CLASSIC_POOL;
        if (spec.aspNetCore()) {
            ensureApplicationPool(document, pool);
        }
        applySite(document, spec, pool);
        return document;
    }

    private static Path locateTemplate(Path iisExpress) {
        for (Path candidate : templateCandidates(iisExpress)) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Template applicationhost.config do IIS Express não encontrado.");
    }

    private static List<Path> templateCandidates(Path iisExpress) {
        List<Path> candidates = new ArrayList<>();
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.isBlank()) {
            candidates.add(Path.of(userProfile, "Documents", "IISExpress", "config", "applicationhost.config"));
            candidates.add(Path.of(userProfile, "Documentos", "IISExpress", "config", "applicationhost.config"));
        }
        Path installDirectory = iisExpress.getParent();
        if (installDirectory != null) {
            candidates.add(installDirectory.resolve("config")
                    .resolve("templates").resolve("PersonalWebServer").resolve("applicationhost.config"));
            candidates.add(installDirectory.resolve("AppServer").resolve("applicationhost.config"));
        }
        return candidates;
    }

    private static boolean ensureAspNetCoreModule(Document document, Path module) {
        Element root = document.getDocumentElement();
        if (hasNamedEntry(firstChild(root, "system.webServer", "globalModules"), ASPNETCORE_MODULE)) {
            ensureModuleReference(document, root);
            return true;
        }
        if (module == null) {
            return false;
        }
        Element globalModules = ensurePath(document, root, "system.webServer", "globalModules");
        Element entry = document.createElement("add");
        entry.setAttribute("name", ASPNETCORE_MODULE);
        entry.setAttribute("image", module.toAbsolutePath().toString());
        globalModules.appendChild(entry);
        ensureSectionDeclaration(document, root);
        ensureModuleReference(document, root);
        return true;
    }

    private static void ensureModuleReference(Document document, Element root) {
        Element modules = ensurePath(document, root, "system.webServer", "modules");
        if (hasNamedEntry(modules, ASPNETCORE_MODULE)) {
            return;
        }
        Element entry = document.createElement("add");
        entry.setAttribute("name", ASPNETCORE_MODULE);
        modules.appendChild(entry);
    }

    private static void ensureSectionDeclaration(Document document, Element root) {
        Element configSections = ensureChild(document, root, "configSections");
        Element group = null;
        NodeList groups = configSections.getElementsByTagName("sectionGroup");
        for (int i = 0; i < groups.getLength(); i++) {
            if (groups.item(i) instanceof Element element
                    && "system.webServer".equals(element.getAttribute("name"))) {
                group = element;
                break;
            }
        }
        if (group == null) {
            group = document.createElement("sectionGroup");
            group.setAttribute("name", "system.webServer");
            configSections.appendChild(group);
        }
        NodeList sections = group.getElementsByTagName("section");
        for (int i = 0; i < sections.getLength(); i++) {
            if (sections.item(i) instanceof Element element
                    && "aspNetCore".equals(element.getAttribute("name"))) {
                return;
            }
        }
        Element section = document.createElement("section");
        section.setAttribute("name", "aspNetCore");
        section.setAttribute("overrideModeDefault", "Allow");
        group.appendChild(section);
    }

    private static Path locateAspNetCoreModule(Path iisExpress) {
        List<Path> candidates = new ArrayList<>();
        Path installDirectory = iisExpress.getParent();
        if (installDirectory != null) {
            candidates.add(installDirectory.resolve("aspnetcorev2.dll"));
            candidates.add(installDirectory.resolve("Asp.Net Core Module V2").resolve("aspnetcorev2.dll"));
        }
        String windir = System.getenv("windir");
        if (windir != null && !windir.isBlank()) {
            candidates.add(Path.of(windir, "system32", "inetsrv", "aspnetcorev2.dll"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return IisEnvironment.locateAspNetCoreModule();
    }

    private static void ensureApplicationPool(Document document, String poolName) {
        Element pools = ensurePath(document, document.getDocumentElement(),
                "system.applicationHost", "applicationPools");
        if (hasNamedEntry(pools, poolName)) {
            return;
        }
        Element pool = document.createElement("add");
        pool.setAttribute("name", poolName);
        pool.setAttribute("managedRuntimeVersion", "");
        pool.setAttribute("managedPipelineMode", "Integrated");
        Node first = pools.getFirstChild();
        if (first == null) {
            pools.appendChild(pool);
        } else {
            pools.insertBefore(pool, first);
        }
    }

    private static void applySite(Document document, SiteSpec spec, String poolName) {
        Element sites = ensurePath(document, document.getDocumentElement(), "system.applicationHost", "sites");
        long maxId = 0;
        List<Element> existing = new ArrayList<>();
        NodeList nodes = sites.getElementsByTagName("site");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                existing.add(element);
                maxId = Math.max(maxId, AppCmd.parseLong(element.getAttribute("id"), 0));
            }
        }
        for (Element element : existing) {
            if (spec.siteName().equalsIgnoreCase(element.getAttribute("name"))) {
                sites.removeChild(element);
            }
        }

        Element site = document.createElement("site");
        site.setAttribute("name", spec.siteName());
        site.setAttribute("id", String.valueOf(maxId + 1));

        Element application = document.createElement("application");
        application.setAttribute("path", "/");
        application.setAttribute("applicationPool", poolName);

        Element virtualDirectory = document.createElement("virtualDirectory");
        virtualDirectory.setAttribute("path", "/");
        virtualDirectory.setAttribute("physicalPath", spec.contentRoot().toAbsolutePath().normalize().toString());
        application.appendChild(virtualDirectory);
        site.appendChild(application);

        Element bindings = document.createElement("bindings");
        for (IisBinding binding : spec.bindings()) {
            Element element = document.createElement("binding");
            element.setAttribute("protocol", binding.protocol());
            element.setAttribute("bindingInformation", binding.bindingInformation());
            bindings.appendChild(element);
        }
        site.appendChild(bindings);
        sites.appendChild(site);
    }

    private static boolean hasNamedEntry(Element parent, String name) {
        if (parent == null) {
            return false;
        }
        NodeList nodes = parent.getElementsByTagName("add");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && name.equals(element.getAttribute("name"))) {
                return true;
            }
        }
        return false;
    }

    private static Element ensurePath(Document document, Element root, String... tags) {
        Element current = root;
        for (String tag : tags) {
            current = ensureChild(document, current, tag);
        }
        return current;
    }

    private static Element ensureChild(Document document, Element parent, String tag) {
        Element child = directChild(parent, tag);
        if (child != null) {
            return child;
        }
        Element created = document.createElement(tag);
        parent.appendChild(created);
        return created;
    }

    private static Element firstChild(Element root, String... tags) {
        Element current = root;
        for (String tag : tags) {
            current = directChild(current, tag);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static Element directChild(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
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

    static void write(Document document, Path target) throws Exception {
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
