package dtm.ide.ui;

import dtm.stools.i18n.I18n;
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

public final class SolutionChooserPanel extends JPanel {

    private final JComboBox<Path> combo = new JComboBox<>();

    private static String text(String key, String def) {
        return I18n.getText(SolutionChooserPanel.class, key, def);
    }

    public SolutionChooserPanel(List<Path> solutions) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel(text("title", "Which solution do you want to open?"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(10));

        for (Path solution : solutions) {
            combo.addItem(solution);
        }
        combo.setRenderer(new SolutionRenderer());
        combo.setAlignmentX(Component.LEFT_ALIGNMENT);
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        combo.setPreferredSize(new Dimension(420, 32));
        add(combo);
    }

    public Path getSelected() {
        return (Path) combo.getSelectedItem();
    }

    private static String solutionName(Path file) {
        if (file == null || file.getFileName() == null) {
            return text("solutionFallback", "solution");
        }
        return file.getFileName().toString();
    }

    private static final class SolutionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Path path) {
                setText(solutionName(path));
            }
            return this;
        }
    }
}
