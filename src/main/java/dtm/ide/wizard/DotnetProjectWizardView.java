package dtm.ide.wizard;

import dtm.ide.api.extension.wizard.ProjectWizardCallback;
import dtm.stools.component.inputfields.osfilepicker.OsFilePicker;
import dtm.stools.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
class DotnetProjectWizardView extends JPanel {

    private static String text(String key, String def) {
        return I18n.getText(DotnetProjectWizardView.class, key, def);
    }

    private static final String STRUCT_SAME = text("struct.same", "Single project (.sln + .csproj in the same folder)");
    private static final String STRUCT_SEPARATE = text("struct.separate", "Solution with modules (.sln at root + project in subfolder)");

    private static final int FIELD_WIDTH = 420;
    private static final int ROW_HEIGHT = 30;

    private final DotnetTemplate template;
    private final ProjectWizardCallback callback;
    private final DotnetProjectScaffolder scaffolder = new DotnetProjectScaffolder();

    private final JTextField nameField = new JTextField("MyDotnetApp");
    private final JTextField locationField = new JTextField();
    private final JComboBox<String> frameworkCombo = new JComboBox<>();
    private final JComboBox<String> structureCombo = new JComboBox<>(new String[]{STRUCT_SAME, STRUCT_SEPARATE});
    private final JCheckBox gitCheckbox = new JCheckBox(text("checkbox.gitignore", "Create .gitignore"), true);
    private final JCheckBox openCheckbox = new JCheckBox(text("checkbox.openAfter", "Open after creating"), true);
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton createButton = new JButton(text("button.create", "Create"));
    private final JButton cancelButton = new JButton(text("button.cancel", "Cancel"));

    DotnetProjectWizardView(DotnetTemplate template, ProjectWizardCallback callback) {
        super(new BorderLayout(8, 8));
        this.template = template;
        this.callback = callback;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        for (String framework : template.frameworkOptions()) {
            frameworkCombo.addItem(framework);
        }
        frameworkCombo.setSelectedItem(template.defaultFramework());
        locationField.setText(defaultLocation().toString());
        add(buildForm(), BorderLayout.NORTH);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    private JComponent buildForm() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JLabel title = new JLabel(template.displayName());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = new JLabel(template.description());
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(title);
        form.add(Box.createVerticalStrut(2));
        form.add(subtitle);
        form.add(Box.createVerticalStrut(12));

        JButton browse = new JButton("…");
        browse.addActionListener(e -> chooseLocation());
        JPanel locationRow = new JPanel(new BorderLayout(6, 0));
        locationRow.add(locationField, BorderLayout.CENTER);
        locationRow.add(browse, BorderLayout.EAST);

        form.add(labeled(text("label.name", "Name"), nameField));
        form.add(Box.createVerticalStrut(8));
        form.add(labeled(text("label.location", "Location"), locationRow));
        form.add(Box.createVerticalStrut(8));
        form.add(labeled(text("label.framework", "Framework"), frameworkCombo));
        form.add(Box.createVerticalStrut(8));
        form.add(labeled(text("label.structure", "Structure"), structureCombo));
        form.add(Box.createVerticalStrut(8));
        gitCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        openCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(gitCheckbox);
        form.add(openCheckbox);
        return form;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(statusLabel, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        cancelButton.addActionListener(e -> {
            if (callback != null) {
                callback.cancel();
            }
        });
        createButton.addActionListener(e -> onCreate());
        buttons.add(cancelButton);
        buttons.add(createButton);
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

    private void onCreate() {
        String name = sanitizeName(nameField.getText());
        if (name.isEmpty()) {
            showError(text("error.nameRequired", "Project name is required."));
            return;
        }
        Path location = parseLocation(locationField.getText());
        if (location == null) {
            showError(text("error.chooseLocation", "Choose an existing location."));
            return;
        }
        Path rootDir = location.resolve(name);
        if (Files.exists(rootDir)) {
            showError(text("error.folderExists", "A folder '{0}' already exists at this location.").replace("{0}", name));
            return;
        }
        String framework = String.valueOf(frameworkCombo.getSelectedItem());
        boolean separate = STRUCT_SEPARATE.equals(structureCombo.getSelectedItem());
        boolean git = gitCheckbox.isSelected();
        boolean openAfter = openCheckbox.isSelected();
        setBusy(true);
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                return scaffolder.create(template, rootDir, name, framework, separate, git);
            }

            @Override
            protected void done() {
                try {
                    Path opened = get();
                    setBusy(false);
                    if (callback == null) {
                        return;
                    }
                    if (openAfter) {
                        callback.notifyProjectCreated(opened);
                    } else {
                        callback.cancel();
                    }
                } catch (Exception e) {
                    setBusy(false);
                    log.error("Erro ao criar solução .NET", e);
                    showError(text("error.createFailed", "Failed to create: ") + e.getMessage());
                }
            }
        }.execute();
    }

    private void chooseLocation() {
        Path current = parseLocation(locationField.getText());
        File initial = current != null && Files.isDirectory(current)
                ? current.toFile()
                : new File(System.getProperty("user.home"));
        File selected = OsFilePicker.openDirectory(text("picker.title", "Select the project location"), initial);
        if (selected != null) {
            locationField.setText(selected.toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void setBusy(boolean busy) {
        createButton.setEnabled(!busy);
        cancelButton.setEnabled(!busy);
        nameField.setEnabled(!busy);
        locationField.setEnabled(!busy);
        frameworkCombo.setEnabled(!busy);
        structureCombo.setEnabled(!busy);
        if (busy) {
            statusLabel.setText(text("status.creating", "Creating…"));
        }
    }

    private void showError(String message) {
        statusLabel.setText(message);
    }

    private Path parseLocation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path path = Paths.get(value.trim()).toAbsolutePath().normalize();
            return Files.isDirectory(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitizeName(String raw) {
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

    private Path defaultLocation() {
        String home = System.getProperty("user.home", ".");
        Path docs = Paths.get(home, "Documents");
        return Files.isDirectory(docs) ? docs.toAbsolutePath() : Paths.get(home).toAbsolutePath();
    }
}
