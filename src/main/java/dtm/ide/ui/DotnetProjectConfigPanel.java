package dtm.ide.ui;

import dtm.ide.project.DotnetProjectConfig;
import dtm.stools.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
public final class DotnetProjectConfigPanel extends JPanel {

    private static final int PAGE_MAX_WIDTH = 680;
    private static final int CONTROL_HEIGHT = 34;

    private static String text(String key, String def) {
        return I18n.getText(DotnetProjectConfigPanel.class, key, def);
    }

    private final Supplier<Path> projectSupplier;

    private final JComboBox<String> targetFramework = editableCombo(
            "net10.0", "net9.0", "net8.0", "net7.0", "net6.0",
            "net48", "net472", "net471", "net462", "netstandard2.0");
    private final JComboBox<String> outputType = placeholderCombo("", "Exe", "Library", "WinExe");
    private final JComboBox<String> langVersion = placeholderCombo("", "latest", "preview", "12", "11", "10", "9");
    private final JComboBox<String> nullable = placeholderCombo("", "enable", "disable", "warnings", "annotations");
    private final JComboBox<String> implicitUsings = placeholderCombo("", "enable", "disable");
    private final JTextField sdkVersion = new JTextField();
    private final JLabel statusLabel = new JLabel(" ");

    public DotnetProjectConfigPanel(Supplier<Path> projectSupplier) {
        super(new BorderLayout());
        this.projectSupplier = projectSupplier;
        setBackground(Theme.bg());
        add(buildScroller(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        reload();
    }

    private JComponent buildScroller() {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setMaximumSize(new Dimension(PAGE_MAX_WIDTH, Integer.MAX_VALUE));
        column.setAlignmentY(TOP_ALIGNMENT);

        column.add(buildHeader());
        column.add(Box.createVerticalStrut(18));

        Card build = new Card();
        build.add(sectionTitle(text("section.build", "Framework and build")));
        build.add(Box.createVerticalStrut(16));
        build.add(fieldRow(text("field.targetFramework.title", "Target Framework"),
                text("field.targetFramework.desc", ".NET version used to build and run the project."), targetFramework));
        build.add(Box.createVerticalStrut(16));
        build.add(fieldRow(text("field.outputType.title", "Output Type"),
                text("field.outputType.desc", "Build output: executable (Exe/WinExe) or library (Library)."), outputType));
        build.add(Box.createVerticalStrut(16));
        build.add(fieldRow(text("field.langVersion.title", "Language Version (C#)"),
                text("field.langVersion.desc", "C# language version enabled during compilation."), langVersion));
        build.add(Box.createVerticalStrut(16));
        build.add(fieldRow(text("field.sdkVersion.title", ".NET SDK version"),
                text("field.sdkVersion.desc", "Pins the SDK version via global.json. Optional."), sdkVersion));
        column.add(build);
        column.add(Box.createVerticalStrut(14));

        Card language = new Card();
        language.add(sectionTitle(text("section.language", "Language features")));
        language.add(Box.createVerticalStrut(16));
        language.add(fieldRow(text("field.nullable.title", "Nullable"),
                text("field.nullable.desc", "Nullable reference types context."), nullable));
        language.add(Box.createVerticalStrut(16));
        language.add(fieldRow(text("field.implicitUsings.title", "Implicit Usings"),
                text("field.implicitUsings.desc", "Imports common namespaces automatically (implicit using)."), implicitUsings));
        column.add(language);
        column.add(Box.createVerticalStrut(8));

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.X_AXIS));
        center.add(Box.createHorizontalGlue());
        center.add(column);
        center.add(Box.createHorizontalGlue());

        PagePanel page = new PagePanel(new BorderLayout());
        page.setOpaque(false);
        page.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        page.add(center, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(page,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setAlignmentX(LEFT_ALIGNMENT);

        JLabel title = new JLabel(text("header.title", "Project configuration"));
        title.setFont(Theme.uiFont().deriveFont(Font.BOLD, 19f));
        title.setForeground(Theme.text());
        title.setAlignmentX(LEFT_ALIGNMENT);
        header.add(title);

        header.add(Box.createVerticalStrut(4));
        JLabel subtitle = new JLabel(text("header.subtitle", "Adjust the target, output and language features of the .NET project."));
        subtitle.setFont(Theme.uiFont().deriveFont(Font.PLAIN, 12.5f));
        subtitle.setForeground(Theme.muted());
        subtitle.setAlignmentX(LEFT_ALIGNMENT);
        header.add(subtitle);
        return header;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(Theme.barBg());
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.border()),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)));

        statusLabel.setFont(Theme.uiFont().deriveFont(12f));
        statusLabel.setForeground(Theme.muted());
        footer.add(statusLabel, BorderLayout.WEST);

        FlatButton reload = new FlatButton(text("button.reload", "Reload"), false);
        reload.addActionListener(e -> reload());
        FlatButton save = new FlatButton(text("button.save", "Save"), true);
        save.addActionListener(e -> save());

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(reload);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(save);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    private JComponent sectionTitle(String text) {
        JLabel label = new JLabel(text.toUpperCase());
        label.setFont(Theme.uiFont().deriveFont(Font.BOLD, 11f));
        label.setForeground(Theme.accent());
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private JComponent fieldRow(String title, String description, JComponent control) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(Theme.uiFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setForeground(Theme.text());
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        row.add(titleLabel);

        if (description != null && !description.isBlank()) {
            row.add(Box.createVerticalStrut(2));
            JLabel desc = new JLabel(description);
            desc.setFont(Theme.uiFont().deriveFont(Font.PLAIN, 11.5f));
            desc.setForeground(Theme.muted());
            desc.setAlignmentX(LEFT_ALIGNMENT);
            row.add(desc);
        }

        row.add(Box.createVerticalStrut(8));
        styleControl(control);
        control.setAlignmentX(LEFT_ALIGNMENT);
        row.add(control);
        return row;
    }

    private void styleControl(JComponent control) {
        control.setFont(Theme.uiFont().deriveFont(13f));
        control.setMaximumSize(new Dimension(Integer.MAX_VALUE, CONTROL_HEIGHT));
        control.setPreferredSize(new Dimension(PAGE_MAX_WIDTH, CONTROL_HEIGHT));
        if (control instanceof JTextField field) {
            field.setBackground(Theme.fieldBg());
            field.setForeground(Theme.text());
            field.setCaretColor(Theme.text());
            field.setBorder(fieldBorder());
        } else if (control instanceof JComboBox<?> combo) {
            combo.setBackground(Theme.fieldBg());
            combo.setForeground(Theme.text());
        }
    }

    private static Border fieldBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.border(), 1, true),
                BorderFactory.createEmptyBorder(4, 10, 4, 10));
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> editableCombo(String... items) {
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setEditable(true);
        return combo;
    }

    private static JComboBox<String> placeholderCombo(String... items) {
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setRenderer(new PlaceholderRenderer());
        return combo;
    }

    public void reload() {
        Path project = project();
        if (project == null) {
            setStatus(text("status.openProject", "Open a .NET project."), Theme.muted());
            return;
        }
        DotnetProjectConfig config = new DotnetProjectConfig(project);
        Map<String, String> props = config.readProperties();
        targetFramework.setSelectedItem(props.getOrDefault(DotnetProjectConfig.TARGET_FRAMEWORK, ""));
        outputType.setSelectedItem(props.getOrDefault(DotnetProjectConfig.OUTPUT_TYPE, ""));
        langVersion.setSelectedItem(props.getOrDefault(DotnetProjectConfig.LANG_VERSION, ""));
        nullable.setSelectedItem(props.getOrDefault(DotnetProjectConfig.NULLABLE, ""));
        implicitUsings.setSelectedItem(props.getOrDefault(DotnetProjectConfig.IMPLICIT_USINGS, ""));
        sdkVersion.setText(config.readSdkVersion());
        setStatus(config.projectFile().map(p -> text("status.editing", "Editing: ") + p.getFileName())
                .orElse(text("status.noCsproj", "No .csproj found.")), Theme.muted());
    }

    private void save() {
        Path project = project();
        if (project == null) {
            setStatus(text("status.openProject", "Open a .NET project."), Theme.muted());
            return;
        }
        DotnetProjectConfig config = new DotnetProjectConfig(project);
        Map<String, String> props = new LinkedHashMap<>();
        props.put(DotnetProjectConfig.TARGET_FRAMEWORK, value(targetFramework));
        props.put(DotnetProjectConfig.OUTPUT_TYPE, value(outputType));
        props.put(DotnetProjectConfig.LANG_VERSION, value(langVersion));
        props.put(DotnetProjectConfig.NULLABLE, value(nullable));
        props.put(DotnetProjectConfig.IMPLICIT_USINGS, value(implicitUsings));
        try {
            config.writeProperties(props);
            config.writeSdkVersion(sdkVersion.getText());
            setStatus(text("status.saved", "Configuration saved. Reopen/rebuild to apply."), Theme.success());
        } catch (Exception e) {
            setStatus(text("status.saveFailed", "Failed to save: ") + e.getMessage(), Theme.error());
        }
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private static String value(JComboBox<String> combo) {
        Object selected = combo.isEditable() && combo.getEditor() != null
                ? combo.getEditor().getItem() : combo.getSelectedItem();
        return selected == null ? "" : selected.toString().trim();
    }

    private Path project() {
        return projectSupplier == null ? null : projectSupplier.get();
    }

    private static final class Card extends JPanel {
        Card() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(LEFT_ALIGNMENT);
            setBorder(BorderFactory.createEmptyBorder(18, 20, 20, 20));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = 16;
                g2.setColor(Theme.cardBg());
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g2.setColor(Theme.border());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    private static final class FlatButton extends JButton {
        private final boolean primary;
        private boolean hover;

        FlatButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(Theme.uiFont().deriveFont(Font.BOLD, 13f));
            setBorder(BorderFactory.createEmptyBorder(9, 20, 9, 20));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = 10;
                Color base = primary ? Theme.accent() : Theme.subtleBg();
                if (hover) {
                    base = Theme.lighten(base, primary ? 0.12F : 0.10F);
                }
                g2.setColor(base);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                if (!primary) {
                    g2.setColor(Theme.border());
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
            } finally {
                g2.dispose();
            }
            setForeground(primary ? Theme.onAccent() : Theme.text());
            super.paintComponent(g);
        }
    }

    private static final class PlaceholderRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            String text = value == null ? "" : value.toString();
            if (text.isEmpty()) {
                setText(text("placeholder.sdkDefault", "SDK default"));
                if (!isSelected) {
                    setForeground(Theme.muted());
                }
            }
            return this;
        }
    }

    private static final class PagePanel extends JPanel implements Scrollable {
        PagePanel(LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 120;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class Theme {
        private Theme() {
        }

        static Color bg() {
            return resolve("Panel.background", new Color(0x1E2127));
        }

        static Color barBg() {
            return blend(bg(), text(), isDark(bg()) ? 0.04F : 0.03F);
        }

        static Color cardBg() {
            return blend(bg(), text(), isDark(bg()) ? 0.05F : 0.03F);
        }

        static Color subtleBg() {
            return blend(bg(), text(), isDark(bg()) ? 0.10F : 0.06F);
        }

        static Color fieldBg() {
            return resolve("TextField.background", resolve("ComboBox.background", bg()));
        }

        static Color text() {
            return resolve("Label.foreground", new Color(0xD7DEE8));
        }

        static Color muted() {
            return blend(text(), bg(), 0.45F);
        }

        static Color accent() {
            return resolve("Component.accentColor", resolve("ProgressBar.foreground", new Color(0x4F9CF9)));
        }

        static Color border() {
            return resolve("Component.borderColor", blend(bg(), text(), 0.20F));
        }

        static Color success() {
            return isDark(bg()) ? new Color(0x5BC787) : new Color(0x217D4A);
        }

        static Color error() {
            return isDark(bg()) ? new Color(0xE06C75) : new Color(0xC0392B);
        }

        static Color onAccent() {
            return new Color(0xF7FAFF);
        }

        static Color lighten(Color color, float ratio) {
            return blend(color, Color.WHITE, ratio);
        }

        static Font uiFont() {
            Font font = UIManager.getFont("Label.font");
            return font != null ? font : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }

        private static Color resolve(String key, Color fallback) {
            Color color = UIManager.getColor(key);
            return color != null ? color : fallback;
        }

        private static boolean isDark(Color color) {
            if (color == null) {
                return true;
            }
            double luminance = (0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue()) / 255.0;
            return luminance < 0.5;
        }

        private static Color blend(Color base, Color overlay, float ratio) {
            float r = Math.clamp(ratio, 0F, 1F);
            return new Color(
                    Math.round(base.getRed() * (1 - r) + overlay.getRed() * r),
                    Math.round(base.getGreen() * (1 - r) + overlay.getGreen() * r),
                    Math.round(base.getBlue() * (1 - r) + overlay.getBlue() * r));
        }
    }
}
