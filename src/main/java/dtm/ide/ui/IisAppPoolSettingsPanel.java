package dtm.ide.ui;

import dtm.ide.iis.IisAppPool;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

public final class IisAppPoolSettingsPanel extends JPanel {

    private static final String NO_MANAGED_CODE_LABEL = "No Managed Code";

    private final JTextField nameField = new JTextField();
    private final JComboBox<String> runtimeCombo = new JComboBox<>(
            new String[]{NO_MANAGED_CODE_LABEL, "v4.0", "v2.0"});
    private final JComboBox<String> pipelineCombo = new JComboBox<>(new String[]{"Integrated", "Classic"});
    private final JComboBox<String> identityCombo = new JComboBox<>(new String[]{
            "ApplicationPoolIdentity", "LocalService", "LocalSystem", "NetworkService", "SpecificUser"});
    private final JTextField userNameField = new JTextField();
    private final JCheckBox enable32Bit = new JCheckBox(text("field.enable32Bit", "Enable 32-bit applications"));
    private final JCheckBox autoStart = new JCheckBox(text("field.autoStart", "Start automatically"));
    private final JComboBox<String> startModeCombo = new JComboBox<>(new String[]{"OnDemand", "AlwaysRunning"});
    private final JSpinner queueLength = new JSpinner(new SpinnerNumberModel(1000, 10, 65535, 10));
    private final JSpinner idleTimeout = new JSpinner(new SpinnerNumberModel(20, 0, 43200, 1));
    private final JSpinner maxProcesses = new JSpinner(new SpinnerNumberModel(1, 1, 64, 1));
    private final JSpinner recycleInterval = new JSpinner(new SpinnerNumberModel(1740, 0, 432000, 30));
    private final JSpinner privateMemory = new JSpinner(new SpinnerNumberModel(0L, 0L, 100_000_000L, 1024L));
    private final JSpinner virtualMemory = new JSpinner(new SpinnerNumberModel(0L, 0L, 100_000_000L, 1024L));

    private final boolean creating;

    private static String text(String key, String def) {
        return I18n.getText(IisAppPoolSettingsPanel.class, key, def);
    }

    public IisAppPoolSettingsPanel(IisAppPool pool) {
        super(new GridBagLayout());
        this.creating = pool == null;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        build();
        apply(pool);
        identityCombo.addActionListener(e -> syncUserNameEnabled());
        syncUserNameEnabled();
    }

    private void build() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 6, 10);

        section(gbc, text("section.general", "General"));
        row(gbc, text("field.name", "Name:"), nameField);
        nameField.setEnabled(creating);
        row(gbc, text("field.runtime", ".NET CLR version:"), runtimeCombo);
        row(gbc, text("field.pipeline", "Managed pipeline mode:"), pipelineCombo);
        row(gbc, text("field.startMode", "Start mode:"), startModeCombo);
        row(gbc, text("field.queueLength", "Queue length:"), queueLength);
        checkRow(gbc, enable32Bit);
        checkRow(gbc, autoStart);

        section(gbc, text("section.processModel", "Process model"));
        row(gbc, text("field.identity", "Identity:"), identityCombo);
        row(gbc, text("field.userName", "User (SpecificUser):"), userNameField);
        row(gbc, text("field.idleTimeout", "Idle timeout (minutes):"), idleTimeout);
        row(gbc, text("field.maxProcesses", "Maximum worker processes:"), maxProcesses);

        section(gbc, text("section.recycling", "Recycling"));
        row(gbc, text("field.recycleInterval", "Regular time interval (minutes):"), recycleInterval);
        row(gbc, text("field.privateMemory", "Private memory limit (KB, 0 = off):"), privateMemory);
        row(gbc, text("field.virtualMemory", "Virtual memory limit (KB, 0 = off):"), virtualMemory);
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
        field.setPreferredSize(new Dimension(260, 26));
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

    private void syncUserNameEnabled() {
        userNameField.setEnabled("SpecificUser".equals(identityCombo.getSelectedItem()));
    }

    private void apply(IisAppPool pool) {
        if (pool == null) {
            runtimeCombo.setSelectedItem(NO_MANAGED_CODE_LABEL);
            autoStart.setSelected(true);
            return;
        }
        nameField.setText(pool.name());
        runtimeCombo.setSelectedItem(pool.noManagedCode() ? NO_MANAGED_CODE_LABEL : pool.managedRuntimeVersion());
        pipelineCombo.setSelectedItem(pool.managedPipelineMode());
        identityCombo.setSelectedItem(pool.identityType());
        userNameField.setText(pool.userName());
        enable32Bit.setSelected(pool.enable32Bit());
        autoStart.setSelected(!"false".equalsIgnoreCase(pool.autoStart()));
        startModeCombo.setSelectedItem(pool.startMode());
        queueLength.setValue((int) Math.max(10, Math.min(65535, pool.queueLength())));
        idleTimeout.setValue((int) Math.max(0, Math.min(43200, pool.idleTimeoutMinutes())));
        maxProcesses.setValue((int) Math.max(1, Math.min(64, pool.maxProcesses())));
        recycleInterval.setValue((int) Math.max(0, Math.min(432000, pool.recyclingIntervalMinutes())));
        privateMemory.setValue(Math.max(0, pool.recyclingPrivateMemoryKb()));
        virtualMemory.setValue(Math.max(0, pool.recyclingVirtualMemoryKb()));
    }

    public String poolName() {
        return nameField.getText() == null ? "" : nameField.getText().strip();
    }

    public String runtimeVersion() {
        Object selected = runtimeCombo.getSelectedItem();
        return NO_MANAGED_CODE_LABEL.equals(selected) ? IisAppPool.NO_MANAGED_CODE : String.valueOf(selected);
    }

    public String pipelineMode() {
        return String.valueOf(pipelineCombo.getSelectedItem());
    }

    public IisAppPool toPool(IisAppPool original) {
        return new IisAppPool(
                poolName(),
                original == null ? "" : original.state(),
                runtimeVersion(),
                pipelineMode(),
                String.valueOf(identityCombo.getSelectedItem()),
                userNameField.getText() == null ? "" : userNameField.getText().strip(),
                enable32Bit.isSelected(),
                String.valueOf(startModeCombo.getSelectedItem()),
                Boolean.toString(autoStart.isSelected()),
                ((Number) queueLength.getValue()).longValue(),
                ((Number) idleTimeout.getValue()).longValue(),
                ((Number) maxProcesses.getValue()).longValue(),
                ((Number) recycleInterval.getValue()).longValue(),
                ((Number) privateMemory.getValue()).longValue(),
                ((Number) virtualMemory.getValue()).longValue(),
                original == null ? java.util.Map.of() : original.rawAttributes());
    }
}
