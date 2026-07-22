package dtm.ide.iis;

import dtm.ide.run.TargetFramework;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IisWebProject {

    public enum HostingModel {
        IN_PROCESS,
        OUT_OF_PROCESS;

        public static HostingModel fromText(String value) {
            return value != null && value.strip().equalsIgnoreCase("OutOfProcess")
                    ? OUT_OF_PROCESS : IN_PROCESS;
        }

        public String descriptor() {
            return this == OUT_OF_PROCESS ? "OutOfProcess" : "InProcess";
        }
    }

    private static final Pattern PROJECT_SDK = Pattern.compile("<Project[^>]*Sdk\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOSTING_MODEL = Pattern.compile(
            "<AspNetCoreHostingModel>\\s*([^<]+)</AspNetCoreHostingModel>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSEMBLY_NAME = Pattern.compile(
            "<AssemblyName>\\s*([^<]+)</AssemblyName>", Pattern.CASE_INSENSITIVE);

    public static final String PUBLISH_FOLDER = "orion-iis";

    private IisWebProject() {
    }

    public static boolean isWebProject(Path projectFile) {
        if (projectFile == null || !Files.isRegularFile(projectFile)) {
            return false;
        }
        String content = readQuietly(projectFile);
        Matcher sdk = PROJECT_SDK.matcher(content);
        if (sdk.find() && sdk.group(1).toLowerCase(Locale.ROOT).contains(".web")) {
            return true;
        }
        if (HOSTING_MODEL.matcher(content).find()) {
            return true;
        }
        if (content.toLowerCase(Locale.ROOT).contains("microsoft.aspnetcore")) {
            return true;
        }
        Path directory = projectFile.getParent();
        if (directory == null) {
            return false;
        }
        return Files.isRegularFile(directory.resolve("web.config"))
                || Files.isDirectory(directory.resolve("wwwroot"));
    }

    public static boolean isAspNetCore(Path projectFile) {
        if (!isWebProject(projectFile)) {
            return false;
        }
        for (String tfm : TargetFramework.readTfms(projectFile)) {
            if (TargetFramework.isModern(tfm)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isClassicAspNet(Path projectFile) {
        if (!isWebProject(projectFile)) {
            return false;
        }
        List<String> tfms = TargetFramework.readTfms(projectFile);
        if (tfms.isEmpty()) {
            return false;
        }
        for (String tfm : tfms) {
            if (!TargetFramework.isNetFramework(tfm)) {
                return false;
            }
        }
        return true;
    }

    public static HostingModel hostingModel(Path projectFile) {
        Matcher matcher = HOSTING_MODEL.matcher(readQuietly(projectFile));
        return matcher.find() ? HostingModel.fromText(matcher.group(1)) : HostingModel.IN_PROCESS;
    }

    public static String assemblyName(Path projectFile) {
        Matcher matcher = ASSEMBLY_NAME.matcher(readQuietly(projectFile));
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        return projectName(projectFile);
    }

    public static String projectName(Path projectFile) {
        if (projectFile == null || projectFile.getFileName() == null) {
            return "app";
        }
        return projectFile.getFileName().toString().replaceFirst("(?i)\\.(csproj|vbproj|fsproj)$", "");
    }

    public static Path publishDirectory(Path projectFile, String configuration) {
        Path directory = projectFile == null ? null : projectFile.getParent();
        if (directory == null) {
            return null;
        }
        String config = configuration == null || configuration.isBlank() ? "Debug" : configuration;
        return directory.resolve("bin").resolve(PUBLISH_FOLDER).resolve(config).toAbsolutePath().normalize();
    }

    public static Path contentRoot(Path projectFile, String configuration) {
        if (isClassicAspNet(projectFile)) {
            return projectFile.getParent() == null ? null : projectFile.getParent().toAbsolutePath().normalize();
        }
        return publishDirectory(projectFile, configuration);
    }

    public static boolean requiresPublish(Path projectFile) {
        return !isClassicAspNet(projectFile);
    }

    private static String readQuietly(Path projectFile) {
        if (projectFile == null || !Files.isRegularFile(projectFile)) {
            return "";
        }
        try {
            return Files.readString(projectFile);
        } catch (Exception e) {
            return "";
        }
    }
}
