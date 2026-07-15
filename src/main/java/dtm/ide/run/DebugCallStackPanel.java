package dtm.ide.run;

import dtm.stools.i18n.I18n;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.function.IntConsumer;

public final class DebugCallStackPanel extends JPanel {

    private static String text(String key, String def) {
        return I18n.getText(DebugCallStackPanel.class, key, def);
    }

    private final DefaultListModel<DebugFrame> model = new DefaultListModel<>();
    private final JList<DebugFrame> list = new JList<>(model);
    private final JLabel emptyLabel = new JLabel(text("empty.noSession", "No active debug session"), SwingConstants.CENTER);
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private volatile IntConsumer onSelectFrame;

    public DebugCallStackPanel() {
        super(new BorderLayout());
        setBackground(DebugTheme.contentBg());
        styleList();

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(DebugTheme.contentBg());

        emptyLabel.setOpaque(true);
        emptyLabel.setBackground(DebugTheme.contentBg());
        emptyLabel.setForeground(DebugTheme.mutedColor());
        emptyLabel.setFont(DebugTheme.uiFont().deriveFont(12f));

        content.add(emptyLabel, "empty");
        content.add(scroll, "list");
        add(content, BorderLayout.CENTER);
        cards.show(content, "empty");
    }

    public void bindSelectFrame(IntConsumer onSelectFrame) {
        this.onSelectFrame = onSelectFrame;
    }

    public void setFrames(List<DebugFrame> frames) {
        SwingUtilities.invokeLater(() -> {
            model.clear();
            if (frames == null || frames.isEmpty()) {
                emptyLabel.setText(text("empty.noFrames", "No frames at the current stop"));
                cards.show(content, "empty");
                return;
            }
            frames.forEach(model::addElement);
            cards.show(content, "list");
            if (!model.isEmpty()) {
                list.setSelectedIndex(0);
            }
        });
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> {
            model.clear();
            emptyLabel.setText(text("empty.noSession", "No active debug session"));
            cards.show(content, "empty");
        });
    }

    private static String labelOf(DebugFrame frame, boolean selected) {
        if (frame == null) {
            return "";
        }
        String location = frame.file() == null || frame.file().isBlank()
                ? ""
                : new File(frame.file()).getName() + ":" + frame.line();
        java.awt.Color nameColor = selected
                ? DebugTheme.textColor()
                : (frame.userCode() ? DebugTheme.textColor() : DebugTheme.mutedColor());
        StringBuilder sb = new StringBuilder("<html><body style='white-space:nowrap'>");
        sb.append("<span style='color:").append(DebugTheme.hex(nameColor)).append(";'>")
                .append(esc(frame.name())).append("</span>");
        if (!location.isEmpty()) {
            sb.append("<span style='color:").append(DebugTheme.hex(DebugTheme.mutedColor())).append(";'>&nbsp;&nbsp;")
                    .append(esc(location)).append("</span>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void styleList() {
        list.setBackground(DebugTheme.contentBg());
        list.setForeground(DebugTheme.textColor());
        list.setSelectionBackground(DebugTheme.selectionBg());
        list.setSelectionForeground(DebugTheme.textColor());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(24);
        list.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        installCopyActions();
        list.setCellRenderer((jList, value, index, selected, focused) -> {
            JLabel label = new JLabel(labelOf(value, selected));
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            label.setFont(DebugTheme.monoFont());
            label.setBackground(selected
                    ? DebugTheme.selectionBg()
                    : (index % 2 == 0 ? DebugTheme.contentBg() : DebugTheme.stripeBg()));
            return label;
        });
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            DebugFrame frame = list.getSelectedValue();
            IntConsumer handler = onSelectFrame;
            if (frame != null && handler != null) {
                handler.accept(frame.id());
            }
        });
    }

    private void installCopyActions() {
        list.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copyDebugFrame");
        list.getActionMap().put("copyDebugFrame", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                DebugFrame frame = list.getSelectedValue();
                if (frame != null) {
                    copyText(copyFrameOf(frame));
                }
            }
        });

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showPopup(e);
            }

            private void showPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = list.locationToIndex(e.getPoint());
                if (row >= 0 && list.getCellBounds(row, row).contains(e.getPoint())) {
                    list.setSelectedIndex(row);
                }
                list.requestFocusInWindow();

                DebugFrame frame = list.getSelectedValue();
                if (frame == null) {
                    return;
                }

                JPopupMenu menu = new JPopupMenu();
                JMenuItem copyFrame = new JMenuItem(text("menu.copyFrame", "Copy frame"));
                copyFrame.addActionListener(a -> copyText(copyFrameOf(frame)));
                menu.add(copyFrame);

                JMenuItem copyName = new JMenuItem(text("menu.copyName", "Copy name"));
                copyName.addActionListener(a -> copyText(frame.name()));
                menu.add(copyName);

                JMenuItem copyLocation = new JMenuItem(text("menu.copyLocation", "Copy location"));
                copyLocation.setEnabled(hasLocation(frame));
                copyLocation.addActionListener(a -> copyText(copyLocationOf(frame)));
                menu.add(copyLocation);

                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private static String copyFrameOf(DebugFrame frame) {
        String location = copyLocationOf(frame);
        if (location.isBlank()) {
            return frame.name();
        }
        return frame.name() + "  " + location;
    }

    private static String copyLocationOf(DebugFrame frame) {
        if (!hasLocation(frame)) {
            return "";
        }
        return frame.file() + ":" + frame.line();
    }

    private static boolean hasLocation(DebugFrame frame) {
        return frame != null && frame.file() != null && !frame.file().isBlank();
    }

    private static void copyText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }
}
