package dtm.ide.ui;

import dtm.stools.component.inputfields.osfilepicker.OsFilePicker;
import dtm.stools.component.inputfields.textfield.PathTextField;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class DotnetPublishPanel extends JPanel {

    public record Rid(String id, String label) {
    }

    public record PublishOptions(
            String configuration,
            String framework,
            boolean selfContained,
            String runtime,
            boolean singleFile,
            boolean trimmed,
            String output) {
    }

    private static final Color ACCENT = new Color(59, 130, 246);
    private static final Color MUTED = new Color(120, 120, 130);

    private static final List<Rid> RIDS = List.of(
            new Rid("win-x64", "Windows 64 bits (win-x64)"),
            new Rid("win-x86", "Windows 32 bits (win-x86)"),
            new Rid("win-arm64", "Windows ARM64 (win-arm64)"),
            new Rid("linux-x64", "Linux 64 bits (linux-x64)"),
            new Rid("linux-arm64", "Linux ARM64 (linux-arm64)"),
            new Rid("linux-musl-x64", "Linux musl / Alpine 64 bits (linux-musl-x64)"),
            new Rid("osx-x64", "macOS Intel (osx-x64)"),
            new Rid("osx-arm64", "macOS Apple Silicon (osx-arm64)"));

    private final JComboBox<String> configCombo = new JComboBox<>(new String[] {"Release", "Debug"});
    private final JComboBox<String> frameworkCombo = new JComboBox<>();
    private final JRadioButton frameworkDependent =
            new JRadioButton("Dependente do framework (precisa do runtime .NET instalado)");
    private final JRadioButton selfContained =
            new JRadioButton("Autocontido (self-contained, empacota o runtime junto)");
    private final JComboBox<Rid> runtimeCombo = new JComboBox<>();
    private final JCheckBox singleFile = new JCheckBox("Publicar como arquivo único (single file)");
    private final JCheckBox trimmed = new JCheckBox("Recortar assemblies não usados (trim)");
    private final PathTextField outputField = new PathTextField(File.separator);

    private final boolean netFrameworkOnly;
    private final Path projectDir;

    public DotnetPublishPanel(List<String> tfms, boolean netFrameworkOnly, Path projectDir) {
        this.netFrameworkOnly = netFrameworkOnly;
        this.projectDir = projectDir;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        addTitle("Configuração");
        configCombo.setSelectedItem("Release");
        addField(configCombo);

        List<String> frameworks = tfms == null ? List.of() : tfms;
        if (!frameworks.isEmpty()) {
            addGap();
            addTitle("Framework de destino");
            for (String tfm : frameworks) {
                frameworkCombo.addItem(tfm);
            }
            frameworkCombo.setEnabled(frameworks.size() > 1);
            addField(frameworkCombo);
        }

        addGap();
        addTitle("Tipo de publicação");
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(frameworkDependent);
        modeGroup.add(selfContained);
        alignLeft(frameworkDependent);
        alignLeft(selfContained);
        frameworkDependent.setSelected(true);
        add(frameworkDependent);
        add(Box.createVerticalStrut(2));
        add(selfContained);

        addGap();
        addTitle("Runtime de destino");
        for (Rid rid : RIDS) {
            runtimeCombo.addItem(rid);
        }
        runtimeCombo.setRenderer(new RidRenderer());
        runtimeCombo.setSelectedItem(defaultRid());
        addField(runtimeCombo);

        add(Box.createVerticalStrut(8));
        alignLeft(singleFile);
        alignLeft(trimmed);
        add(singleFile);
        add(Box.createVerticalStrut(2));
        add(trimmed);

        addGap();
        addTitle("Pasta de saída (opcional)");
        outputField.setPlaceholder("Padrão do projeto (bin/<Configuração>/.../publish)");
        JButton browse = new JButton("Procurar...");
        browse.addActionListener(e -> chooseOutputDir());
        JPanel outputRow = new JPanel();
        outputRow.setLayout(new BoxLayout(outputRow, BoxLayout.X_AXIS));
        outputRow.setOpaque(false);
        outputField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        outputField.setPreferredSize(new Dimension(360, 32));
        outputRow.add(outputField);
        outputRow.add(Box.createHorizontalStrut(8));
        outputRow.add(browse);
        outputRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        alignLeft(outputRow);
        add(outputRow);

        if (netFrameworkOnly) {
            addGap();
            JLabel note = new JLabel(
                    "<html>Projeto .NET Framework: a publicação é apenas para Windows"
                            + " e sempre dependente do framework.</html>");
            note.setForeground(MUTED);
            alignLeft(note);
            add(note);
            selfContained.setEnabled(false);
            frameworkDependent.setSelected(true);
        }

        selfContained.addItemListener(e -> updateSelfContainedState());
        frameworkDependent.addItemListener(e -> updateSelfContainedState());
        updateSelfContainedState();
    }

    private void chooseOutputDir() {
        File initial = null;
        String current = outputField.getText();
        if (current != null && !current.isBlank()) {
            File dir = new File(current.trim());
            if (dir.isDirectory()) {
                initial = dir;
            }
        }
        if (initial == null && projectDir != null && Files.isDirectory(projectDir)) {
            initial = projectDir.toFile();
        }
        File selected = initial != null
                ? OsFilePicker.openDirectory("Selecionar pasta de publicação", initial)
                : OsFilePicker.openDirectory("Selecionar pasta de publicação");
        if (selected != null) {
            outputField.setText(selected.toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void updateSelfContainedState() {
        boolean sc = selfContained.isSelected() && !netFrameworkOnly;
        runtimeCombo.setEnabled(sc);
        singleFile.setEnabled(sc);
        trimmed.setEnabled(sc);
        if (!sc) {
            singleFile.setSelected(false);
            trimmed.setSelected(false);
        }
    }

    public PublishOptions getOptions() {
        boolean sc = selfContained.isSelected() && !netFrameworkOnly;
        String framework = frameworkCombo.getItemCount() > 0
                ? (String) frameworkCombo.getSelectedItem()
                : null;
        Rid rid = (Rid) runtimeCombo.getSelectedItem();
        String out = outputField.getText();
        out = out == null || out.isBlank() ? null : out.trim();
        return new PublishOptions(
                (String) configCombo.getSelectedItem(),
                framework,
                sc,
                sc && rid != null ? rid.id() : null,
                sc && singleFile.isSelected(),
                sc && trimmed.isSelected(),
                out);
    }

    private Rid defaultRid() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        boolean x86 = arch.equals("x86") || arch.equals("i386");
        String prefix;
        if (os.contains("win")) {
            prefix = arm ? "win-arm64" : (x86 ? "win-x86" : "win-x64");
        } else if (os.contains("mac") || os.contains("darwin")) {
            prefix = arm ? "osx-arm64" : "osx-x64";
        } else {
            prefix = arm ? "linux-arm64" : "linux-x64";
        }
        for (Rid rid : RIDS) {
            if (rid.id().equals(prefix)) {
                return rid;
            }
        }
        return RIDS.get(0);
    }

    private void addTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setForeground(ACCENT);
        alignLeft(label);
        add(label);
        add(Box.createVerticalStrut(6));
    }

    private void addField(Component component) {
        alignLeft(component);
        if (component instanceof JComboBox<?> combo) {
            combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            combo.setPreferredSize(new Dimension(460, 32));
        }
        add(component);
    }

    private void addGap() {
        add(Box.createVerticalStrut(14));
    }

    private void alignLeft(Component component) {
        ((javax.swing.JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private static final class RidRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Rid rid) {
                setText(rid.label());
            }
            return this;
        }
    }
}
