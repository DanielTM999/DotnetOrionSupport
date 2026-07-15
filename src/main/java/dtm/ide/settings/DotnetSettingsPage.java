package dtm.ide.settings;

import dtm.ide.api.extension.settings.PluginSettingsPage;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

public final class DotnetSettingsPage implements PluginSettingsPage {

    private final DotnetPluginSettings settings;
    private final Runnable onTreeLayoutChanged;

    private final JCheckBox formatOnSave = new JCheckBox(text("checkbox.formatOnSave", "Format on save (OmniSharp)"));
    private final JCheckBox onTypeFormatting = new JCheckBox(text("checkbox.onTypeFormatting", "Format while typing (; } and new line)"));
    private final JCheckBox includePrerelease = new JCheckBox(text("checkbox.includePrerelease", "Include prerelease versions in NuGet by default"));
    private final JCheckBox ghostText = new JCheckBox(text("checkbox.ghostText", "Inline suggestions (ghost text) while typing"));
    private final JCheckBox breakOnAllExceptions = new JCheckBox(text("checkbox.breakOnAllExceptions", "Break on all thrown exceptions (debug)"));
    private final JComboBox<String> defaultConfiguration = new JComboBox<>(new String[]{"Debug", "Release"});
    private final JComboBox<TreeLayoutOption> treeLayout = new JComboBox<>(TreeLayoutOption.values());

    private JComponent view;

    private static String text(String key, String def) {
        return I18n.getText(DotnetSettingsPage.class, key, def);
    }

    public DotnetSettingsPage(DotnetPluginSettings settings) {
        this(settings, null);
    }

    public DotnetSettingsPage(DotnetPluginSettings settings, Runnable onTreeLayoutChanged) {
        this.settings = settings;
        this.onTreeLayoutChanged = onTreeLayoutChanged;
    }

    @Override
    public String getTitle() {
        return ".NET / C#";
    }

    @Override
    public JComponent getView() {
        if (view == null) {
            view = buildView();
        }
        syncFromSettings();
        return view;
    }

    private JComponent buildView() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;

        gbc.insets = new Insets(0, 0, 4, 0);
        panel.add(sectionLabel(text("section.editor", "Editor")), gbc);

        gbc.insets = new Insets(0, 0, 6, 0);
        nextRow(gbc);
        panel.add(formatOnSave, gbc);
        nextRow(gbc);
        panel.add(onTypeFormatting, gbc);
        nextRow(gbc);
        panel.add(ghostText, gbc);

        gbc.insets = new Insets(16, 0, 4, 0);
        nextRow(gbc);
        panel.add(sectionLabel(text("section.nuget", "NuGet")), gbc);

        gbc.insets = new Insets(0, 0, 6, 0);
        nextRow(gbc);
        panel.add(includePrerelease, gbc);

        gbc.insets = new Insets(16, 0, 4, 0);
        nextRow(gbc);
        panel.add(sectionLabel(text("section.debug", "Debugging")), gbc);

        gbc.insets = new Insets(0, 0, 6, 0);
        nextRow(gbc);
        panel.add(breakOnAllExceptions, gbc);

        gbc.insets = new Insets(16, 0, 4, 0);
        nextRow(gbc);
        panel.add(sectionLabel(text("section.project", "Project")), gbc);

        addLabeledRow(panel, gbc, text("label.defaultConfiguration", "Default build configuration:"), defaultConfiguration);
        addLabeledRow(panel, gbc, text("label.treeLayout", "Project tree layout:"), treeLayout);

        nextRow(gbc);
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(Box.createGlue(), gbc);

        return panel;
    }

    private void addLabeledRow(JPanel panel, GridBagConstraints gbc, String label, Component field) {
        nextRow(gbc);
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 8, 12);
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.insets = new Insets(0, 0, 8, 0);
        panel.add(field, gbc);

        gbc.gridx = 0;
        gbc.gridwidth = 2;
    }

    private void nextRow(GridBagConstraints gbc) {
        gbc.gridx = 0;
        gbc.gridy++;
    }

    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private void syncFromSettings() {
        if (settings == null) {
            return;
        }
        formatOnSave.setSelected(settings.isFormatOnSave());
        onTypeFormatting.setSelected(settings.isOnTypeFormatting());
        includePrerelease.setSelected(settings.isIncludePrerelease());
        ghostText.setSelected(settings.isGhostTextEnabled());
        breakOnAllExceptions.setSelected(settings.isBreakOnAllExceptions());
        defaultConfiguration.setSelectedItem(settings.getDefaultConfiguration());
        treeLayout.setSelectedItem(TreeLayoutOption.of(settings.getTreeLayout()));
    }

    @Override
    public void onApply() {
        if (settings == null) {
            return;
        }
        settings.setFormatOnSave(formatOnSave.isSelected());
        settings.setOnTypeFormatting(onTypeFormatting.isSelected());
        settings.setIncludePrerelease(includePrerelease.isSelected());
        settings.setGhostTextEnabled(ghostText.isSelected());
        settings.setBreakOnAllExceptions(breakOnAllExceptions.isSelected());
        settings.setDefaultConfiguration(String.valueOf(defaultConfiguration.getSelectedItem()));

        TreeLayout previousLayout = settings.getTreeLayout();
        TreeLayout selectedLayout = selectedTreeLayout();
        settings.setTreeLayout(selectedLayout);
        settings.save();

        if (selectedLayout != previousLayout && onTreeLayoutChanged != null) {
            onTreeLayoutChanged.run();
        }
    }

    @Override
    public void onRestoreDefaults() {
        if (settings == null) {
            return;
        }
        TreeLayout previousLayout = settings.getTreeLayout();
        settings.restoreDefaults();
        settings.save();
        syncFromSettings();
        if (previousLayout != settings.getTreeLayout() && onTreeLayoutChanged != null) {
            onTreeLayoutChanged.run();
        }
    }

    private TreeLayout selectedTreeLayout() {
        Object selected = treeLayout.getSelectedItem();
        return selected instanceof TreeLayoutOption option ? option.layout() : TreeLayout.DEFAULT;
    }

    private enum TreeLayoutOption {
        DEFAULT(TreeLayout.DEFAULT, "treeLayout.default", "Default (Orion)"),
        VISUAL_STUDIO(TreeLayout.VISUAL_STUDIO, "treeLayout.visualStudio", "Visual Studio (Solution / Projects)");

        private final TreeLayout layout;
        private final String key;
        private final String label;

        TreeLayoutOption(TreeLayout layout, String key, String label) {
            this.layout = layout;
            this.key = key;
            this.label = label;
        }

        TreeLayout layout() {
            return layout;
        }

        static TreeLayoutOption of(TreeLayout layout) {
            for (TreeLayoutOption option : values()) {
                if (option.layout == layout) {
                    return option;
                }
            }
            return DEFAULT;
        }

        @Override
        public String toString() {
            return text(key, label);
        }
    }
}
