package dtm.ide.ui;

import dtm.ide.iis.IisLaunchSettings;
import dtm.ide.iis.IisService;
import dtm.ide.iis.IisWebProject;
import dtm.ide.run.LaunchSettings;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IisProjectSettingsPanel extends JPanel {

    private final Path projectFile;

    private final JComboBox<String> hostCombo = new JComboBox<>(new String[]{
            LaunchSettings.COMMAND_IIS_EXPRESS, LaunchSettings.COMMAND_IIS});
    private final JTextField profileNameField = new JTextField();
    private final JTextField applicationUrlField = new JTextField();
    private final JSpinner sslPortSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private final JTextField iisApplicationUrlField = new JTextField();
    private final JTextField appPoolField = new JTextField();
    private final JCheckBox windowsAuthentication = new JCheckBox(
            text("field.windowsAuth", "Windows authentication"));
    private final JCheckBox anonymousAuthentication = new JCheckBox(
            text("field.anonymousAuth", "Anonymous authentication"));
    private final JCheckBox launchBrowser = new JCheckBox(text("field.launchBrowser", "Open browser on start"));
    private final JTextField launchUrlField = new JTextField();
    private final JTextArea environmentArea = new JTextArea(5, 32);

    private static String text(String key, String def) {
        return I18n.getText(IisProjectSettingsPanel.class, key, def);
    }

    public IisProjectSettingsPanel(Path projectFile) {
        super(new GridBagLayout());
        this.projectFile = projectFile;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        build();
        load();
    }

    private void build() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 6, 10);

        section(gbc, text("section.host", "Hosting"));
        row(gbc, text("field.host", "Host:"), hostCombo);
        row(gbc, text("field.profile", "Profile name:"), profileNameField);
        row(gbc, text("field.appPool", "Application pool:"), appPoolField);

        section(gbc, text("section.iisExpress", "IIS Express"));
        row(gbc, text("field.applicationUrl", "Application URL:"), applicationUrlField);
        row(gbc, text("field.sslPort", "SSL port (0 = disabled):"), sslPortSpinner);

        section(gbc, text("section.iis", "IIS"));
        row(gbc, text("field.iisApplicationUrl", "Application URL:"), iisApplicationUrlField);

        section(gbc, text("section.authentication", "Authentication"));
        checkRow(gbc, windowsAuthentication);
        checkRow(gbc, anonymousAuthentication);

        section(gbc, text("section.launch", "Launch"));
        checkRow(gbc, launchBrowser);
        row(gbc, text("field.launchUrl", "Launch URL:"), launchUrlField);

        section(gbc, text("section.environment", "Environment variables (NAME=VALUE per line)"));
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JScrollPane scroll = new JScrollPane(environmentArea);
        scroll.setPreferredSize(new Dimension(420, 110));
        UiSupport.styleScroll(scroll);
        add(scroll, gbc);
    }

    private void section(GridBagConstraints gbc, String title) {
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(gbc.gridy == 0 ? 0 : 14, 0, 6, 0);
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        add(label, gbc);
        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.insets = new Insets(0, 0, 6, 10);
    }

    private void row(GridBagConstraints gbc, String label, Component field) {
        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        field.setPreferredSize(new Dimension(280, 26));
        add(field, gbc);
        gbc.gridy++;
    }

    private void checkRow(GridBagConstraints gbc, Component field) {
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(field, gbc);
        gbc.gridy++;
    }

    private void load() {
        String projectName = IisWebProject.projectName(projectFile);
        IisLaunchSettings.Settings settings = IisLaunchSettings.read(projectFile);
        applicationUrlField.setText(settings.iisExpressApplicationUrl());
        sslPortSpinner.setValue(settings.sslPort());
        iisApplicationUrlField.setText(settings.iisApplicationUrl() == null || settings.iisApplicationUrl().isBlank()
                ? "http://localhost/" + projectName : settings.iisApplicationUrl());
        windowsAuthentication.setSelected(settings.windowsAuthentication());
        anonymousAuthentication.setSelected(settings.anonymousAuthentication());
        appPoolField.setText(IisService.suggestAppPoolName(projectName));

        List<LaunchSettings.Profile> profiles = LaunchSettings.iisProfiles(projectFile);
        if (!profiles.isEmpty()) {
            LaunchSettings.Profile profile = profiles.getFirst();
            hostCombo.setSelectedItem(profile.isIisCommand()
                    ? LaunchSettings.COMMAND_IIS : LaunchSettings.COMMAND_IIS_EXPRESS);
            profileNameField.setText(profile.name());
            launchBrowser.setSelected(profile.launchBrowser());
            launchUrlField.setText(profile.launchUrl());
            StringBuilder environment = new StringBuilder();
            for (Map.Entry<String, String> entry : profile.env().entrySet()) {
                environment.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            environmentArea.setText(environment.toString());
        } else {
            profileNameField.setText(LaunchSettings.COMMAND_IIS_EXPRESS);
            launchBrowser.setSelected(true);
            environmentArea.setText("ASPNETCORE_ENVIRONMENT=Development");
        }
    }

    public IisLaunchSettings.Settings toSettings() {
        return new IisLaunchSettings.Settings(
                windowsAuthentication.isSelected(),
                anonymousAuthentication.isSelected(),
                applicationUrlField.getText().strip(),
                ((Number) sslPortSpinner.getValue()).intValue(),
                iisApplicationUrlField.getText().strip());
    }

    public List<IisLaunchSettings.ProfileSpec> toProfiles() {
        String command = String.valueOf(hostCombo.getSelectedItem());
        String name = profileNameField.getText().isBlank() ? command : profileNameField.getText().strip();
        List<IisLaunchSettings.ProfileSpec> specs = new ArrayList<>();
        specs.add(new IisLaunchSettings.ProfileSpec(name, command,
                LaunchSettings.COMMAND_IIS.equals(command)
                        ? iisApplicationUrlField.getText().strip()
                        : applicationUrlField.getText().strip(),
                launchUrlField.getText().strip(),
                launchBrowser.isSelected(),
                parseEnvironment()));
        return specs;
    }

    public String appPoolName() {
        return appPoolField.getText().strip();
    }

    public boolean useFullIis() {
        return LaunchSettings.COMMAND_IIS.equals(hostCombo.getSelectedItem());
    }

    private Map<String, String> parseEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String line : environmentArea.getText().split("\\R")) {
            String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator > 0) {
                environment.put(entry.substring(0, separator).strip(), entry.substring(separator + 1));
            }
        }
        return environment;
    }
}
