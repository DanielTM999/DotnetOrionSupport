package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class AppCmd {

    private static final long READ_TIMEOUT_SECONDS = 60;
    private static final long WRITE_TIMEOUT_SECONDS = 180;

    private static final AtomicBoolean elevationRequired = new AtomicBoolean(false);

    private AppCmd() {
    }

    public static boolean available() {
        return IisEnvironment.current().manageable();
    }

    public static void resetElevationHint() {
        elevationRequired.set(false);
    }

    public static IisProcess.Result read(List<String> arguments) {
        return execute(arguments, READ_TIMEOUT_SECONDS);
    }

    public static IisProcess.Result write(List<String> arguments) {
        return execute(arguments, WRITE_TIMEOUT_SECONDS);
    }

    private static IisProcess.Result execute(List<String> arguments, long timeoutSeconds) {
        Path appCmd = IisEnvironment.current().appCmd();
        if (appCmd == null) {
            return new IisProcess.Result(-1, "appcmd.exe não encontrado: o IIS não está instalado nesta máquina.");
        }
        if (elevationRequired.get()) {
            return IisBroker.run(IisBroker.Tool.APP_CMD, arguments, timeoutSeconds);
        }
        List<String> command = new ArrayList<>();
        command.add(appCmd.toAbsolutePath().toString());
        command.addAll(arguments);
        IisProcess.Result result = IisProcess.capture(command, timeoutSeconds);
        if (result.ok()) {
            return result;
        }
        log.info("appcmd {} falhou sem elevação, delegando ao assistente elevado: {}",
                arguments.isEmpty() ? "" : arguments.getFirst(), firstLine(result.output()));
        elevationRequired.set(true);
        IisProcess.Result elevated = IisBroker.run(IisBroker.Tool.APP_CMD, arguments, timeoutSeconds);
        if (!elevated.ok() && !result.accessDenied()) {
            elevationRequired.set(false);
        }
        return elevated;
    }

    public static List<IisProcess.Result> readAll(List<List<String>> argumentSets) {
        Path appCmd = IisEnvironment.current().appCmd();
        if (appCmd == null || argumentSets == null || argumentSets.isEmpty()) {
            return List.of();
        }
        String executable = appCmd.toAbsolutePath().toString();
        if (elevationRequired.get()) {
            return brokerReadAll(argumentSets);
        }

        List<IisProcess.Result> direct = new ArrayList<>();
        boolean anyFailed = false;
        for (List<String> arguments : argumentSets) {
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.addAll(arguments);
            IisProcess.Result result = IisProcess.capture(command, READ_TIMEOUT_SECONDS);
            anyFailed |= !result.ok();
            direct.add(result);
        }
        if (!anyFailed) {
            return direct;
        }
        log.info("Leitura do IIS falhou sem elevação, delegando ao assistente elevado.");
        elevationRequired.set(true);
        return brokerReadAll(argumentSets);
    }

    private static List<IisProcess.Result> brokerReadAll(List<List<String>> argumentSets) {
        List<IisProcess.Result> results = new ArrayList<>();
        for (List<String> arguments : argumentSets) {
            results.add(IisBroker.run(IisBroker.Tool.APP_CMD, arguments, READ_TIMEOUT_SECONDS));
        }
        return results;
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String[] lines = output.strip().split("\\R");
        return lines.length == 0 ? "" : lines[0].strip();
    }

    public static List<IisAppPool> listAppPools() {
        Map<String, Map<String, String>> configs = appPoolConfigs();
        List<IisAppPool> pools = new ArrayList<>();
        for (Map<String, String> entry : listEntries("apppool", "APPPOOL")) {
            String name = entry.get("APPPOOL.NAME");
            if (name == null || name.isBlank()) {
                continue;
            }
            pools.add(readAppPool(name, entry, configs.getOrDefault(name, Map.of())));
        }
        return pools;
    }

    public static IisAppPool readAppPool(String name) {
        Map<String, Map<String, String>> configs = appPoolConfigs();
        for (Map<String, String> entry : listEntries("apppool", "APPPOOL")) {
            if (name.equals(entry.get("APPPOOL.NAME"))) {
                return readAppPool(name, entry, configs.getOrDefault(name, Map.of()));
            }
        }
        return null;
    }

    private static IisAppPool readAppPool(String name, Map<String, String> listEntry, Map<String, String> config) {
        String state = listEntry.getOrDefault("state", "");
        return new IisAppPool(name,
                state,
                config.getOrDefault("managedRuntimeVersion", listEntry.getOrDefault("RuntimeVersion", "")),
                config.getOrDefault("managedPipelineMode", listEntry.getOrDefault("PipelineMode", "Integrated")),
                config.getOrDefault("processModel.identityType", "ApplicationPoolIdentity"),
                config.getOrDefault("processModel.userName", ""),
                Boolean.parseBoolean(config.getOrDefault("enable32BitAppOnWin64", "false")),
                config.getOrDefault("startMode", "OnDemand"),
                config.getOrDefault("autoStart", "true"),
                parseLong(config.get("queueLength"), 1000),
                parseTimeSpanMinutes(config.get("processModel.idleTimeout"), 20),
                parseLong(config.get("processModel.maxProcesses"), 1),
                parseTimeSpanMinutes(config.get("recycling.periodicRestart.time"), 1740),
                parseLong(config.get("recycling.periodicRestart.privateMemory"), 0),
                parseLong(config.get("recycling.periodicRestart.memory"), 0),
                config);
    }

    private static Map<String, Map<String, String>> appPoolConfigs() {
        IisProcess.Result result = read(LIST_APP_POOL_CONFIG);
        return result.ok() ? parseAppPoolConfigs(result.output()) : Map.of();
    }

    private static Map<String, Map<String, String>> parseAppPoolConfigs(String xml) {
        Element root = parse(xml);
        if (root == null) {
            return Map.of();
        }
        Element pools = firstElement(root, "applicationPools");
        if (pools == null) {
            return Map.of();
        }
        Map<String, Map<String, String>> configs = new LinkedHashMap<>();
        NodeList children = pools.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element element) || !"add".equals(element.getTagName())) {
                continue;
            }
            String name = element.getAttribute("name");
            if (name == null || name.isBlank() || configs.containsKey(name)) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            collectAttributes(element, "", values);
            configs.put(name, values);
        }
        return configs;
    }

    private static void collectAttributes(Element element, String prefix, Map<String, String> values) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            values.put(prefix + attribute.getNodeName(), attribute.getNodeValue());
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                collectAttributes(childElement, prefix + childElement.getTagName() + ".", values);
            }
        }
    }

    public static List<IisSite> listSites() {
        List<IisVirtualDirectory> directories = listVirtualDirectories();
        return listSites(listApplications(directories), directories);
    }

    public static List<IisSite> listSites(List<IisApplication> applications,
                                          List<IisVirtualDirectory> directories) {
        return toSites(listEntries("site", "SITE"), applications, directories);
    }

    private static List<IisSite> toSites(List<Map<String, String>> entries,
                                         List<IisApplication> applications,
                                         List<IisVirtualDirectory> directories) {
        Map<String, String> rootPaths = new LinkedHashMap<>();
        for (IisVirtualDirectory vdir : directories) {
            if (vdir.isRoot() && vdir.applicationName() != null) {
                rootPaths.put(vdir.applicationName(), vdir.physicalPath());
            }
        }
        Map<String, String> rootPools = new LinkedHashMap<>();
        for (IisApplication app : applications) {
            if (app.isRoot() && app.siteName() != null) {
                rootPools.put(app.siteName(), app.applicationPool());
            }
        }
        List<IisSite> sites = new ArrayList<>();
        for (Map<String, String> entry : entries) {
            String name = entry.get("SITE.NAME");
            if (name == null || name.isBlank()) {
                continue;
            }
            sites.add(new IisSite(name,
                    entry.getOrDefault("SITE.ID", ""),
                    entry.getOrDefault("state", ""),
                    IisBinding.parse(entry.get("bindings")),
                    rootPaths.get(name + "/"),
                    rootPools.get(name)));
        }
        return sites;
    }

    public static List<IisApplication> listApplications() {
        return listApplications(listVirtualDirectories());
    }

    public static List<IisApplication> listApplications(List<IisVirtualDirectory> directories) {
        return toApplications(listEntries("app", "APP"), directories);
    }

    private static List<IisApplication> toApplications(List<Map<String, String>> entries,
                                                       List<IisVirtualDirectory> directories) {
        List<IisApplication> applications = new ArrayList<>();
        Map<String, String> paths = new LinkedHashMap<>();
        for (IisVirtualDirectory vdir : directories) {
            if (vdir.isRoot() && vdir.applicationName() != null) {
                paths.put(vdir.applicationName(), vdir.physicalPath());
            }
        }
        for (Map<String, String> entry : entries) {
            String name = entry.get("APP.NAME");
            if (name == null || name.isBlank()) {
                continue;
            }
            applications.add(new IisApplication(name,
                    entry.getOrDefault("SITE.NAME", ""),
                    entry.getOrDefault("path", "/"),
                    entry.getOrDefault("APPPOOL.NAME", ""),
                    paths.get(name),
                    entry.getOrDefault("enabledProtocols", "http")));
        }
        return applications;
    }

    public static List<IisVirtualDirectory> listVirtualDirectories() {
        return toVirtualDirectories(listEntries("vdir", "VDIR"));
    }

    private static List<IisVirtualDirectory> toVirtualDirectories(List<Map<String, String>> entries) {
        List<IisVirtualDirectory> directories = new ArrayList<>();
        for (Map<String, String> entry : entries) {
            String name = entry.get("VDIR.NAME");
            if (name == null || name.isBlank()) {
                continue;
            }
            directories.add(new IisVirtualDirectory(name,
                    entry.getOrDefault("APP.NAME", ""),
                    entry.getOrDefault("path", "/"),
                    entry.getOrDefault("physicalPath", "")));
        }
        return directories;
    }

    public static List<IisWorkerProcess> listWorkerProcesses() {
        return toWorkerProcesses(listEntries("wp", "WP"));
    }

    private static List<IisWorkerProcess> toWorkerProcesses(List<Map<String, String>> entries) {
        List<IisWorkerProcess> processes = new ArrayList<>();
        for (Map<String, String> entry : entries) {
            long pid = parseLong(entry.get("WP.NAME"), 0);
            if (pid > 0) {
                processes.add(new IisWorkerProcess(pid, entry.getOrDefault("APPPOOL.NAME", "")));
            }
        }
        return processes;
    }

    public static long findWorkerProcessPid(String appPoolName) {
        if (appPoolName == null || appPoolName.isBlank()) {
            return 0;
        }
        for (IisWorkerProcess process : listWorkerProcesses()) {
            if (appPoolName.equals(process.appPoolName()) && process.pid() > 0) {
                return process.pid();
            }
        }
        return 0;
    }

    static final List<String> LIST_APP_POOLS = List.of("list", "apppool", "/xml");
    static final List<String> LIST_APP_POOL_CONFIG = List.of("list", "apppool", "/config");
    static final List<String> LIST_SITES = List.of("list", "site", "/xml");
    static final List<String> LIST_APPS = List.of("list", "app", "/xml");
    static final List<String> LIST_VDIRS = List.of("list", "vdir", "/xml");
    static final List<String> LIST_WORKER_PROCESSES = List.of("list", "wp", "/xml");

    public static IisService.Snapshot snapshot() {
        List<IisProcess.Result> results = readAll(List.of(
                LIST_APP_POOLS, LIST_APP_POOL_CONFIG, LIST_SITES, LIST_APPS, LIST_VDIRS, LIST_WORKER_PROCESSES));
        if (results.size() < 6) {
            return IisService.Snapshot.empty();
        }
        Map<String, Map<String, String>> configs = parseAppPoolConfigs(outputOf(results.get(1)));
        List<IisAppPool> pools = new ArrayList<>();
        for (Map<String, String> entry : parseEntries(outputOf(results.getFirst()), "APPPOOL")) {
            String name = entry.get("APPPOOL.NAME");
            if (name != null && !name.isBlank()) {
                pools.add(readAppPool(name, entry, configs.getOrDefault(name, Map.of())));
            }
        }
        List<IisVirtualDirectory> directories = toVirtualDirectories(
                parseEntries(outputOf(results.get(4)), "VDIR"));
        List<IisApplication> applications = toApplications(
                parseEntries(outputOf(results.get(3)), "APP"), directories);
        List<IisSite> sites = toSites(parseEntries(outputOf(results.get(2)), "SITE"),
                applications, directories);
        List<IisWorkerProcess> workers = toWorkerProcesses(
                parseEntries(outputOf(results.get(5)), "WP"));
        return new IisService.Snapshot(pools, sites, applications, workers);
    }

    private static String outputOf(IisProcess.Result result) {
        return result != null && result.ok() ? result.output() : "";
    }

    private static List<Map<String, String>> listEntries(String object, String tag) {
        IisProcess.Result result = read(List.of("list", object, "/xml"));
        if (!result.ok()) {
            log.debug("appcmd list {} falhou: {}", object, result.output());
            return List.of();
        }
        return parseEntries(result.output(), tag);
    }

    private static List<Map<String, String>> parseEntries(String xml, String tag) {
        Element root = parse(xml);
        if (root == null) {
            return List.of();
        }
        List<Map<String, String>> entries = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                Map<String, String> values = new LinkedHashMap<>();
                NamedNodeMap attributes = element.getAttributes();
                for (int j = 0; j < attributes.getLength(); j++) {
                    Node attribute = attributes.item(j);
                    values.put(attribute.getNodeName(), attribute.getNodeValue());
                }
                entries.add(values);
            }
        }
        return entries;
    }

    static IisService.LogSettings parseLogSettings(String xml) {
        Element root = parse(xml);
        Element logFile = root == null ? null : firstElement(root, "logFile");
        if (logFile == null) {
            return IisService.LogSettings.defaults();
        }
        String enabled = logFile.getAttribute("enabled");
        String directory = logFile.getAttribute("directory");
        String format = logFile.getAttribute("logFormat");
        String period = logFile.getAttribute("period");
        IisService.LogSettings defaults = IisService.LogSettings.defaults();
        return new IisService.LogSettings(
                enabled == null || enabled.isBlank() || Boolean.parseBoolean(enabled),
                directory == null || directory.isBlank() ? defaults.directory() : directory,
                format == null || format.isBlank() ? defaults.format() : format,
                period == null || period.isBlank() ? defaults.period() : period);
    }

    static IisService.SiteLimits parseLimits(String xml) {
        Element root = parse(xml);
        Element limits = root == null ? null : firstElement(root, "limits");
        IisService.SiteLimits defaults = IisService.SiteLimits.defaults();
        if (limits == null) {
            return defaults;
        }
        return new IisService.SiteLimits(
                valueOr(limits.getAttribute("connectionTimeout"), defaults.connectionTimeout()),
                valueOr(limits.getAttribute("maxBandwidth"), defaults.maxBandwidth()),
                valueOr(limits.getAttribute("maxConnections"), defaults.maxConnections()));
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    static Element parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        String trimmed = xml.strip();
        int start = trimmed.indexOf('<');
        if (start < 0) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(trimmed.substring(start))));
            return document.getDocumentElement();
        } catch (Exception e) {
            log.debug("Falha ao interpretar saída XML do appcmd: {}", e.getMessage());
            return null;
        }
    }

    static Element firstElement(Element root, String tag) {
        if (root == null) {
            return null;
        }
        if (tag.equals(root.getTagName())) {
            return root;
        }
        NodeList nodes = root.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static long parseTimeSpanMinutes(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String text = value.strip();
        long days = 0;
        int dot = text.indexOf('.');
        if (dot > 0 && text.indexOf(':') > dot) {
            days = parseLong(text.substring(0, dot), 0);
            text = text.substring(dot + 1);
        }
        String[] parts = text.split(":");
        if (parts.length < 2) {
            return fallback;
        }
        long hours = parseLong(parts[0], 0);
        long minutes = parseLong(parts[1], 0);
        return days * 24 * 60 + hours * 60 + minutes;
    }

    static String toTimeSpan(long minutes) {
        long safe = Math.max(0, minutes);
        long days = safe / (24 * 60);
        long hours = (safe % (24 * 60)) / 60;
        long remaining = safe % 60;
        String core = String.format("%02d:%02d:00", hours, remaining);
        return days > 0 ? days + "." + core : core;
    }
}
