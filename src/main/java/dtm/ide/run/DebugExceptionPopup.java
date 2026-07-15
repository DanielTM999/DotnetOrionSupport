package dtm.ide.run;

import dtm.stools.i18n.I18n;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.RoundRectangle2D;

public final class DebugExceptionPopup {

    private static final int ARC = 10;
    private JWindow window;
    private JLabel title;
    private JTextArea message;
    private JTextArea details;
    private JScrollPane detailsScroll;
    private DebugExceptionInfo current;

    public void show(DebugExceptionInfo info) {
        if (info == null || !info.hasContent()) {
            hide();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            current = info;
            ensureWindow();
            title.setText(html(info.title()));
            message.setText(info.summary());
            details.setText(info.stackTrace() == null ? "" : info.stackTrace());
            detailsScroll.setVisible(info.stackTrace() != null && !info.stackTrace().isBlank());
            window.pack();
            window.setSize(new Dimension(Math.min(Math.max(window.getWidth(), 440), 680),
                    Math.min(Math.max(window.getHeight(), 180), 520)));
            window.setLocation(preferredBounds(window.getWidth(), window.getHeight()).getLocation());
            window.setVisible(true);
            window.toFront();
        });
    }

    public void hide() {
        SwingUtilities.invokeLater(() -> {
            if (window != null) {
                window.setVisible(false);
            }
        });
    }

    private void ensureWindow() {
        if (window != null) {
            return;
        }
        window = new JWindow(ownerWindow());
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(true);
        try {
            window.setBackground(new Color(0, 0, 0, 0));
        } catch (Exception ignored) {
        }

        RoundedCard card = new RoundedCard();
        card.setLayout(new BorderLayout(0, 0));
        card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        JLabel badge = new JLabel("!");
        badge.setHorizontalAlignment(SwingConstants.CENTER);
        badge.setOpaque(true);
        badge.setBackground(new Color(220, 53, 69));
        badge.setForeground(Color.WHITE);
        badge.setFont(DebugTheme.uiFont().deriveFont(Font.BOLD, 13f));
        badge.setPreferredSize(new Dimension(22, 22));
        title = new JLabel();
        title.setForeground(new Color(255, 178, 178));
        title.setFont(DebugTheme.uiFont().deriveFont(Font.BOLD, 13f));
        header.add(badge, BorderLayout.WEST);
        header.add(title, BorderLayout.CENTER);
        card.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        message = new JTextArea();
        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.setForeground(DebugTheme.textColor());
        message.setFont(DebugTheme.uiFont().deriveFont(12f));
        message.setBorder(BorderFactory.createEmptyBorder());
        body.add(message, BorderLayout.NORTH);

        details = new JTextArea();
        details.setEditable(false);
        details.setLineWrap(false);
        details.setOpaque(true);
        details.setBackground(DebugTheme.contentBg());
        details.setForeground(DebugTheme.mutedColor());
        details.setFont(DebugTheme.monoFont().deriveFont(11f));
        details.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        detailsScroll = new JScrollPane(details);
        detailsScroll.setBorder(BorderFactory.createLineBorder(DebugTheme.borderColor()));
        detailsScroll.getViewport().setBackground(DebugTheme.contentBg());
        detailsScroll.setPreferredSize(new Dimension(620, 220));
        body.add(detailsScroll, BorderLayout.CENTER);
        card.add(body, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton copy = button(text("button.copy", "Copy"));
        copy.addActionListener(e -> copyCurrent());
        JButton close = button(text("button.close", "Close"));
        close.addActionListener(e -> hide());
        actions.add(copy);
        actions.add(close);
        card.add(actions, BorderLayout.SOUTH);

        window.setContentPane(card);
    }

    private void copyCurrent() {
        DebugExceptionInfo info = current;
        if (info == null) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(info.copyText()), null);
    }

    private static String text(String key, String def) {
        return I18n.getText(DebugExceptionPopup.class, key, def);
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFont(DebugTheme.uiFont().deriveFont(Font.BOLD, 11f));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DebugTheme.borderColor()),
                BorderFactory.createEmptyBorder(5, 12, 5, 12)));
        return button;
    }

    private static Window ownerWindow() {
        KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        Window window = manager.getActiveWindow();
        return window == null ? manager.getFocusedWindow() : window;
    }

    private static Rectangle preferredBounds(int width, int height) {
        Window owner = ownerWindow();
        Rectangle base = owner != null && owner.isShowing()
                ? owner.getBounds()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int x = base.x + Math.max(32, (base.width - width) / 2);
        int y = base.y + Math.min(Math.max(82, base.height / 8), 160);
        x = Math.max(screen.x + 12, Math.min(x, screen.x + screen.width - width - 12));
        y = Math.max(screen.y + 12, Math.min(y, screen.y + screen.height - height - 12));
        return new Rectangle(x, y, width, height);
    }

    private static String html(String text) {
        return "<html><body style='white-space:nowrap'>" + esc(text) + "</body></html>";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class RoundedCard extends JPanel {
        RoundedCard() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, ARC, ARC);
            g2.setColor(DebugTheme.popupBg());
            g2.fill(shape);
            g2.setColor(new Color(220, 53, 69));
            g2.draw(shape);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
