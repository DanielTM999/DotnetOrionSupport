package dtm.ide.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.util.List;

public final class RunProjectChooserPanel extends JPanel {

    private final JComboBox<Path> combo = new JComboBox<>();

    public RunProjectChooserPanel(List<Path> projects) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("Qual projeto você quer executar?");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(10));

        for (Path project : projects) {
            combo.addItem(project);
        }
        combo.setRenderer(new ProjectRenderer());
        combo.setAlignmentX(Component.LEFT_ALIGNMENT);
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        combo.setPreferredSize(new Dimension(420, 32));
        add(combo);
    }

    public Path getSelected() {
        return (Path) combo.getSelectedItem();
    }

    private static String projectName(Path file) {
        if (file == null || file.getFileName() == null) {
            return "projeto";
        }
        return file.getFileName().toString().replaceFirst("(?i)\\.(csproj|vbproj|fsproj)$", "");
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
