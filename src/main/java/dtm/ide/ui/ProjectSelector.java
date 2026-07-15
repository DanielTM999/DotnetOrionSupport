package dtm.ide.ui;

import dtm.stools.i18n.I18n;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ProjectSelector extends JButton {

    private static final class Entry {
        private final Path file;
        private boolean checked;

        private Entry(Path file, boolean checked) {
            this.file = file;
            this.checked = checked;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private Runnable onChange;

    private static String text(String key, String def) {
        return I18n.getText(ProjectSelector.class, key, def);
    }

    ProjectSelector() {
        setHorizontalAlignment(SwingConstants.LEFT);
        setHorizontalTextPosition(SwingConstants.LEFT);
        setIconTextGap(8);
        setIcon(caretIcon());
        addActionListener(e -> showPopup());
        updateText();
    }

    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    void setProjects(List<Path> files) {
        Set<Path> previouslyChecked = new HashSet<>();
        for (Entry entry : entries) {
            if (entry.checked) {
                previouslyChecked.add(entry.file);
            }
        }
        entries.clear();
        for (Path file : files) {
            entries.add(new Entry(file, previouslyChecked.contains(file)));
        }
        if (entries.stream().noneMatch(e -> e.checked) && !entries.isEmpty()) {
            entries.get(0).checked = true;
        }
        setEnabled(!entries.isEmpty());
        updateText();
    }

    List<Path> selectedFiles() {
        List<Path> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.checked) {
                out.add(entry.file);
            }
        }
        return out;
    }

    void selectAll() {
        for (Entry entry : entries) {
            entry.checked = true;
        }
        updateText();
    }

    void selectOnly(Path file) {
        if (file == null) {
            return;
        }
        String target = file.toAbsolutePath().normalize().toString();
        boolean matched = false;
        for (Entry entry : entries) {
            boolean match = entry.file != null
                    && entry.file.toAbsolutePath().normalize().toString().equalsIgnoreCase(target);
            entry.checked = match;
            matched |= match;
        }
        if (!matched && !entries.isEmpty()) {
            entries.get(0).checked = true;
        }
        updateText();
    }

    int projectCount() {
        return entries.size();
    }

    private void showPopup() {
        if (entries.isEmpty()) {
            return;
        }
        JPopupMenu popup = new JPopupMenu();
        popup.setLayout(new java.awt.BorderLayout());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        List<JCheckBox> boxes = new ArrayList<>();
        JCheckBox all = new JCheckBox(text("allProjects", "All projects"));
        all.setSelected(allChecked());
        all.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(all);
        panel.add(Box.createVerticalStrut(4));
        JSeparator separator = new JSeparator();
        separator.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(separator);
        panel.add(Box.createVerticalStrut(4));

        for (Entry entry : entries) {
            JCheckBox box = new JCheckBox(projectName(entry.file), entry.checked);
            box.setAlignmentX(LEFT_ALIGNMENT);
            box.addActionListener(a -> {
                entry.checked = box.isSelected();
                all.setSelected(allChecked());
                updateText();
                fireChange();
            });
            boxes.add(box);
            panel.add(box);
        }

        all.addActionListener(a -> {
            boolean selected = all.isSelected();
            for (int i = 0; i < entries.size(); i++) {
                entries.get(i).checked = selected;
                boxes.get(i).setSelected(selected);
            }
            updateText();
            fireChange();
        });

        JScrollPane scroll = new JScrollPane(panel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        UiSupport.styleScroll(scroll);
        int height = Math.min(panel.getPreferredSize().height + 6, 300);
        scroll.setPreferredSize(new Dimension(Math.max(getWidth(), 240), height));

        popup.add(scroll, java.awt.BorderLayout.CENTER);
        popup.show(this, 0, getHeight());
    }

    private boolean allChecked() {
        return !entries.isEmpty() && entries.stream().allMatch(e -> e.checked);
    }

    private void fireChange() {
        if (onChange != null) {
            onChange.run();
        }
    }

    private void updateText() {
        int total = entries.size();
        long checked = entries.stream().filter(e -> e.checked).count();
        String text;
        if (total == 0) {
            text = text("noProjects", "No projects");
        } else if (checked == 0) {
            text = text("noneSelected", "None selected");
        } else if (checked == total && total > 1) {
            text = text("allProjects", "All projects");
        } else if (checked == 1) {
            text = projectName(firstChecked());
        } else {
            text = checked + " " + text("projectsWord", "projects");
        }
        setText(text);
    }

    private Path firstChecked() {
        for (Entry entry : entries) {
            if (entry.checked) {
                return entry.file;
            }
        }
        return null;
    }

    static String projectName(Path file) {
        if (file == null || file.getFileName() == null) {
            return text("projectFallback", "project");
        }
        return file.getFileName().toString().replaceFirst("(?i)\\.(csproj|vbproj|fsproj)$", "");
    }

    private static Icon caretIcon() {
        int size = 10;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(150, 150, 150));
        int[] xs = {1, size - 1, size / 2};
        int[] ys = {3, 3, size - 2};
        g.fillPolygon(xs, ys, 3);
        g.dispose();
        return new ImageIcon(image);
    }
}
