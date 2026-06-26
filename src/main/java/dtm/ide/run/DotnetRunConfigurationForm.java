package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunConfigurationForm;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class DotnetRunConfigurationForm implements RunConfigurationForm {

    private static final String DEFAULT_PROFILE = "(padrão)";

    private final JPanel panel = new JPanel();
    private final JComboBox<Path> projectCombo = new JComboBox<>();
    private final JComboBox<String> configCombo = new JComboBox<>(new String[]{"Debug", "Release"});
    private final JComboBox<String> profileCombo = new JComboBox<>();

    private RunConfigurationData current;

    public DotnetRunConfigurationForm(Supplier<Path> projectRootSupplier) {
        Path root = projectRootSupplier == null ? null : projectRootSupplier.get();
        List<Path> projects = TargetFramework.findRunnableProjectFiles(root);
        if (projects.isEmpty()) {
            projects = TargetFramework.findProjectFiles(root);
        }
        for (Path project : projects) {
            projectCombo.addItem(project);
        }
        projectCombo.setRenderer(new ProjectRenderer());
        projectCombo.addActionListener(e -> reloadProfiles());
        reloadProfiles();
        build();
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
        Object profile = profileCombo.getSelectedItem();
        if (profile != null && !DEFAULT_PROFILE.equals(profile)) {
            properties.put(DotnetRunSupport.PROP_LAUNCH_PROFILE, profile.toString());
        }
        RunConfigurationData data = current != null ? current : new RunConfigurationData();
        data.setType(DotnetRunSupport.TYPE_RUN);
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
        reloadProfiles();
        Object profile = properties.get(DotnetRunSupport.PROP_LAUNCH_PROFILE);
        profileCombo.setSelectedItem(profile == null ? DEFAULT_PROFILE : profile.toString());
    }

    private void build() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(labeled("Projeto executável", projectCombo));
        panel.add(Box.createVerticalStrut(8));
        panel.add(labeled("Configuração", configCombo));
        panel.add(Box.createVerticalStrut(8));
        panel.add(labeled("Perfil de launch", profileCombo));
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

    private void reloadProfiles() {
        Object previous = profileCombo.getSelectedItem();
        profileCombo.removeAllItems();
        profileCombo.addItem(DEFAULT_PROFILE);
        Path project = (Path) projectCombo.getSelectedItem();
        if (project != null) {
            for (LaunchSettings.Profile profile : LaunchSettings.runnableProfiles(project)) {
                profileCombo.addItem(profile.name());
            }
        }
        if (previous != null) {
            profileCombo.setSelectedItem(previous);
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

    private static String projectName(Path file) {
        if (file == null || file.getFileName() == null) {
            return "projeto";
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
