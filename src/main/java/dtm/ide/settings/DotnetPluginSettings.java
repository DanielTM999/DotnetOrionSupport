package dtm.ide.settings;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Slf4j
public final class DotnetPluginSettings {

    private static final String FILE_NAME = "dotnet-settings.properties";

    private static final String KEY_FORMAT_ON_SAVE = "formatOnSave";
    private static final String KEY_ON_TYPE_FORMATTING = "onTypeFormatting";
    private static final String KEY_INCLUDE_PRERELEASE = "includePrerelease";
    private static final String KEY_DEFAULT_CONFIGURATION = "defaultConfiguration";
    private static final String KEY_GHOST_TEXT = "ghostText";
    private static final String KEY_TREE_LAYOUT = "treeLayout";
    private static final String KEY_BREAK_ON_ALL_EXCEPTIONS = "breakOnAllExceptions";

    private final Path settingsFile;

    private boolean formatOnSave = false;
    private boolean onTypeFormatting = false;
    private boolean includePrerelease = false;
    private boolean ghostText = true;
    private String defaultConfiguration = "Debug";
    private TreeLayout treeLayout = TreeLayout.DEFAULT;
    private boolean breakOnAllExceptions = false;

    public DotnetPluginSettings(Path settingsDir) {
        this.settingsFile = settingsDir == null ? null : settingsDir.resolve(FILE_NAME);
        load();
    }

    public boolean isFormatOnSave() {
        return formatOnSave;
    }

    public void setFormatOnSave(boolean formatOnSave) {
        this.formatOnSave = formatOnSave;
    }

    public boolean isOnTypeFormatting() {
        return onTypeFormatting;
    }

    public void setOnTypeFormatting(boolean onTypeFormatting) {
        this.onTypeFormatting = onTypeFormatting;
    }

    public boolean isBreakOnAllExceptions() {
        return breakOnAllExceptions;
    }

    public void setBreakOnAllExceptions(boolean breakOnAllExceptions) {
        this.breakOnAllExceptions = breakOnAllExceptions;
    }

    public boolean isIncludePrerelease() {
        return includePrerelease;
    }

    public void setIncludePrerelease(boolean includePrerelease) {
        this.includePrerelease = includePrerelease;
    }

    public boolean isGhostTextEnabled() {
        return ghostText;
    }

    public void setGhostTextEnabled(boolean ghostText) {
        this.ghostText = ghostText;
    }

    public String getDefaultConfiguration() {
        return defaultConfiguration;
    }

    public void setDefaultConfiguration(String defaultConfiguration) {
        this.defaultConfiguration = defaultConfiguration == null || defaultConfiguration.isBlank()
                ? "Debug" : defaultConfiguration;
    }

    public TreeLayout getTreeLayout() {
        return treeLayout == null ? TreeLayout.DEFAULT : treeLayout;
    }

    public void setTreeLayout(TreeLayout treeLayout) {
        this.treeLayout = treeLayout == null ? TreeLayout.DEFAULT : treeLayout;
    }

    public void restoreDefaults() {
        formatOnSave = false;
        onTypeFormatting = false;
        includePrerelease = false;
        ghostText = true;
        defaultConfiguration = "Debug";
        treeLayout = TreeLayout.DEFAULT;
        breakOnAllExceptions = false;
    }

    public void load() {
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) {
            return;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(settingsFile)) {
            props.load(in);
            formatOnSave = Boolean.parseBoolean(props.getProperty(KEY_FORMAT_ON_SAVE, "false"));
            onTypeFormatting = Boolean.parseBoolean(props.getProperty(KEY_ON_TYPE_FORMATTING, "false"));
            includePrerelease = Boolean.parseBoolean(props.getProperty(KEY_INCLUDE_PRERELEASE, "false"));
            ghostText = Boolean.parseBoolean(props.getProperty(KEY_GHOST_TEXT, "true"));
            defaultConfiguration = props.getProperty(KEY_DEFAULT_CONFIGURATION, "Debug");
            treeLayout = TreeLayout.fromKey(props.getProperty(KEY_TREE_LAYOUT, TreeLayout.DEFAULT.key()));
            breakOnAllExceptions = Boolean.parseBoolean(props.getProperty(KEY_BREAK_ON_ALL_EXCEPTIONS, "false"));
        } catch (Exception e) {
            log.debug("Falha ao carregar settings .NET: {}", e.getMessage());
        }
    }

    public void save() {
        if (settingsFile == null) {
            return;
        }
        Properties props = new Properties();
        props.setProperty(KEY_FORMAT_ON_SAVE, Boolean.toString(formatOnSave));
        props.setProperty(KEY_ON_TYPE_FORMATTING, Boolean.toString(onTypeFormatting));
        props.setProperty(KEY_INCLUDE_PRERELEASE, Boolean.toString(includePrerelease));
        props.setProperty(KEY_GHOST_TEXT, Boolean.toString(ghostText));
        props.setProperty(KEY_DEFAULT_CONFIGURATION, defaultConfiguration);
        props.setProperty(KEY_TREE_LAYOUT, getTreeLayout().key());
        props.setProperty(KEY_BREAK_ON_ALL_EXCEPTIONS, Boolean.toString(breakOnAllExceptions));
        try {
            Path parent = settingsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var out = Files.newOutputStream(settingsFile)) {
                props.store(out, "DotnetOrionSupport settings");
            }
        } catch (Exception e) {
            log.debug("Falha ao salvar settings .NET: {}", e.getMessage());
        }
    }
}
