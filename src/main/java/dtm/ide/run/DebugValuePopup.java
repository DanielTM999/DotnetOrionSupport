package dtm.ide.run;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.Function;

public final class DebugValuePopup {

    private static final int ARC = 12;

    private JWindow window;
    private JLabel label;
    private DebugObjectTreePanel tree;
    private final Timer hideTimer;
    private AWTEventListener outsideClickListener;
    private volatile Function<Integer, List<DebugVar>> childrenProvider;

    public DebugValuePopup() {
        hideTimer = new Timer(8000, e -> hide());
        hideTimer.setRepeats(false);
    }

    public void bindChildrenProvider(Function<Integer, List<DebugVar>> provider) {
        this.childrenProvider = provider;
        if (tree != null) {
            tree.bindChildrenProvider(provider);
        }
    }

    public void show(DebugVar var, Point screen) {
        if (var == null) {
            hide();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            ensureWindow();
            label.setText(html(var));
            tree.bindChildrenProvider(childrenProvider);
            tree.setValue(var);
            window.pack();
            window.setSize(Math.min(Math.max(window.getWidth(), 460), 760),
                    Math.min(Math.max(window.getHeight(), 260), 560));
            int x = screen != null ? screen.x + 14 : 120;
            int y = screen != null ? screen.y + 20 : 120;
            window.setLocation(x, y);
            window.setVisible(true);
            installOutsideClickListener();
            hideTimer.restart();
        });
    }

    public void hide() {
        SwingUtilities.invokeLater(() -> {
            if (window != null) {
                window.setVisible(false);
            }
            uninstallOutsideClickListener();
        });
    }

    private void installOutsideClickListener() {
        if (outsideClickListener != null) {
            return;
        }
        outsideClickListener = event -> {
            if (!(event instanceof MouseEvent mouse) || mouse.getID() != MouseEvent.MOUSE_PRESSED) {
                return;
            }
            if (window == null || !window.isVisible()) {
                return;
            }
            Object source = mouse.getSource();
            if (source instanceof java.awt.Component component
                    && SwingUtilities.isDescendingFrom(component, window)) {
                return;
            }
            hide();
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(outsideClickListener, AWTEvent.MOUSE_EVENT_MASK);
    }

    private void uninstallOutsideClickListener() {
        if (outsideClickListener == null) {
            return;
        }
        Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClickListener);
        outsideClickListener = null;
    }

    private void ensureWindow() {
        if (window != null) {
            return;
        }
        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);
        try {
            window.setBackground(new Color(0, 0, 0, 0));
        } catch (Exception ignored) {
        }
        RoundedCard card = new RoundedCard();
        card.setLayout(new BorderLayout());
        label = new JLabel();
        label.setFont(DebugTheme.monoFont());
        label.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        tree = new DebugObjectTreePanel();
        tree.setPreferredSize(new Dimension(560, 320));
        card.add(label, BorderLayout.NORTH);
        card.add(tree, BorderLayout.CENTER);
        window.setContentPane(card);
    }

    private static String html(DebugVar v) {
        String name = esc(v.name());
        String value = esc(v.value());
        String type = v.type() == null ? "" : esc(v.type());
        Color valueColor = DebugTheme.valueColorFor(v.value(), v.type());
        StringBuilder sb = new StringBuilder("<html><body style='font-family:monospace;'>");
        sb.append("<b style='color:").append(DebugTheme.hex(DebugTheme.nameColor())).append(";'>").append(name).append("</b>");
        sb.append("<span style='color:").append(DebugTheme.hex(DebugTheme.mutedColor())).append(";'> = </span>");
        sb.append("<span style='color:").append(DebugTheme.hex(valueColor)).append(";'>").append(value).append("</span>");
        if (!type.isEmpty()) {
            sb.append("<span style='color:").append(DebugTheme.hex(DebugTheme.mutedColor())).append(";'>&nbsp;&nbsp;").append(type).append("</span>");
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
            g2.setColor(DebugTheme.borderColor());
            g2.setStroke(new BasicStroke(1f));
            g2.draw(shape);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
