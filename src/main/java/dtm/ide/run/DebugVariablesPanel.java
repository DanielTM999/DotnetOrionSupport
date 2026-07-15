package dtm.ide.run;

import dtm.stools.i18n.I18n;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public final class DebugVariablesPanel extends JPanel {

    private static String text(String key, String def) {
        return I18n.getText(DebugVariablesPanel.class, key, def);
    }

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JLabel emptyLabel = new JLabel(text("empty.noSession", "No active debug session"), SwingConstants.CENTER);
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dotnet-debug-vars");
        t.setDaemon(true);
        return t;
    });

    private volatile Function<Integer, List<DebugVar>> childrenProvider;

    public DebugVariablesPanel() {
        super(new BorderLayout());
        setBackground(DebugTheme.contentBg());
        styleTree();

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(DebugTheme.contentBg());

        emptyLabel.setOpaque(true);
        emptyLabel.setBackground(DebugTheme.contentBg());
        emptyLabel.setForeground(DebugTheme.mutedColor());
        emptyLabel.setFont(DebugTheme.uiFont().deriveFont(12f));

        content.add(emptyLabel, "empty");
        content.add(scroll, "tree");
        add(content, BorderLayout.CENTER);
        cards.show(content, "empty");
    }

    public void bindChildrenProvider(Function<Integer, List<DebugVar>> provider) {
        this.childrenProvider = provider;
    }

    public void setScopes(List<DebugScope> scopes) {
        SwingUtilities.invokeLater(() -> {
            root.removeAllChildren();
            if (scopes == null || scopes.isEmpty()) {
                emptyLabel.setText(text("empty.noVariables", "No variables in the current frame"));
                model.reload();
                cards.show(content, "empty");
                return;
            }
            for (DebugScope scope : scopes) {
                DebugVariableNode node = DebugVariableNode.scope(scope);
                if (node.expandable()) {
                    node.add(DebugVariableNode.placeholder());
                }
                root.add(node);
            }
            model.reload();
            cards.show(content, "tree");
            if (root.getChildCount() > 0) {
                tree.expandPath(new TreePath(new Object[]{root, root.getChildAt(0)}));
            }
        });
    }

    public void setLoading() {
        SwingUtilities.invokeLater(() -> {
            root.removeAllChildren();
            model.reload();
            emptyLabel.setText(text("empty.loading", "Loading variables..."));
            cards.show(content, "empty");
        });
    }

    public void clearVariables() {
        SwingUtilities.invokeLater(() -> {
            root.removeAllChildren();
            model.reload();
            emptyLabel.setText(text("empty.noSession", "No active debug session"));
            cards.show(content, "empty");
        });
    }

    private void styleTree() {
        tree.setBackground(DebugTheme.contentBg());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new VarRenderer());
        installCopyActions();
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                lazyLoad(event.getPath());
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
            }
        });
    }

    private void installCopyActions() {
        tree.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copyDebugVariableValue");
        tree.getActionMap().put("copyDebugVariableValue", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                DebugVariableNode node = selectedNode();
                if (node != null) {
                    copyText(copyValueOf(node));
                }
            }
        });

        tree.addMouseListener(new MouseAdapter() {
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
                int row = tree.getRowForLocation(e.getX(), e.getY());
                if (row >= 0) {
                    tree.setSelectionRow(row);
                }
                tree.requestFocusInWindow();

                DebugVariableNode node = selectedNode();
                if (node == null) {
                    return;
                }

                JPopupMenu menu = new JPopupMenu();
                JMenuItem copyValue = new JMenuItem(text("menu.copyValue", "Copy value"));
                copyValue.setEnabled(!node.isScope() && hasText(node.value()));
                copyValue.addActionListener(a -> copyText(node.value()));
                menu.add(copyValue);

                JMenuItem copyName = new JMenuItem(node.isScope()
                        ? text("menu.copyScope", "Copy scope") : text("menu.copyName", "Copy name"));
                copyName.addActionListener(a -> copyText(node.name()));
                menu.add(copyName);

                JMenuItem copyLine = new JMenuItem(text("menu.copyLine", "Copy line"));
                copyLine.addActionListener(a -> copyText(copyLineOf(node)));
                menu.add(copyLine);

                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private DebugVariableNode selectedNode() {
        TreePath path = tree.getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof DebugVariableNode node)
                || node.isPlaceholder()) {
            return null;
        }
        return node;
    }

    private static String copyValueOf(DebugVariableNode node) {
        if (node.isScope()) {
            return node.name();
        }
        return hasText(node.value()) ? node.value() : copyLineOf(node);
    }

    private static String copyLineOf(DebugVariableNode node) {
        if (node.isScope()) {
            return node.name();
        }
        StringBuilder sb = new StringBuilder(safe(node.name()));
        if (hasText(node.value())) {
            sb.append(" = ").append(node.value());
        }
        if (hasText(node.type())) {
            sb.append("  ").append(node.type());
        }
        return sb.toString();
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static void copyText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void lazyLoad(TreePath path) {
        Object last = path.getLastPathComponent();
        if (!(last instanceof DebugVariableNode node) || node.isLoaded() || !node.expandable()) {
            return;
        }
        Function<Integer, List<DebugVar>> provider = childrenProvider;
        if (provider == null) {
            return;
        }
        int ref = node.variablesReference();
        loader.execute(() -> {
            List<DebugVar> children = provider.apply(ref);
            SwingUtilities.invokeLater(() -> {
                node.removeAllChildren();
                for (DebugVar var : children) {
                    DebugVariableNode child = DebugVariableNode.variable(var);
                    if (child.expandable()) {
                        child.add(DebugVariableNode.placeholder());
                    }
                    node.add(child);
                }
                node.markLoaded();
                model.nodeStructureChanged(node);
                tree.expandPath(path);
            });
        });
    }

    private static final class VarRenderer extends DefaultTreeCellRenderer {

        VarRenderer() {
            setOpaque(false);
            setBackgroundNonSelectionColor(DebugTheme.contentBg());
            setBackgroundSelectionColor(DebugTheme.selectionBg());
            setBorderSelectionColor(null);
            setTextSelectionColor(DebugTheme.textColor());
            setTextNonSelectionColor(DebugTheme.textColor());
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setFont(DebugTheme.uiFont().deriveFont(12f));
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 8));
            if (!(value instanceof DebugVariableNode node)) {
                setText("");
                setIcon(null);
                return this;
            }
            if (node.isPlaceholder()) {
                setIcon(null);
                setText(html(span(DebugTheme.mutedColor(), text("placeholder.loading", "Loading…"))));
                return this;
            }
            if (node.isScope()) {
                setIcon(DebugVarIcon.scope());
                setText(html(boldSpan(DebugTheme.accentColor(), esc(node.name()))));
                return this;
            }
            setIcon(DebugVarIcon.forVariable(node.asVar()));
            Color valueColor = DebugTheme.valueColorFor(node.value(), node.type());
            StringBuilder sb = new StringBuilder();
            sb.append(boldSpan(DebugTheme.nameColor(), esc(node.name())));
            if (node.value() != null && !node.value().isEmpty()) {
                sb.append(span(DebugTheme.mutedColor(), " = "));
                sb.append(span(valueColor, esc(node.value())));
            }
            if (node.type() != null && !node.type().isEmpty()) {
                sb.append(span(DebugTheme.mutedColor(), "&nbsp;&nbsp;" + esc(node.type())));
            }
            setText(html(sb.toString()));
            return this;
        }

        private static String html(String body) {
            return "<html><body style='white-space:nowrap'>" + body + "</body></html>";
        }

        private static String span(Color color, String text) {
            return "<span style='color:" + DebugTheme.hex(color) + ";'>" + text + "</span>";
        }

        private static String boldSpan(Color color, String text) {
            return "<b style='color:" + DebugTheme.hex(color) + ";'>" + text + "</b>";
        }

        private static String esc(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
