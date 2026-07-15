package dtm.ide.ui;

import dtm.ide.reference.ProjectReferenceService;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SolutionReferenceDialog extends JPanel {

    private final List<Path> projects;
    private final JComboBox<Path> sourceCombo = new JComboBox<>();
    private final JPanel listPanel = new JPanel();
    private final Map<Path, JCheckBox> checks = new LinkedHashMap<>();

    private static String text(String key, String def) {
        return I18n.getText(SolutionReferenceDialog.class, key, def);
    }

    public SolutionReferenceDialog(List<Path> projects) {
        super(new BorderLayout(0, 10));
        this.projects = new ArrayList<>(projects);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel title = new JLabel(text("title", "Solution project references"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));

        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.add(title, BorderLayout.NORTH);
        JPanel sourceRow = new JPanel(new BorderLayout(8, 0));
        sourceRow.add(new JLabel(text("project", "Project:")), BorderLayout.WEST);
        for (Path project : this.projects) {
            sourceCombo.addItem(project);
        }
        sourceCombo.setRenderer(new ProjectRenderer());
        sourceCombo.addActionListener(e -> rebuildList());
        sourceRow.add(sourceCombo, BorderLayout.CENTER);
        header.add(sourceRow, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(listPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(480, 280));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        rebuildList();
    }

    public Path getSourceProject() {
        return (Path) sourceCombo.getSelectedItem();
    }

    public List<Path> getCandidates() {
        return new ArrayList<>(checks.keySet());
    }

    public Set<Path> getSelected() {
        Set<Path> selected = new LinkedHashSet<>();
        checks.forEach((path, box) -> {
            if (box.isSelected()) {
                selected.add(path);
            }
        });
        return selected;
    }

    private void rebuildList() {
        Path source = getSourceProject();
        listPanel.removeAll();
        checks.clear();
        if (source != null) {
            Set<Path> referenced = new HashSet<>(ProjectReferenceService.listProjectReferences(source));
            for (Path candidate : projects) {
                if (candidate.equals(source)) {
                    continue;
                }
                JCheckBox box = new JCheckBox(label(candidate), referenced.contains(candidate));
                box.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
                checks.put(candidate, box);
                listPanel.add(box);
            }
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    private static String label(Path candidate) {
        String name = candidate.getFileName() == null ? candidate.toString() : candidate.getFileName().toString();
        return "<html><b>" + escape(name) + "</b> &nbsp;<span style='color:gray'>"
                + escape(candidate.toString()) + "</span></html>";
    }

    private static String projectName(Path file) {
        if (file == null || file.getFileName() == null) {
            return text("projectFallback", "project");
        }
        return file.getFileName().toString().replaceFirst("(?i)\\.(csproj|vbproj|fsproj)$", "");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class ProjectRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Path path) {
                setText(projectName(path));
            }
            return this;
        }
    }
}
