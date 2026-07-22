package dtm.ide.ui;

import dtm.ide.iis.IisFeatures;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

public final class IisFeaturesPanel extends JPanel {

    private final JCheckBox anonymous = new JCheckBox(text("field.anonymous", "Anonymous authentication"));
    private final JCheckBox windows = new JCheckBox(text("field.windows", "Windows authentication"));
    private final JCheckBox basic = new JCheckBox(text("field.basic", "Basic authentication"));
    private final JCheckBox directoryBrowse = new JCheckBox(text("field.directoryBrowse", "Directory browsing"));
    private final JCheckBox defaultDocument = new JCheckBox(text("field.defaultDocument", "Default document"));
    private final JTextArea documents = new JTextArea(6, 30);

    private static String text(String key, String def) {
        return I18n.getText(IisFeaturesPanel.class, key, def);
    }

    public IisFeaturesPanel(IisFeatures.Settings settings) {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        build();
        apply(settings);
    }

    private void build() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.insets = new Insets(0, 0, 6, 0);

        section(gbc, text("section.authentication", "Authentication"));
        add(anonymous, gbc);
        gbc.gridy++;
        add(windows, gbc);
        gbc.gridy++;
        add(basic, gbc);
        gbc.gridy++;

        section(gbc, text("section.browsing", "Browsing"));
        add(directoryBrowse, gbc);
        gbc.gridy++;
        add(defaultDocument, gbc);
        gbc.gridy++;

        section(gbc, text("section.documents", "Default documents (one per line, in order)"));
        JScrollPane scroll = new JScrollPane(documents);
        scroll.setPreferredSize(new Dimension(360, 130));
        UiSupport.styleScroll(scroll);
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        add(scroll, gbc);
    }

    private void section(GridBagConstraints gbc, String title) {
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        gbc.insets = new Insets(gbc.gridy == 0 ? 0 : 14, 0, 6, 0);
        add(label, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 6, 0);
    }

    private void apply(IisFeatures.Settings settings) {
        IisFeatures.Settings effective = settings == null ? IisFeatures.Settings.defaults() : settings;
        anonymous.setSelected(effective.anonymousAuthentication());
        windows.setSelected(effective.windowsAuthentication());
        basic.setSelected(effective.basicAuthentication());
        directoryBrowse.setSelected(effective.directoryBrowse());
        defaultDocument.setSelected(effective.defaultDocument());
        documents.setText(String.join(System.lineSeparator(), effective.documents()));
    }

    public IisFeatures.Settings toSettings() {
        List<String> values = new ArrayList<>();
        for (String line : documents.getText().split("\\R")) {
            String value = line.strip();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return new IisFeatures.Settings(anonymous.isSelected(), windows.isSelected(), basic.isSelected(),
                directoryBrowse.isSelected(), defaultDocument.isSelected(), values);
    }
}
