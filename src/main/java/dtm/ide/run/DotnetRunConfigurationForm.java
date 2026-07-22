package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunConfigurationForm;
import dtm.ide.iis.IisService;
import dtm.ide.iis.IisWebProject;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class DotnetRunConfigurationForm implements RunConfigurationForm {

    private static String text(String key, String def) {
        return I18n.getText(DotnetRunConfigurationForm.class, key, def);
    }

    private static final String DEFAULT_PROFILE = text("profile.default", "(default)");
    private static final String DEFAULT_TFM = text("tfm.automatic", "(automatic)");

    private final JPanel panel = new JPanel();
    private final JComboBox<Path> projectCombo = new JComboBox<>();
    private final JComboBox<String> configCombo = new JComboBox<>(new String[]{"Debug", "Release"});
    private final JComboBox<String> frameworkCombo = new JComboBox<>();
    private final JComboBox<String> profileCombo = new JComboBox<>();
    private final JTextField argumentsField = new JTextField();
    private final JTextField workingDirectoryField = new JTextField();
    private final JTextArea environmentArea = new JTextArea(4, 36);
    private final JTextField siteNameField = new JTextField();
    private final JTextField applicationPathField = new JTextField();
    private final JTextField applicationPoolField = new JTextField();
    private final JTextField launchUrlField = new JTextField();
    private final JCheckBox launchBrowserBox = new JCheckBox(text("label.launchBrowser", "Open browser on start"));
    private final String type;

    private RunConfigurationData current;

    public DotnetRunConfigurationForm(Supplier<Path> projectRootSupplier) {
        this(projectRootSupplier, DotnetRunSupport.TYPE_RUN);
    }

    public DotnetRunConfigurationForm(Supplier<Path> projectRootSupplier, String type) {
        this.type = type == null ? DotnetRunSupport.TYPE_RUN : type;
        Path root = projectRootSupplier == null ? null : projectRootSupplier.get();
        List<Path> projects = resolveProjects(root);
        if (projects.isEmpty()) {
            projects = TargetFramework.findProjectFiles(root);
        }
        for (Path project : projects) {
            projectCombo.addItem(project);
        }
        projectCombo.setRenderer(new ProjectRenderer());
        projectCombo.addActionListener(e -> reloadProjectOptions());
        reloadFrameworks();
        reloadProfiles();
        build();
    }

    private List<Path> resolveProjects(Path root) {
        if (isIisType()) {
            List<Path> webProjects = new ArrayList<>();
            for (Path candidate : TargetFramework.findProjectFiles(root)) {
                if (IisWebProject.isWebProject(candidate)) {
                    webProjects.add(candidate);
                }
            }
            return webProjects;
        }
        if (DotnetRunSupport.TYPE_RUN.equals(type)) {
            return TargetFramework.findRunnableProjectFiles(root);
        }
        return TargetFramework.findProjectFiles(root);
    }

    private boolean isIisType() {
        return DotnetRunSupport.TYPE_IIS_EXPRESS.equals(type) || DotnetRunSupport.TYPE_IIS.equals(type);
    }

    private boolean isFullIisType() {
        return DotnetRunSupport.TYPE_IIS.equals(type);
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public RunConfigurationData getData() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Path project = (Path) projectCombo.getSelectedItem();
        if (project != null) {
            properties.put(DotnetRunSupport.PROP_PROJECT, project.toString());
        }
        properties.put(DotnetRunSupport.PROP_CONFIGURATION, String.valueOf(configCombo.getSelectedItem()));
        Object framework = frameworkCombo.getSelectedItem();
        if (framework != null && !DEFAULT_TFM.equals(framework)) {
            properties.put(DotnetRunSupport.PROP_TARGET_FRAMEWORK, framework.toString());
        }
        Object profile = profileCombo.getSelectedItem();
        if (profile != null && !DEFAULT_PROFILE.equals(profile)) {
            properties.put(DotnetRunSupport.PROP_LAUNCH_PROFILE, profile.toString());
        }
        putIfNotBlank(properties, DotnetRunSupport.PROP_PROGRAM_ARGS, argumentsField.getText());
        putIfNotBlank(properties, DotnetRunSupport.PROP_WORKING_DIRECTORY, workingDirectoryField.getText());
        putIfNotBlank(properties, DotnetRunSupport.PROP_ENVIRONMENT, environmentArea.getText());
        if (isIisType()) {
            putIfNotBlank(properties, DotnetRunSupport.PROP_IIS_SITE, siteNameField.getText());
            putIfNotBlank(properties, DotnetRunSupport.PROP_IIS_APP_PATH, applicationPathField.getText());
            putIfNotBlank(properties, DotnetRunSupport.PROP_IIS_APP_POOL, applicationPoolField.getText());
            putIfNotBlank(properties, DotnetRunSupport.PROP_IIS_LAUNCH_URL, launchUrlField.getText());
            properties.put(DotnetRunSupport.PROP_IIS_LAUNCH_BROWSER,
                    Boolean.toString(launchBrowserBox.isSelected()));
        }
        RunConfigurationData data = current != null ? current : new RunConfigurationData();
        data.setType(type);
        data.setProperties(properties);
        data.setTitle(null);
        return data;
    }

    @Override
    public void setData(RunConfigurationData data) {
        if (data == null) {
            return;
        }
        current = data;
        if (data.getProperties() == null) {
            return;
        }
        Map<String, Object> properties = data.getProperties();
        Object project = properties.get(DotnetRunSupport.PROP_PROJECT);
        if (project != null) {
            selectProject(project.toString());
        }
        Object configuration = properties.get(DotnetRunSupport.PROP_CONFIGURATION);
        if (configuration != null) {
            configCombo.setSelectedItem(configuration.toString());
        }
        reloadFrameworks();
        Object framework = properties.get(DotnetRunSupport.PROP_TARGET_FRAMEWORK);
        frameworkCombo.setSelectedItem(framework == null ? DEFAULT_TFM : framework.toString());
        reloadProfiles();
        Object profile = properties.get(DotnetRunSupport.PROP_LAUNCH_PROFILE);
        profileCombo.setSelectedItem(profile == null ? DEFAULT_PROFILE : profile.toString());
        argumentsField.setText(textProperty(properties, DotnetRunSupport.PROP_PROGRAM_ARGS));
        workingDirectoryField.setText(textProperty(properties, DotnetRunSupport.PROP_WORKING_DIRECTORY));
        environmentArea.setText(textProperty(properties, DotnetRunSupport.PROP_ENVIRONMENT));
        if (isIisType()) {
            siteNameField.setText(textProperty(properties, DotnetRunSupport.PROP_IIS_SITE));
            applicationPathField.setText(textProperty(properties, DotnetRunSupport.PROP_IIS_APP_PATH));
            applicationPoolField.setText(textProperty(properties, DotnetRunSupport.PROP_IIS_APP_POOL));
            launchUrlField.setText(textProperty(properties, DotnetRunSupport.PROP_IIS_LAUNCH_URL));
            launchBrowserBox.setSelected(Boolean.parseBoolean(
                    textProperty(properties, DotnetRunSupport.PROP_IIS_LAUNCH_BROWSER)));
            applyIisDefaults();
        }
    }

    private void applyIisDefaults() {
        Path project = (Path) projectCombo.getSelectedItem();
        if (project == null) {
            return;
        }
        String projectName = IisWebProject.projectName(project);
        if (siteNameField.getText().isBlank()) {
            siteNameField.setText(isFullIisType()
                    ? "Default Web Site" : IisService.suggestSiteName(projectName));
        }
        if (applicationPoolField.getText().isBlank()) {
            applicationPoolField.setText(IisService.suggestAppPoolName(projectName));
        }
        if (isFullIisType() && applicationPathField.getText().isBlank()) {
            applicationPathField.setText("/" + projectName);
        }
    }

    private void build() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(labeled(text("label.project", "Executable project"), projectCombo));
        panel.add(Box.createVerticalStrut(8));
        panel.add(labeled(text("label.configuration", "Configuration"), configCombo));
        panel.add(Box.createVerticalStrut(8));
        panel.add(labeled(text("label.framework", "Target framework"), frameworkCombo));
        if (isIisType()) {
            panel.add(Box.createVerticalStrut(8));
            panel.add(labeled(text("label.launchProfile", "Launch profile"), profileCombo));
            panel.add(Box.createVerticalStrut(8));
            panel.add(labeled(text("label.iisSite", "IIS site"), siteNameField));
            if (isFullIisType()) {
                panel.add(Box.createVerticalStrut(8));
                panel.add(labeled(text("label.iisAppPath", "Application path"), applicationPathField));
            }
            panel.add(Box.createVerticalStrut(8));
            panel.add(labeled(text("label.iisAppPool", "Application pool"), applicationPoolField));
            panel.add(Box.createVerticalStrut(8));
            panel.add(labeled(text("label.iisLaunchUrl", "Launch URL (relative or absolute)"), launchUrlField));
            panel.add(Box.createVerticalStrut(8));
            launchBrowserBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(launchBrowserBox);
            panel.add(Box.createVerticalStrut(8));
            environmentArea.setLineWrap(false);
            panel.add(labeledArea(text("label.environment", "Environment variables (one NAME=VALUE per line)"),
                    environmentArea));
            applyIisDefaults();
            return;
        }
        if (DotnetRunSupport.TYPE_RUN.equals(type)) {
            panel.add(Box.createVerticalStrut(8));
            panel.add(labeled(text("label.launchProfile", "Launch profile"), profileCombo));
            panel.add(Box.createVerticalStrut(8));
            panel.add(labeled(text("label.programArgs", "Program arguments"), argumentsField));
            panel.add(Box.createVerticalStrut(8));
            panel.add(labeled(text("label.workingDir", "Working directory"), workingDirectoryField));
            panel.add(Box.createVerticalStrut(8));
            environmentArea.setLineWrap(false);
            panel.add(labeledArea(text("label.environment", "Environment variables (one NAME=VALUE per line)"), environmentArea));
        }
    }

    private JPanel labeled(String label, JComponent field) {
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        field.setPreferredSize(new Dimension(420, 30));
        JPanel row = new JPanel(new BorderLayout(0, 4));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        row.add(new JLabel(label), BorderLayout.NORTH);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private JPanel labeledArea(String label, JTextArea field) {
        JScrollPane scroll = new JScrollPane(field);
        scroll.setPreferredSize(new Dimension(420, 90));
        JPanel row = new JPanel(new BorderLayout(0, 4));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        row.add(new JLabel(label), BorderLayout.NORTH);
        row.add(scroll, BorderLayout.CENTER);
        return row;
    }

    private void reloadProfiles() {
        Object previous = profileCombo.getSelectedItem();
        profileCombo.removeAllItems();
        profileCombo.addItem(DEFAULT_PROFILE);
        Path project = (Path) projectCombo.getSelectedItem();
        if (project != null) {
            List<LaunchSettings.Profile> profiles = isIisType()
                    ? LaunchSettings.iisProfiles(project)
                    : LaunchSettings.runnableProfiles(project);
            for (LaunchSettings.Profile profile : profiles) {
                profileCombo.addItem(profile.name());
            }
        }
        if (previous != null) {
            profileCombo.setSelectedItem(previous);
        }
    }

    private void reloadProjectOptions() {
        reloadFrameworks();
        reloadProfiles();
    }

    private void reloadFrameworks() {
        Object previous = frameworkCombo.getSelectedItem();
        frameworkCombo.removeAllItems();
        frameworkCombo.addItem(DEFAULT_TFM);
        Path project = (Path) projectCombo.getSelectedItem();
        if (project != null) {
            for (String tfm : TargetFramework.readTfms(project)) {
                if (!DotnetRunSupport.TYPE_RUN.equals(type)
                        || TargetFramework.selectRunnableTfm(List.of(tfm), TargetFramework.isWindows()).isPresent()) {
                    frameworkCombo.addItem(tfm);
                }
            }
        }
        if (previous != null) {
            frameworkCombo.setSelectedItem(previous);
        }
    }

    private void selectProject(String pathText) {
        try {
            Path target = Path.of(pathText).toAbsolutePath().normalize();
            for (int i = 0; i < projectCombo.getItemCount(); i++) {
                Path item = projectCombo.getItemAt(i);
                if (item != null && item.toAbsolutePath().normalize().equals(target)) {
                    projectCombo.setSelectedIndex(i);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void putIfNotBlank(Map<String, Object> properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.put(key, value.trim());
        }
    }

    private static String textProperty(Map<String, Object> properties, String key) {
        Object value = properties.get(key);
        return value == null ? "" : value.toString();
    }

    private static String projectName(Path file) {
        if (file == null || file.getFileName() == null) {
            return text("projectFallback", "project");
        }
        return file.getFileName().toString().replaceFirst("(?i)\\.(csproj|vbproj|fsproj)$", "");
    }

    private static final class ProjectRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Path path) {
                setText(projectName(path));
            }
            return this;
        }
    }
}
