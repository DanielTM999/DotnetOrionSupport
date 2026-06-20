package dtm.ide.ui;

import dtm.ide.project.DotnetProjectConfig;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
public final class DotnetProjectConfigPanel extends JPanel {

    private static final int FIELD_WIDTH = 360;
    private static final int ROW_HEIGHT = 30;

    private final Supplier<Path> projectSupplier;

    private final JComboBox<String> targetFramework = editableCombo(
            "net8.0", "net7.0", "net6.0", "net48", "net472", "net471", "net462", "netstandard2.0");
    private final JComboBox<String> outputType = new JComboBox<>(new String[]{"", "Exe", "Library", "WinExe"});
    private final JComboBox<String> langVersion = new JComboBox<>(new String[]{"", "latest", "preview", "12", "11", "10", "9"});
    private final JComboBox<String> nullable = new JComboBox<>(new String[]{"", "enable", "disable", "warnings", "annotations"});
    private final JComboBox<String> implicitUsings = new JComboBox<>(new String[]{"", "enable", "disable"});
    private final JTextField sdkVersion = new JTextField();
    private final JLabel statusLabel = new JLabel(" ");

    public DotnetProjectConfigPanel(Supplier<Path> projectSupplier) {
        super(new BorderLayout(8, 8));
        this.projectSupplier = projectSupplier;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(buildForm(), BorderLayout.NORTH);
        add(buildFooter(), BorderLayout.SOUTH);
        reload();
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> editableCombo(String... items) {
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setEditable(true);
        return combo;
    }

    private JComponent buildForm() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(labeled("Target Framework (alvo .NET)", targetFramework));
        form.add(Box.createVerticalStrut(6));
        form.add(labeled("Output Type", outputType));
        form.add(Box.createVerticalStrut(6));
        form.add(labeled("Language Version (C#)", langVersion));
        form.add(Box.createVerticalStrut(6));
        form.add(labeled("Nullable", nullable));
        form.add(Box.createVerticalStrut(6));
        form.add(labeled("Implicit Usings", implicitUsings));
        form.add(Box.createVerticalStrut(6));
        form.add(labeled("Versão do .NET SDK (global.json, opcional)", sdkVersion));
        return form;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(statusLabel, BorderLayout.WEST);
        JButton reload = new JButton("Recarregar");
        reload.addActionListener(e -> reload());
        JButton save = new JButton("Salvar");
        save.addActionListener(e -> save());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(reload);
        buttons.add(save);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    private JPanel labeled(String label, JComponent field) {
        field.setPreferredSize(new Dimension(FIELD_WIDTH, ROW_HEIGHT));
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(FIELD_WIDTH, ROW_HEIGHT + 22));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    public void reload() {
        Path project = project();
        if (project == null) {
            statusLabel.setText("Abra um projeto .NET.");
            return;
        }
        DotnetProjectConfig config = new DotnetProjectConfig(project);
        Map<String, String> props = config.readProperties();
        targetFramework.setSelectedItem(props.getOrDefault(DotnetProjectConfig.TARGET_FRAMEWORK, ""));
        outputType.setSelectedItem(props.getOrDefault(DotnetProjectConfig.OUTPUT_TYPE, ""));
        langVersion.setSelectedItem(props.getOrDefault(DotnetProjectConfig.LANG_VERSION, ""));
        nullable.setSelectedItem(props.getOrDefault(DotnetProjectConfig.NULLABLE, ""));
        implicitUsings.setSelectedItem(props.getOrDefault(DotnetProjectConfig.IMPLICIT_USINGS, ""));
        sdkVersion.setText(config.readSdkVersion());
        statusLabel.setText(config.projectFile().map(p -> "Editando: " + p.getFileName())
                .orElse("Nenhum .csproj encontrado."));
    }

    private void save() {
        Path project = project();
        if (project == null) {
            statusLabel.setText("Abra um projeto .NET.");
            return;
        }
        DotnetProjectConfig config = new DotnetProjectConfig(project);
        Map<String, String> props = new LinkedHashMap<>();
        props.put(DotnetProjectConfig.TARGET_FRAMEWORK, value(targetFramework));
        props.put(DotnetProjectConfig.OUTPUT_TYPE, value(outputType));
        props.put(DotnetProjectConfig.LANG_VERSION, value(langVersion));
        props.put(DotnetProjectConfig.NULLABLE, value(nullable));
        props.put(DotnetProjectConfig.IMPLICIT_USINGS, value(implicitUsings));
        try {
            config.writeProperties(props);
            config.writeSdkVersion(sdkVersion.getText());
            statusLabel.setText("Configuração salva. Reabra/rebuild para aplicar.");
        } catch (Exception e) {
            statusLabel.setText("Falha ao salvar: " + e.getMessage());
        }
    }

    private static String value(JComboBox<String> combo) {
        Object selected = combo.isEditable() && combo.getEditor() != null
                ? combo.getEditor().getItem() : combo.getSelectedItem();
        return selected == null ? "" : selected.toString().trim();
    }

    private Path project() {
        return projectSupplier == null ? null : projectSupplier.get();
    }
}
