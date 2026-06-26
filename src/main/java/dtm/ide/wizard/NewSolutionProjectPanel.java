package dtm.ide.wizard;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;

public final class NewSolutionProjectPanel extends JPanel {

    private static final int FIELD_WIDTH = 420;
    private static final int ROW_HEIGHT = 30;

    private final DotnetProjectScaffolder scaffolder = new DotnetProjectScaffolder();
    private final Path solutionDir;

    private final JComboBox<DotnetTemplate> templateCombo = new JComboBox<>();
    private final JTextField nameField = new JTextField("NewProject");
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
        Path projectDir = solutionDir.resolve(name);
        if (java.nio.file.Files.exists(projectDir)) {
            statusLabel.setText("Já existe uma pasta '" + name + "' na solução.");
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
        form.add(labeled("Framework", frameworkCombo));
        return form;
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
