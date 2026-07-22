package dtm.ide.ui;

import dtm.ide.iis.IisAppPool;
import dtm.stools.component.inputfields.selectfield.DropdownField;
import dtm.stools.component.inputfields.switchfield.SwitchField;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

public final class IisAppPoolBasicPanel extends JPanel {

    private static final String NO_MANAGED_CODE = "No Managed Code";

    private final JTextField nameField = new JTextField();
    private final DropdownField runtimeField = new DropdownField(
            NO_MANAGED_CODE, ".NET CLR v4.0.30319", ".NET CLR v2.0.50727");
    private final DropdownField pipelineField = new DropdownField("Integrated", "Classic");
    private final SwitchField startImmediately = new SwitchField(true);

    private final boolean creating;

    private static String text(String key, String def) {
        return I18n.getText(IisAppPoolBasicPanel.class, key, def);
    }

    public IisAppPoolBasicPanel(IisAppPool pool) {
        super(new GridBagLayout());
        this.creating = pool == null;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        build();
        apply(pool);
    }

    private void build() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 14, 14);

        row(gbc, text("field.name", "Name:"), nameField);
        row(gbc, text("field.runtime", ".NET CLR version:"), runtimeField);
        row(gbc, text("field.pipeline", "Managed pipeline mode:"), pipelineField);

        JPanel switchHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        switchHolder.setOpaque(false);
        startImmediately.setPreferredSize(new Dimension(58, 28));
        switchHolder.add(startImmediately);
        row(gbc, text("field.startImmediately", "Start application pool immediately:"), switchHolder);
    }

    private void row(GridBagConstraints gbc, String label, Component field) {
        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        if (field instanceof JTextField || field instanceof DropdownField) {
            field.setPreferredSize(new Dimension(320, 30));
        }
        add(field, gbc);
        gbc.gridy++;
    }

    private void apply(IisAppPool pool) {
        if (pool == null) {
            runtimeField.setSelectedItem(NO_MANAGED_CODE);
            pipelineField.setSelectedItem("Integrated");
            return;
        }
        nameField.setText(pool.name());
        nameField.setEditable(false);
        nameField.setEnabled(false);
        runtimeField.setSelectedItem(runtimeLabel(pool.managedRuntimeVersion()));
        pipelineField.setSelectedItem(pool.managedPipelineMode());
        startImmediately.setSelected(!"false".equalsIgnoreCase(pool.autoStart()));
    }

    private String runtimeLabel(String version) {
        if (version == null || version.isBlank()) {
            return NO_MANAGED_CODE;
        }
        return version.startsWith("v2") ? ".NET CLR v2.0.50727" : ".NET CLR v4.0.30319";
    }

    public String poolName() {
        return nameField.getText() == null ? "" : nameField.getText().strip();
    }

    public boolean creating() {
        return creating;
    }

    public String runtimeVersion() {
        Object selected = runtimeField.getSelectedItem();
        String value = selected == null ? NO_MANAGED_CODE : selected.toString();
        if (NO_MANAGED_CODE.equals(value)) {
            return IisAppPool.NO_MANAGED_CODE;
        }
        return value.contains("v2.0") ? "v2.0" : "v4.0";
    }

    public String pipelineMode() {
        Object selected = pipelineField.getSelectedItem();
        return selected == null ? "Integrated" : selected.toString();
    }

    public boolean startImmediately() {
        return startImmediately.isSelected();
    }
}
