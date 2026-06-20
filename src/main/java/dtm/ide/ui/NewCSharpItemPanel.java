package dtm.ide.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

public final class NewCSharpItemPanel extends JPanel {

    public enum Kind {
        CLASS("Class", "class", new Color(78, 201, 176), 'C'),
        INTERFACE("Interface", "interface", new Color(184, 215, 163), 'I'),
        RECORD("Record", "record", new Color(86, 156, 214), 'R'),
        STRUCT("Struct", "struct", new Color(220, 220, 170), 'S'),
        ENUM("Enum", "enum", new Color(197, 134, 192), 'E');

        private final String label;
        private final String keyword;
        private final Color color;
        private final char badge;

        Kind(String label, String keyword, Color color, char badge) {
            this.label = label;
            this.keyword = keyword;
            this.color = color;
            this.badge = badge;
        }

        public String keyword() {
            return keyword;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record Result(String name, Kind kind) {
    }

    private final JTextField nameField = new JTextField();
    private final JList<Kind> kindList = new JList<>(Kind.values());

    private Kind lastAuto = Kind.CLASS;
    private boolean manual = false;

    public NewCSharpItemPanel() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        build();
        wire();
    }

    public Result getResult() {
        String name = sanitize(nameField.getText());
        Kind kind = kindList.getSelectedValue();
        if (name.isEmpty() || kind == null) {
            return null;
        }
        return new Result(name, kind);
    }

    private void build() {
        nameField.setPreferredSize(new Dimension(360, 34));
        nameField.setBackground(inputBg());
        nameField.setForeground(fg());
        nameField.setCaretColor(fg());
        nameField.setBorder(roundedBorder());
        nameField.setFont(nameField.getFont().deriveFont(14f));

        kindList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        kindList.setVisibleRowCount(Kind.values().length);
        kindList.setFixedCellHeight(30);
        kindList.setOpaque(false);
        kindList.setForeground(fg());
        kindList.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        kindList.setCellRenderer(new KindRenderer());

        kindList.setSelectedValue(Kind.CLASS, true);

        add(nameField, BorderLayout.NORTH);
        add(kindList, BorderLayout.CENTER);
    }

    private void wire() {
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { autoSelectKind(); }
            @Override public void removeUpdate(DocumentEvent e) { autoSelectKind(); }
            @Override public void changedUpdate(DocumentEvent e) { autoSelectKind(); }
        });

        kindList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            Kind selected = kindList.getSelectedValue();
            if (selected != null && selected != lastAuto) {
                manual = true;
            }
        });
        addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                nameField.requestFocusInWindow();
            }

            @Override public void ancestorRemoved(AncestorEvent event) { }
            @Override public void ancestorMoved(AncestorEvent event) { }
        });
    }

    private void autoSelectKind() {
        if (manual) {
            return;
        }
        Kind target = isInterfaceName(nameField.getText().trim()) ? Kind.INTERFACE : Kind.CLASS;
        lastAuto = target;
        if (kindList.getSelectedValue() != target) {
            kindList.setSelectedValue(target, true);
        }
    }

    private static boolean isInterfaceName(String name) {
        return name.length() >= 2 && name.charAt(0) == 'I' && Character.isUpperCase(name.charAt(1));
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            }
        }
        if (sb.length() > 0 && Character.isDigit(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    private final class KindRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            Kind kind = (Kind) value;
            setText(kind.label);
            setIcon(new BadgeIcon(kind.color, kind.badge));
            setIconTextGap(10);
            setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
            setFont(getFont().deriveFont(13f));
            setForeground(isSelected ? accentForeground() : fg());
            setBackground(isSelected ? accent() : new Color(0, 0, 0, 0));
            setOpaque(isSelected);
            return this;
        }
    }

    private static final class BadgeIcon implements Icon {
        private static final int SIZE = 18;
        private final Color color;
        private final char letter;

        BadgeIcon(Color color, char letter) {
            this.color = color;
            this.letter = letter;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillRoundRect(x, y, SIZE, SIZE, 6, 6);
                g2.setColor(new Color(20, 20, 20, 220));
                g2.setFont(c.getFont().deriveFont(Font.BOLD, 11f));
                java.awt.FontMetrics fm = g2.getFontMetrics();
                String s = String.valueOf(letter);
                int tx = x + (SIZE - fm.stringWidth(s)) / 2;
                int ty = y + (SIZE - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(s, tx, ty);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

    private static Color inputBg() {
        Color c = UIManager.getColor("TextField.background");
        return c != null ? c : new Color(60, 63, 65);
    }

    private static Color fg() {
        Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : new Color(220, 220, 220);
    }

    private static Color accent() {
        Color c = UIManager.getColor("List.selectionBackground");
        return c != null ? c : new Color(59, 130, 246);
    }

    private static Color accentForeground() {
        Color c = UIManager.getColor("List.selectionForeground");
        return c != null ? c : Color.WHITE;
    }

    private static Color stroke() {
        Color c = UIManager.getColor("Component.borderColor");
        if (c == null) {
            c = UIManager.getColor("Separator.foreground");
        }
        return c != null ? c : new Color(90, 90, 90);
    }

    private static AbstractBorder roundedBorder() {
        Color s = stroke();
        return new AbstractBorder() {
            @Override
            public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(s);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
                g2.drawRoundRect(x, y, width - 1, height - 1, 8, 8);
                g2.dispose();
            }

            @Override
            public Insets getBorderInsets(Component c) {
                return new Insets(7, 10, 7, 10);
            }
        };
    }
}
