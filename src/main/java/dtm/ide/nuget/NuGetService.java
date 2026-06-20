package dtm.ide.nuget;

import dtm.ide.nuget.models.InstalledPackage;
import dtm.ide.run.TargetFramework;
import dtm.ide.sdk.DotnetSdkService;
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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
public final class NuGetService {

    private static final Pattern PACKAGE_REFERENCE_ATTR = Pattern.compile(
            "<PackageReference\\s+Include=\"([^\"]+)\"[^>]*?Version=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKAGE_REFERENCE_CHILD = Pattern.compile(
            "<PackageReference\\s+Include=\"([^\"]+)\"[^>]*?>\\s*<Version>\\s*([^<]+)</Version>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PACKAGES_CONFIG_ENTRY = Pattern.compile(
            "<package\\s+id=\"([^\"]+)\"\\s+version=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final Path projectRoot;
    private final Path projectFile;
    private final DotnetSdkService sdkService;

    public NuGetService(Path projectRoot, DotnetSdkService sdkService) {
        this(projectRoot, null, sdkService);
    }

    public NuGetService(Path projectRoot, Path projectFile, DotnetSdkService sdkService) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        this.projectFile = projectFile == null ? null : projectFile.toAbsolutePath().normalize();
        this.sdkService = sdkService;
    }

    private Path targetProjectFile() {
        return projectFile != null ? projectFile : TargetFramework.findPrimaryProjectFile(projectRoot);
    }

    private Path projectDir() {
        Path file = targetProjectFile();
        if (file != null && file.getParent() != null) {
            return file.getParent();
        }
        return projectRoot;
    }

    public record OperationResult(boolean success, String message) {
        static OperationResult ok(String message) {
            return new OperationResult(true, message);
        }

        static OperationResult fail(String message) {
            return new OperationResult(false, message);
        }
    }

    public List<InstalledPackage> listInstalled() {
        List<InstalledPackage> out = new ArrayList<>();
        Path csproj = targetProjectFile();
        if (csproj != null && Files.isRegularFile(csproj)) {
            out.addAll(readPackageReferences(csproj));
        }
        Path packagesConfig = packagesConfigFile();
        if (packagesConfig != null && Files.isRegularFile(packagesConfig)) {
            out.addAll(readPackagesConfig(packagesConfig, csproj));
        }
        return dedupe(out);
    }

    public boolean isLegacyProject() {
        Path packagesConfig = packagesConfigFile();
        if (packagesConfig != null && Files.isRegularFile(packagesConfig)) {
            return true;
        }
        Path csproj = targetProjectFile();
        if (csproj == null || !Files.isRegularFile(csproj)) {
            return false;
        }
        try {
            String content = Files.readString(csproj);
            String header = content.substring(0, Math.min(content.length(), 600)).toLowerCase(Locale.ROOT);

            return !header.contains("sdk=");
        } catch (Exception e) {
            return false;
        }
    }

    public OperationResult install(String id, String version) {
        if (id == null || id.isBlank()) {
            return OperationResult.fail("id do pacote obrigatório.");
        }
        if (isLegacyProject()) {
            return installLegacy(id, version);
        }
        return installSdkStyle(id, version);
    }

    public OperationResult uninstall(String id) {
        if (id == null || id.isBlank()) {
            return OperationResult.fail("id do pacote obrigatório.");
        }
        if (isLegacyProject()) {
            return uninstallLegacy(id);
        }
        return uninstallSdkStyle(id);
    }

    public OperationResult update(String id, String version) {
        return install(id, version);
    }

    private OperationResult installSdkStyle(String id, String version) {
        Path csproj = targetProjectFile();
        Optional<Path> dotnet = dotnetPath();
        if (dotnet.isEmpty()) {
            return OperationResult.fail("dotnet não encontrado para instalar via PackageReference.");
        }
        List<String> command = new ArrayList<>();
        command.add(dotnet.get().toString());
        command.add("add");
        if (csproj != null) {
            command.add(csproj.toString());
        }
        command.add("package");
        command.add(id);
        if (version != null && !version.isBlank()) {
            command.add("--version");
            command.add(version);
        }
        return runDotnet(command, "Pacote " + id + (version == null ? "" : " " + version) + " adicionado.");
    }

    private OperationResult uninstallSdkStyle(String id) {
        Path csproj = targetProjectFile();
        Optional<Path> dotnet = dotnetPath();
        if (dotnet.isEmpty()) {
            return OperationResult.fail("dotnet não encontrado para remover o pacote.");
        }
        List<String> command = new ArrayList<>();
        command.add(dotnet.get().toString());
        command.add("remove");
        if (csproj != null) {
            command.add(csproj.toString());
        }
        command.add("package");
        command.add(id);
        return runDotnet(command, "Pacote " + id + " removido.");
    }

    private OperationResult installLegacy(String id, String version) {
        try {
            String resolvedVersion = version;
            NuGetClient client = NuGetClient.forSource(NuGetSource.nugetOrg());
            if (resolvedVersion == null || resolvedVersion.isBlank()) {
                resolvedVersion = client.latestVersion(id, false);
            }
            if (resolvedVersion == null) {
                return OperationResult.fail("Não foi possível resolver a versão de " + id + ".");
            }

            Path packagesDir = solutionPackagesDir();
            Path nupkg = client.download(id, resolvedVersion, packagesDir);
            Path extractDir = packagesDir.resolve(id + "." + resolvedVersion);
            extractZip(nupkg, extractDir);
            Files.deleteIfExists(nupkg);

            upsertPackagesConfigEntry(id, resolvedVersion);
            addLegacyReferenceBestEffort(id, resolvedVersion, extractDir);
            return OperationResult.ok("Pacote " + id + " " + resolvedVersion
                    + " instalado (packages.config). Confira a referência no .csproj.");
        } catch (Exception e) {
            return OperationResult.fail("Falha ao instalar (legado) " + id + ": " + e.getMessage());
        }
    }

    private OperationResult uninstallLegacy(String id) {
        try {
            removePackagesConfigEntry(id);
            return OperationResult.ok("Pacote " + id + " removido do packages.config. "
                    + "Remova a <Reference> correspondente do .csproj, se necessário.");
        } catch (Exception e) {
            return OperationResult.fail("Falha ao remover (legado) " + id + ": " + e.getMessage());
        }
    }

    private void upsertPackagesConfigEntry(String id, String version) throws Exception {
        Path file = packagesConfigFileForWrite();
        Document doc;
        Element root;
        if (Files.isRegularFile(file)) {
            doc = parse(file);
            root = doc.getDocumentElement();
        } else {
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            root = doc.createElement("packages");
            doc.appendChild(root);
        }
        NodeList packages = root.getElementsByTagName("package");
        for (int i = 0; i < packages.getLength(); i++) {
            Element pkg = (Element) packages.item(i);
            if (id.equalsIgnoreCase(pkg.getAttribute("id"))) {
                pkg.setAttribute("version", version);
                write(doc, file);
                return;
            }
        }
        Element pkg = doc.createElement("package");
        pkg.setAttribute("id", id);
        pkg.setAttribute("version", version);
        String tfm = legacyTargetFrameworkAttr();
        if (tfm != null) {
            pkg.setAttribute("targetFramework", tfm);
        }
        root.appendChild(pkg);
        write(doc, file);
    }

    private void removePackagesConfigEntry(String id) throws Exception {
        Path file = packagesConfigFile();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Document doc = parse(file);
        Element root = doc.getDocumentElement();
        NodeList packages = root.getElementsByTagName("package");
        for (int i = packages.getLength() - 1; i >= 0; i--) {
            Element pkg = (Element) packages.item(i);
            if (id.equalsIgnoreCase(pkg.getAttribute("id"))) {
                root.removeChild(pkg);
            }
        }
        write(doc, file);
    }

    private void addLegacyReferenceBestEffort(String id, String version, Path extractDir) {
        try {
            Path libDir = extractDir.resolve("lib");
            if (!Files.isDirectory(libDir)) {
                return;
            }
            Optional<Path> dll = pickBestLibDll(libDir);
            if (dll.isEmpty()) {
                return;
            }
            Path csproj = targetProjectFile();
            if (csproj == null) {
                return;
            }
            Document doc = parse(csproj);
            Element project = doc.getDocumentElement();
            String assemblyName = dll.get().getFileName().toString().replaceFirst("(?i)\\.dll$", "");

            NodeList references = project.getElementsByTagName("Reference");
            for (int i = 0; i < references.getLength(); i++) {
                Element ref = (Element) references.item(i);
                if (assemblyName.equalsIgnoreCase(stripAssemblyName(ref.getAttribute("Include")))) {
                    return;
                }
            }
            Element itemGroup = doc.createElement("ItemGroup");
            Element reference = doc.createElement("Reference");
            reference.setAttribute("Include", assemblyName);
            Element hintPath = doc.createElement("HintPath");
            Path relative = csproj.getParent().relativize(dll.get());
            hintPath.setTextContent(relative.toString().replace('/', '\\'));
            reference.appendChild(hintPath);
            itemGroup.appendChild(reference);
            project.appendChild(itemGroup);
            write(doc, csproj);
        } catch (Exception e) {
            log.debug("Não foi possível inserir <Reference> legado para {}: {}", id, e.getMessage());
        }
    }

    private static Optional<Path> pickBestLibDll(Path libDir) throws Exception {

        try (Stream<Path> tfmDirs = Files.list(libDir)) {
            List<Path> dirs = tfmDirs.filter(Files::isDirectory)
                    .sorted((a, b) -> b.getFileName().toString().compareToIgnoreCase(a.getFileName().toString()))
                    .toList();
            for (Path tfmDir : dirs) {
                Optional<Path> dll = firstDll(tfmDir);
                if (dll.isPresent()) {
                    return dll;
                }
            }
        }
        return firstDll(libDir);
    }

    private static Optional<Path> firstDll(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dll"))
                    .findFirst();
        }
    }

    private List<InstalledPackage> readPackageReferences(Path csproj) {
        List<InstalledPackage> out = new ArrayList<>();
        try {
            String content = Files.readString(csproj);
            Matcher attr = PACKAGE_REFERENCE_ATTR.matcher(content);
            while (attr.find()) {
                out.add(new InstalledPackage(attr.group(1), attr.group(2),
                        InstalledPackage.Mode.PACKAGE_REFERENCE, csproj));
            }
            Matcher child = PACKAGE_REFERENCE_CHILD.matcher(content);
            while (child.find()) {
                out.add(new InstalledPackage(child.group(1), child.group(2).trim(),
                        InstalledPackage.Mode.PACKAGE_REFERENCE, csproj));
            }
        } catch (Exception e) {
            log.debug("Falha ao ler PackageReference de {}: {}", csproj, e.getMessage());
        }
        return out;
    }

    private List<InstalledPackage> readPackagesConfig(Path packagesConfig, Path csproj) {
        List<InstalledPackage> out = new ArrayList<>();
        try {
            String content = Files.readString(packagesConfig);
            Matcher entry = PACKAGES_CONFIG_ENTRY.matcher(content);
            while (entry.find()) {
                out.add(new InstalledPackage(entry.group(1), entry.group(2),
                        InstalledPackage.Mode.PACKAGES_CONFIG, csproj));
            }
        } catch (Exception e) {
            log.debug("Falha ao ler packages.config {}: {}", packagesConfig, e.getMessage());
        }
        return out;
    }

    private List<InstalledPackage> dedupe(List<InstalledPackage> packages) {
        Map<String, InstalledPackage> byId = new LinkedHashMap<>();
        for (InstalledPackage pkg : packages) {
            byId.putIfAbsent(pkg.id().toLowerCase(Locale.ROOT), pkg);
        }
        return new ArrayList<>(byId.values());
    }

    private Optional<Path> dotnetPath() {
        return sdkService == null ? Optional.empty() : sdkService.getDotnetPath(projectRoot);
    }

    private OperationResult runDotnet(List<String> command, String successMessage) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (projectRoot != null) {
                builder.directory(projectRoot.toFile());
            }
            builder.redirectErrorStream(true);
            builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int code = process.waitFor();
            if (code == 0) {
                return OperationResult.ok(successMessage);
            }
            return OperationResult.fail("dotnet retornou código " + code + ":\n" + output);
        } catch (Exception e) {
            return OperationResult.fail("Falha ao executar dotnet: " + e.getMessage());
        }
    }

    private Path packagesConfigFile() {
        Path dir = projectDir();
        if (dir == null) {
            return null;
        }
        for (String name : List.of("packages.config", "Packages.config")) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Path packagesConfigFileForWrite() {
        Path existing = packagesConfigFile();
        if (existing != null) {
            return existing;
        }
        Path dir = projectDir();
        return dir == null ? null : dir.resolve("packages.config");
    }

    private Path solutionPackagesDir() {
        return projectRoot.resolve("packages");
    }

    private String legacyTargetFrameworkAttr() {

        for (String tfm : TargetFramework.readTfms(targetProjectFile())) {
            String t = tfm.toLowerCase(Locale.ROOT);
            if (t.startsWith("v")) {
                return "net" + t.substring(1).replace(".", "");
            }
            if (TargetFramework.isNetFramework(t)) {
                return t;
            }
        }
        return null;
    }

    private static String stripAssemblyName(String include) {
        if (include == null) {
            return "";
        }
        int comma = include.indexOf(',');
        return comma >= 0 ? include.substring(0, comma).trim() : include.trim();
    }

    private static void extractZip(Path zip, Path targetDir) throws Exception {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget);
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path output = normalizedTarget.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedTarget)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Path parent = output.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zin, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
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
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        try (var out = Files.newOutputStream(file)) {
            transformer.transform(new DOMSource(doc), new StreamResult(out));
        }
    }
}
