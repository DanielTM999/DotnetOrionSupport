package dtm.ide.ui;

import dtm.stools.activity.DialogActivity;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

public final class IisFeatureDialog extends DialogActivity {

    private static final Color ACCENT = new Color(59, 130, 246);

    private final String heading;
    private final String subtitle;
    private final JComponent body;
    private final String confirmText;
    private boolean confirmed;

    private static String text(String key, String def) {
        return I18n.getText(IisFeatureDialog.class, key, def);
    }

    private IisFeatureDialog(Window owner, String heading, String subtitle,
                             JComponent body, String confirmText) {
        super(owner, heading, Dialog.ModalityType.APPLICATION_MODAL);
        this.heading = heading;
        this.subtitle = subtitle;
        this.body = body;
        this.confirmText = confirmText;
    }

    public static boolean show(JComponent parent, String heading, String subtitle,
                               JComponent body, String confirmText) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        IisFeatureDialog dialog = new IisFeatureDialog(owner, heading, subtitle, body, confirmText);
        dialog.init();
        dialog.setVisible(true);
        return dialog.confirmed;
    }

    @Override
    protected void onDrawing() {
        setLayout(new BorderLayout());
        setResizable(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        add(root, BorderLayout.CENTER);

        installEscape();
        setMinimumSize(new Dimension(620, 420));
        pack();
        Dimension size = getSize();
        setSize(new Dimension(Math.min(Math.max(size.width, 720), 1080),
                Math.min(Math.max(size.height, 520), 760)));
        setLocationRelativeTo(getOwner());
    }

    private JComponent buildHeader() {
        JLabel title = new JLabel(heading);
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 5f));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new javax.swing.BoxLayout(titles, javax.swing.BoxLayout.Y_AXIS));
        title.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        titles.add(title);

        if (subtitle != null && !subtitle.isBlank()) {
            JLabel description = new JLabel(subtitle);
            description.setForeground(mutedForeground());
            description.setFont(description.getFont().deriveFont(description.getFont().getSize2D() - 1f));
            description.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            description.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
            titles.add(description);
        }

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor()),
                BorderFactory.createEmptyBorder(0, 0, 14, 0)));
        header.add(titles, BorderLayout.CENTER);
        return header;
    }

    private JComponent buildBody() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(16, 0, 16, 0));
        wrapper.add(body, BorderLayout.CENTER);
        return wrapper;
    }

    private JComponent buildFooter() {
        JButton cancel = new JButton(text("action.cancel", "Cancel"));
        cancel.setFocusPainted(false);
        cancel.putClientProperty("JButton.buttonType", "roundRect");
        cancel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor(), 1, true),
                BorderFactory.createEmptyBorder(8, 20, 8, 20)));
        cancel.addActionListener(e -> close(false));

        JButton confirm = new JButton(confirmText);
        confirm.setFocusPainted(false);
        confirm.putClientProperty("JButton.buttonType", "roundRect");
        confirm.setBackground(ACCENT);
        confirm.setForeground(Color.WHITE);
        confirm.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 1, true),
                BorderFactory.createEmptyBorder(8, 22, 8, 22)));
        confirm.addActionListener(e -> close(true));

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor()),
                BorderFactory.createEmptyBorder(14, 0, 0, 0)));
        footer.add(cancel);
        footer.add(confirm);
        getRootPane().setDefaultButton(confirm);
        return footer;
    }

    private void installEscape() {
        JRootPane rootPane = getRootPane();
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "iisCloseDialog");
        rootPane.getActionMap().put("iisCloseDialog", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close(false);
            }
        });
    }

    private void close(boolean accepted) {
        confirmed = accepted;
        dispose();
    }

    private static Color borderColor() {
        Color color = UIManager.getColor("Component.borderColor");
        if (color == null) {
            color = UIManager.getColor("Separator.foreground");
        }
        return color == null ? new Color(150, 150, 150) : color;
    }

    private static Color mutedForeground() {
        Color color = UIManager.getColor("Label.disabledForeground");
        return color == null ? new Color(110, 110, 110) : color;
    }
}
