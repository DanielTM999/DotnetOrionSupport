package dtm.ide.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProjectReferenceDialog extends JPanel {

    private final Map<Path, JCheckBox> checks = new LinkedHashMap<>();

    public ProjectReferenceDialog(Path target, List<Path> candidates, Set<Path> referenced) {
        super(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        String targetName = target.getFileName() == null ? target.toString() : target.getFileName().toString();
        JLabel title = new JLabel("Referências de projeto de " + targetName);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        add(title, BorderLayout.NORTH);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (Path candidate : candidates) {
            JCheckBox box = new JCheckBox(label(target, candidate), referenced.contains(candidate));
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            checks.put(candidate, box);
            list.add(box);
        }

        JScrollPane scroll = new JScrollPane(list,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(480, 300));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
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

    private static String label(Path target, Path candidate) {
        String name = candidate.getFileName() == null ? candidate.toString() : candidate.getFileName().toString();
        Path base = target.getParent();
        String location = candidate.toString();
        if (base != null) {
            try {
                location = base.relativize(candidate).toString();
            } catch (Exception ignored) {
            }
        }
        return "<html><b>" + escape(name) + "</b> &nbsp;<span style='color:gray'>"
                + escape(location) + "</span></html>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
