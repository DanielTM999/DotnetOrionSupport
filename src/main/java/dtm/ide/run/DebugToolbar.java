package dtm.ide.run;

import dtm.stools.i18n.I18n;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

public final class DebugToolbar extends JPanel {

    private static final String HOT_RELOAD_TEXT = text("button.hotReload", "Hot Reload");
    private static final String HOT_RELOAD_SHORTCUT = "Ctrl+F5";

    private static String text(String key, String def) {
        return I18n.getText(DebugToolbar.class, key, def);
    }

    private volatile Consumer<String> commandSink;
    private JButton hotReloadButton;
    private javax.swing.Timer hotReloadBusyTimer;
    private int hotReloadBusyTick;
    private boolean hotReloadBusy;

    public DebugToolbar() {
        super(new FlowLayout(FlowLayout.LEFT, 8, 6));
        setBackground(DebugTheme.headerBg());
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, DebugTheme.borderColor()));

        JLabel title = new JLabel(text("title", "Debug"));
        title.setFont(DebugTheme.uiFont().deriveFont(Font.BOLD, 12f));
        title.setForeground(DebugTheme.textColor());
        title.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 6));
        add(title);
        add(button(text("button.continue", "Continue"), "F5", "continue", DebugTheme.numberColor()));
        add(button(text("button.pause", "Pause"), "F6", "pause", DebugTheme.accentColor()));
        add(separator());
        add(button(text("button.stepOver", "Step Over"), "F10", "next", DebugTheme.accentColor()));
        add(button(text("button.stepIn", "Step In"), "F11", "stepIn", DebugTheme.accentColor()));
        add(button(text("button.stepOut", "Step Out"), "Shift+F11", "stepOut", DebugTheme.accentColor()));
        add(separator());
        hotReloadButton = button(HOT_RELOAD_TEXT, HOT_RELOAD_SHORTCUT, "hotReload", DebugTheme.numberColor());
        add(hotReloadButton);
        add(separator());
        add(button(text("button.restart", "Restart"), "Ctrl+Shift+F5", "restart", DebugTheme.accentColor()));
        add(button(text("button.stop", "Stop"), "Shift+F5", "stop", DebugTheme.nullColor()));
    }

    public void bindCommandSink(Consumer<String> commandSink) {
        this.commandSink = commandSink;
    }

    public void setHotReloadEnabled(boolean enabled) {
        onUiThread(() -> {
            if (!hotReloadBusy && hotReloadButton != null) {
                hotReloadButton.setEnabled(enabled);
                hotReloadButton.setCursor(Cursor.getPredefinedCursor(
                        enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
    }

    public void startHotReloadBusy() {
        onUiThread(() -> {
            if (hotReloadButton == null) {
                return;
            }
            hotReloadBusy = true;
            hotReloadBusyTick = 0;
            hotReloadButton.setEnabled(false);
            hotReloadButton.setForeground(DebugTheme.accentColor());
            hotReloadButton.setToolTipText(text("tooltip.applyingHotReload", "Applying Hot Reload..."));
            hotReloadButton.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            updateHotReloadBusyText();
            if (hotReloadBusyTimer != null) {
                hotReloadBusyTimer.stop();
            }
            hotReloadBusyTimer = new javax.swing.Timer(280, e -> updateHotReloadBusyText());
            hotReloadBusyTimer.start();
        });
    }

    public void finishHotReloadBusy(boolean enabled) {
        onUiThread(() -> {
            hotReloadBusy = false;
            if (hotReloadBusyTimer != null) {
                hotReloadBusyTimer.stop();
                hotReloadBusyTimer = null;
            }
            if (hotReloadButton == null) {
                return;
            }
            hotReloadButton.setText(HOT_RELOAD_TEXT);
            hotReloadButton.setToolTipText(HOT_RELOAD_TEXT + " (" + HOT_RELOAD_SHORTCUT + ")");
            hotReloadButton.setForeground(DebugTheme.numberColor());
            hotReloadButton.setEnabled(enabled);
            hotReloadButton.setCursor(Cursor.getPredefinedCursor(
                    enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        });
    }

    private void updateHotReloadBusyText() {
        if (hotReloadButton == null) {
            return;
        }
        String dots = ".".repeat(hotReloadBusyTick % 4);
        hotReloadBusyTick++;
        hotReloadButton.setText(text("busy.applying", "Applying") + dots);
    }

    private JPanel separator() {
        JPanel sep = new JPanel();
        sep.setPreferredSize(new Dimension(1, 20));
        sep.setBackground(DebugTheme.borderColor());
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 0));
        wrap.setOpaque(false);
        wrap.add(sep);
        return wrap;
    }

    private JButton button(String text, String shortcut, String command, Color color) {
        JButton button = new JButton(text);
        button.setToolTipText(text + " (" + shortcut + ")");
        button.setForeground(color);
        button.setFont(DebugTheme.uiFont().deriveFont(Font.BOLD, 12f));
        button.setFocusable(false);
        button.setPreferredSize(new Dimension(Math.max(76, text.length() * 8 + 24), 28));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DebugTheme.borderColor()),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setBackground(DebugTheme.headerBg());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(DebugTheme.hoverBg());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(DebugTheme.headerBg());
            }
        });
        button.addActionListener(e -> {
            Consumer<String> sink = commandSink;
            if (sink != null) {
                sink.accept(command);
            }
        });
        return button;
    }

    private static void onUiThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
