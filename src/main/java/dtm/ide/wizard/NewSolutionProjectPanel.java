package dtm.ide.wizard;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import dtm.stools.component.inputfields.textfield.PathTextField;
import dtm.stools.component.inputfields.osfilepicker.OsFilePicker;
import java.io.File;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NewSolutionProjectPanel extends JPanel {

    private static final int FIELD_WIDTH = 420;
    private static final int ROW_HEIGHT = 30;

    private final DotnetProjectScaffolder scaffolder = new DotnetProjectScaffolder();
    private final Path solutionDir;

    private final JComboBox<DotnetTemplate> templateCombo = new JComboBox<>();
    private final JTextField nameField = new JTextField("NewProject");
    private final PathTextField locationField = new PathTextField("/");
    private final JComboBox<String> frameworkCombo = new JComboBox<>();
    private final JLabel statusLabel = new JLabel(" ");

    public NewSolutionProjectPanel(Path solutionDir) {
        super(new BorderLayout(8, 8));
        this.solutionDir = solutionDir;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        for (DotnetTemplate template : DotnetTemplate.available()) {
            templateCombo.addItem(template);
        }
        templateCombo.setRenderer(new TemplateRenderer());
        templateCombo.addActionListener(e -> refreshFrameworks());
        refreshFrameworks();
        add(buildForm(), BorderLayout.NORTH);
        add(statusLabel, BorderLayout.SOUTH);
        addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                nameField.requestFocusInWindow();
                nameField.selectAll();
            }

            @Override public void ancestorRemoved(AncestorEvent event) { }
            @Override public void ancestorMoved(AncestorEvent event) { }
        });
    }

    public Spec getSpec() {
        String name = sanitizeName(nameField.getText());
        if (name.isEmpty()) {
            statusLabel.setText("Nome do projeto é obrigatório.");
            return null;
        }
        if (solutionDir == null) {
            statusLabel.setText("Pasta da solução indisponível.");
            return null;
        }
        Path baseDir;
        try {
            baseDir = resolveLocation(locationField.getText());
        } catch (IllegalArgumentException ex) {
            statusLabel.setText(ex.getMessage());
            return null;
        }
        Path projectDir = baseDir.resolve(name);
        if (Files.exists(projectDir)) {
            Path relative = solutionDir.relativize(projectDir);
            statusLabel.setText("Já existe uma pasta '" + relative + "' na solução.");
            return null;
        }
        DotnetTemplate template = (DotnetTemplate) templateCombo.getSelectedItem();
        if (template == null) {
            statusLabel.setText("Selecione um tipo de projeto.");
            return null;
        }
        String framework = String.valueOf(frameworkCombo.getSelectedItem());
        return new Spec(template, name, framework, projectDir);
    }

    private JComponent buildForm() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Novo projeto na solução");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(title);
        form.add(Box.createVerticalStrut(12));

        form.add(labeled("Tipo", templateCombo));
        form.add(Box.createVerticalStrut(8));
        form.add(labeled("Nome", nameField));
        form.add(Box.createVerticalStrut(8));
        locationField.setPlaceholder("ex.: NetCore ou src/NetStandard (vazio = raiz da solução)");
        locationField.setToolTipText("Subpasta relativa à solução onde o projeto será criado. Vazio = raiz da solução.");
        form.add(labeled("Local (opcional)", locationRow()));
        form.add(Box.createVerticalStrut(8));
        form.add(labeled("Framework", frameworkCombo));
        return form;
    }

    private Path resolveLocation(String raw) {
        String location = raw == null ? "" : raw.trim().replace('\\', '/');
        while (location.startsWith("/")) {
            location = location.substring(1);
        }
        while (location.endsWith("/")) {
            location = location.substring(0, location.length() - 1);
        }
        if (location.isEmpty()) {
            return solutionDir;
        }
        Path base = solutionDir;
        for (String segment : location.split("/")) {
            String part = segment.trim();
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new IllegalArgumentException("O local não pode sair da pasta da solução.");
            }
            base = base.resolve(part);
        }
        Path normalized = base.normalize();
        if (!normalized.startsWith(solutionDir.normalize())) {
            throw new IllegalArgumentException("O local deve ficar dentro da pasta da solução.");
        }
        return normalized;
    }

    private JComponent locationRow() {
        JButton browse = new JButton("...");
        browse.setToolTipText("Escolher pasta dentro da solução");
        browse.setPreferredSize(new Dimension(ROW_HEIGHT + 6, ROW_HEIGHT));
        browse.addActionListener(e -> chooseLocation());
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(locationField, BorderLayout.CENTER);
        row.add(browse, BorderLayout.EAST);
        return row;
    }

    private void chooseLocation() {
        if (solutionDir == null) {
            statusLabel.setText("Pasta da solução indisponível.");
            return;
        }
        Path base = solutionDir.toAbsolutePath().normalize();
        File initial = base.toFile();
        String current = locationField.getText();
        if (current != null && !current.isBlank()) {
            File candidate = base.resolve(current.trim().replace('\\', '/')).toFile();
            if (candidate.isDirectory()) {
                initial = candidate;
            }
        }
        File selected = OsFilePicker.openDirectory("Selecionar pasta do projeto na solução", initial);
        if (selected == null) {
            return;
        }
        Path chosen = selected.toPath().toAbsolutePath().normalize();
        if (!chosen.startsWith(base)) {
            statusLabel.setText("A pasta deve ficar dentro da solução.");
            return;
        }
        String relative = base.relativize(chosen).toString().replace('\\', '/');
        locationField.setText(relative);
        statusLabel.setText(" ");
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

    private void refreshFrameworks() {
        DotnetTemplate template = (DotnetTemplate) templateCombo.getSelectedItem();
        if (template == null) {
            return;
        }
        frameworkCombo.removeAllItems();
        for (String framework : template.frameworkOptions()) {
            frameworkCombo.addItem(framework);
        }
        frameworkCombo.setSelectedItem(template.defaultFramework());
    }

    private static String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    public final class Spec {
        private final DotnetTemplate template;
        private final String name;
        private final String framework;
        private final Path projectDir;

        private Spec(DotnetTemplate template, String name, String framework, Path projectDir) {
            this.template = template;
            this.name = name;
            this.framework = framework;
            this.projectDir = projectDir;
        }

        public String name() {
            return name;
        }

        public Path projectDir() {
            return projectDir;
        }

        public Path scaffold() throws Exception {
            return scaffolder.createProjectOnly(template, projectDir, name, framework);
        }
    }

    private static final class TemplateRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DotnetTemplate template) {
                setText(template.displayName());
            }
            return this;
        }
    }
}
