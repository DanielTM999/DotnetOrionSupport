package dtm.ide.run;

import dtm.stools.i18n.I18n;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class DebugExpressionField extends JPanel {

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
            "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
            "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
            "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
            "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
            "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed",
            "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw",
            "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using",
            "virtual", "void", "volatile", "while", "nameof");

    private static String text(String key, String def) {
        return I18n.getText(DebugExpressionField.class, key, def);
    }

    private final JTextPane field = new JTextPane();
    private final JButton expand = new JButton(text("button.expand", "Expand"));
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<DebugCompletion> model = new DefaultListModel<>();
    private final JList<DebugCompletion> list = new JList<>(model);
    private final JLabel completionStatus = new JLabel(" ");
    private final List<ActionListener> actionListeners = new CopyOnWriteArrayList<>();
    private final Timer completionTimer;
    private final AtomicLong completionTicket = new AtomicLong();
    private final ExecutorService completionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dotnet-debug-expression-completion");
        t.setDaemon(true);
        return t;
    });

    private volatile Function<String, List<DebugCompletion>> completionProvider = text -> List.of();
    private volatile Color normalBorder = DebugTheme.borderColor();
    private volatile Color normalText = DebugTheme.textColor();
    private volatile Color numberText = new Color(0xB7D98A);
    private volatile Color stringText = new Color(0xCE9178);
    private volatile Color keywordText = new Color(0x7FB7FF);
    private volatile boolean applyingCompletion;
    private volatile boolean highlighting;

    public DebugExpressionField() {
        super(new BorderLayout(0, 0));
        setOpaque(false);
        styleField();
        stylePopup();
        styleExpand();
        registerKeyActions();
        add(field, BorderLayout.CENTER);
        add(expand, BorderLayout.EAST);

        completionTimer = new Timer(140, e -> requestCompletion(false));
        completionTimer.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onTextChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onTextChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onTextChanged();
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKey(e);
            }
        });
        expand.addActionListener(e -> openExpandedEditor());
    }

    public void bindCompletionProvider(Function<String, List<DebugCompletion>> provider) {
        completionProvider = provider == null ? text -> List.of() : provider;
    }

    public void setColors(Color background, Color foreground, Color border) {
        normalBorder = border == null ? DebugTheme.borderColor() : border;
        normalText = foreground == null ? DebugTheme.textColor() : foreground;
        boolean dark = DebugTheme.isDark(background);
        numberText = dark ? new Color(0xB5CEA8) : new Color(0x098658);
        stringText = dark ? new Color(0xCE9178) : new Color(0xA31515);
        keywordText = dark ? new Color(0x569CD6) : new Color(0x0000FF);
        field.setBackground(background);
        field.setForeground(normalText);
        field.setCaretColor(normalText);
        expand.setBackground(background);
        expand.setForeground(normalText);
        applyHighlighting();
        setError(false);
    }

    public void setText(String text) {
        field.setText(text == null ? "" : text);
        applyHighlighting();
    }

    public String text() {
        return field.getText() == null ? "" : field.getText().trim();
    }

    public void addActionListener(ActionListener listener) {
        if (listener != null) {
            actionListeners.add(listener);
        }
    }

    public void requestFocusAndSelectAll() {
        field.requestFocusInWindow();
        field.selectAll();
    }

    public void setError(boolean error) {
        Color border = error ? new Color(0xC44545) : normalBorder;
        setBorder(BorderFactory.createLineBorder(border));
    }

    private void styleField() {
        field.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
        field.setFocusTraversalKeysEnabled(false);
        field.setPreferredSize(new Dimension(0, 32));
        field.setFont(DebugTheme.monoFont());
        field.setToolTipText(text("field.tooltip", "Ctrl+Space opens suggestions. Typing '.' opens members automatically."));
        setBorder(BorderFactory.createLineBorder(normalBorder));
    }

    private void styleExpand() {
        expand.setFocusable(false);
        expand.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, DebugTheme.borderColor()),
                BorderFactory.createEmptyBorder(0, 9, 0, 9)));
    }

    private void stylePopup() {
        list.setBackground(DebugTheme.popupBg());
        list.setForeground(DebugTheme.textColor());
        list.setSelectionBackground(DebugTheme.selectionBg());
        list.setSelectionForeground(DebugTheme.textColor());
        list.setCellRenderer(new CompletionRenderer());
        list.setFixedCellHeight(26);
        completionStatus.setOpaque(true);
        completionStatus.setBackground(DebugTheme.popupBg());
        completionStatus.setForeground(DebugTheme.mutedColor());
        completionStatus.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
        completionStatus.setVisible(false);
        popup.setLayout(new BorderLayout());
        popup.setBorder(BorderFactory.createLineBorder(DebugTheme.borderColor()));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(DebugTheme.popupBg());
        scroll.setPreferredSize(new Dimension(500, 260));
        popup.add(completionStatus, BorderLayout.NORTH);
        popup.add(scroll, BorderLayout.CENTER);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    applySelectedCompletion();
                }
            }
        });
    }

    private void registerKeyActions() {
        InputMap inputMap = field.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = field.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK), "debugExpression.complete");
        actionMap.put("debugExpression.complete", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                requestCompletion(true);
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "debugExpression.evaluate");
        actionMap.put("debugExpression.evaluate", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (popup.isVisible()) {
                    applySelectedCompletion();
                } else {
                    fireAction();
                }
            }
        });
    }

    private void onTextChanged() {
        if (applyingCompletion || highlighting) {
            return;
        }
        applyHighlightingLater();
        setError(false);
        completionTicket.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            if (applyingCompletion || highlighting) {
                return;
            }
            if (popup.isVisible() || shouldAutoRequestCompletion()) {
                completionTimer.restart();
            } else {
                popup.setVisible(false);
            }
        });
    }

    private void requestCompletion(boolean explicit) {
        String text = field.getText();
        long ticket = completionTicket.incrementAndGet();
        if (explicit) {
            showCompletionStatus(text("completion.loading", "Loading suggestions..."));
        }
        completionExecutor.execute(() -> {
            List<DebugCompletion> loaded;
            try {
                loaded = completionProvider.apply(text == null ? "" : text);
            } catch (Exception e) {
                loaded = List.of();
            }
            final List<DebugCompletion> completions = loaded;
            SwingUtilities.invokeLater(() -> {
                if (ticket == completionTicket.get()) {
                    showCompletions(completions, explicit);
                }
            });
        });
    }

    private void showCompletions(List<DebugCompletion> completions, boolean explicit) {
        model.clear();
        if (completions == null || completions.isEmpty() || !field.hasFocus()) {
            if (explicit && field.hasFocus()) {
                showCompletionStatus(text("completion.none", "No suggestions found."));
            } else {
                popup.setVisible(false);
            }
            return;
        }
        completionStatus.setVisible(false);
        int limit = Math.min(80, completions.size());
        for (int i = 0; i < limit; i++) {
            model.addElement(completions.get(i));
        }
        list.setSelectedIndex(0);
        if (!popup.isVisible()) {
            popup.show(field, 0, field.getHeight() + 1);
        }
    }

    private void showCompletionStatus(String message) {
        model.clear();
        completionStatus.setText(message);
        completionStatus.setVisible(true);
        if (field.hasFocus() && !popup.isVisible()) {
            popup.show(field, 0, field.getHeight() + 1);
        }
    }

    private boolean shouldAutoRequestCompletion() {
        int caret = field.getCaretPosition();
        String text = field.getText();
        return text != null && caret > 0 && caret <= text.length() && text.charAt(caret - 1) == '.';
    }

    private void handleKey(KeyEvent e) {
        if (popup.isVisible()) {
            if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                list.setSelectedIndex(Math.min(model.size() - 1, list.getSelectedIndex() + 1));
                list.ensureIndexIsVisible(list.getSelectedIndex());
                e.consume();
            } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1));
                list.ensureIndexIsVisible(list.getSelectedIndex());
                e.consume();
            } else if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_TAB) {
                applySelectedCompletion();
                e.consume();
            } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                popup.setVisible(false);
                e.consume();
            }
        } else if (e.getKeyCode() == KeyEvent.VK_SPACE && e.isControlDown()) {
            requestCompletion(true);
            e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
            fireAction();
            e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_TAB) {
            requestCompletion(true);
            e.consume();
        }
    }

    private void fireAction() {
        ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "evaluate");
        for (ActionListener listener : actionListeners) {
            listener.actionPerformed(event);
        }
    }

    private void applySelectedCompletion() {
        DebugCompletion item = list.getSelectedValue();
        if (item == null) {
            return;
        }
        String text = field.getText();
        String insert = item.insertText() == null || item.insertText().isBlank() ? item.label() : item.insertText();
        int[] range = completionRange(text, item);
        applyingCompletion = true;
        try {
            field.setText(text.substring(0, range[0]) + insert + text.substring(range[1]));
            field.setCaretPosition(range[0] + insert.length());
            applyHighlighting();
        } finally {
            applyingCompletion = false;
        }
        popup.setVisible(false);
    }

    private int[] completionRange(String text, DebugCompletion item) {
        if (item.start() > 0 && item.length() >= 0) {
            int start = Math.max(0, Math.min(text.length(), item.start() - 1));
            int end = Math.max(start, Math.min(text.length(), start + item.length()));
            return new int[]{start, end};
        }
        int caret = field.getCaretPosition();
        int start = Math.max(0, Math.min(caret, text.length()));
        while (start > 0 && isIdentifierChar(text.charAt(start - 1))) {
            start--;
        }
        return new int[]{start, Math.max(0, Math.min(caret, text.length()))};
    }

    private void openExpandedEditor() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, text("dialog.expression", "Expression"));
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setModal(true);
        JTextArea area = new JTextArea(field.getText(), 6, 72);
        area.setFont(field.getFont());
        area.setBackground(field.getBackground());
        area.setForeground(field.getForeground());
        area.setCaretColor(field.getForeground());
        area.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(area);

        JButton ok = new JButton(text("button.ok", "OK"));
        JButton cancel = new JButton(text("button.cancel", "Cancel"));
        ok.addActionListener(e -> {
            field.setText(area.getText());
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(cancel);
        actions.add(ok);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(scroll, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void applyHighlightingLater() {
        SwingUtilities.invokeLater(this::applyHighlighting);
    }

    private void applyHighlighting() {
        if (highlighting) {
            return;
        }
        highlighting = true;
        try {
            StyledDocument doc = field.getStyledDocument();
            String text = field.getText();
            SimpleAttributeSet normal = attrs(normalText);
            doc.setCharacterAttributes(0, text.length(), normal, true);
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '"' || c == '\'') {
                    int start = i++;
                    while (i < text.length()) {
                        char ch = text.charAt(i++);
                        if (ch == '\\' && i < text.length()) {
                            i++;
                        } else if (ch == c) {
                            break;
                        }
                    }
                    doc.setCharacterAttributes(start, i - start, attrs(stringText), true);
                } else if (Character.isDigit(c)) {
                    int start = i++;
                    while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '.')) {
                        i++;
                    }
                    doc.setCharacterAttributes(start, i - start, attrs(numberText), true);
                } else if (Character.isJavaIdentifierStart(c)) {
                    int start = i++;
                    while (i < text.length() && isIdentifierChar(text.charAt(i))) {
                        i++;
                    }
                    String word = text.substring(start, i);
                    if (KEYWORDS.contains(word)) {
                        doc.setCharacterAttributes(start, i - start, attrs(keywordText), true);
                    }
                } else {
                    i++;
                }
            }
        } finally {
            highlighting = false;
        }
    }

    private static SimpleAttributeSet attrs(Color color) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, color);
        return attrs;
    }

    private static final class CompletionRenderer extends JPanel implements ListCellRenderer<DebugCompletion> {
        private final JLabel icon = new JLabel("m");
        private final JLabel label = new JLabel();
        private final JLabel detail = new JLabel();

        CompletionRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 9, 4, 9));
            icon.setForeground(new Color(0xE36D6D));
            detail.setForeground(DebugTheme.mutedColor());
            add(icon, BorderLayout.WEST);
            add(label, BorderLayout.CENTER);
            add(detail, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends DebugCompletion> list, DebugCompletion value,
                                                      int index, boolean selected, boolean cellHasFocus) {
            setBackground(selected ? DebugTheme.selectionBg() : DebugTheme.popupBg());
            String labelText = value == null ? "" : value.label();
            String detailText = value == null ? "" : value.displayDetail();
            label.setText(labelText);
            detail.setText(detailText);
            return this;
        }
    }
}
