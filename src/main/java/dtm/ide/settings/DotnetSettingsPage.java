package dtm.ide.settings;

import dtm.ide.api.extension.settings.PluginSettingsPage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.FlowLayout;

public final class DotnetSettingsPage implements PluginSettingsPage {

    private final DotnetPluginSettings settings;

    private final JCheckBox formatOnSave = new JCheckBox("Formatar ao salvar (OmniSharp)");
    private final JCheckBox includePrerelease = new JCheckBox("Incluir versões prerelease no NuGet por padrão");
    private final JComboBox<String> defaultConfiguration = new JComboBox<>(new String[]{"Debug", "Release"});

    private JComponent view;

    public DotnetSettingsPage(DotnetPluginSettings settings) {
        this.settings = settings;
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
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel configRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        configRow.add(new JLabel("Configuração padrão de build:"));
        configRow.add(defaultConfiguration);

        panel.add(formatOnSave);
        panel.add(Box.createVerticalStrut(6));
        panel.add(includePrerelease);
        panel.add(Box.createVerticalStrut(6));
        panel.add(configRow);
        return panel;
    }

    private void syncFromSettings() {
        if (settings == null) {
            return;
        }
        formatOnSave.setSelected(settings.isFormatOnSave());
        includePrerelease.setSelected(settings.isIncludePrerelease());
        defaultConfiguration.setSelectedItem(settings.getDefaultConfiguration());
    }

    @Override
    public void onApply() {
        if (settings == null) {
            return;
        }
        settings.setFormatOnSave(formatOnSave.isSelected());
        settings.setIncludePrerelease(includePrerelease.isSelected());
        settings.setDefaultConfiguration(String.valueOf(defaultConfiguration.getSelectedItem()));
        settings.save();
    }

    @Override
    public void onRestoreDefaults() {
        if (settings == null) {
            return;
        }
        settings.restoreDefaults();
        settings.save();
        syncFromSettings();
    }
}
