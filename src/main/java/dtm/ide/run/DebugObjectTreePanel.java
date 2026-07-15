package dtm.ide.run;

import dtm.stools.i18n.I18n;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
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
import java.awt.Color;
import java.awt.Component;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public final class DebugObjectTreePanel extends JPanel {

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JLabel empty = new JLabel(text("empty.noValue", "No value"), SwingConstants.CENTER);
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dotnet-debug-object-tree");
        t.setDaemon(true);
        return t;
    });

    private volatile Function<Integer, List<DebugVar>> childrenProvider;

    private static String text(String key, String def) {
        return I18n.getText(DebugObjectTreePanel.class, key, def);
    }

    public DebugObjectTreePanel() {
        super(new BorderLayout());
        setBackground(DebugTheme.contentBg());
        styleTree();
        empty.setOpaque(true);
        empty.setBackground(DebugTheme.contentBg());
        empty.setForeground(DebugTheme.mutedColor());
        empty.setFont(DebugTheme.uiFont().deriveFont(12f));

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(DebugTheme.contentBg());
        add(scroll, BorderLayout.CENTER);
    }

    public void bindChildrenProvider(Function<Integer, List<DebugVar>> provider) {
        this.childrenProvider = provider;
    }

    public void setValue(DebugVar value) {
        SwingUtilities.invokeLater(() -> {
            removeAll();
            root.removeAllChildren();
            if (value == null) {
                add(empty, BorderLayout.CENTER);
                revalidate();
                repaint();
                return;
            }
            DebugVariableNode node = DebugVariableNode.variable(value);
            if (node.expandable()) {
                node.add(DebugVariableNode.placeholder());
            }
            root.add(node);
            model.reload();
            add(new JScrollPane(tree), BorderLayout.CENTER);
            tree.expandPath(new TreePath(new Object[]{root, node}));
            revalidate();
            repaint();
        });
    }

    private void styleTree() {
        tree.setBackground(DebugTheme.contentBg());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
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

    private void lazyLoad(TreePath path) {
        Object last = path.getLastPathComponent();
        if (!(last instanceof DebugVariableNode node) || node.isLoaded() || !node.expandable()) {
            return;
        }
        Function<Integer, List<DebugVar>> provider = childrenProvider;
        if (provider == null) {
            return;
        }
        loader.execute(() -> {
            List<DebugVar> children = provider.apply(node.variablesReference());
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

    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setFont(DebugTheme.monoFont());
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 8));
            setBackgroundNonSelectionColor(DebugTheme.contentBg());
            setBackgroundSelectionColor(DebugTheme.selectionBg());
            setTextSelectionColor(DebugTheme.textColor());
            setTextNonSelectionColor(DebugTheme.textColor());
            if (!(value instanceof DebugVariableNode node)) {
                setText("");
                setIcon(null);
                return this;
            }
            if (node.isPlaceholder()) {
                setText(html(span(DebugTheme.mutedColor(), text("placeholder.loading", "Loading..."))));
                setIcon(null);
                return this;
            }
            setIcon(node.isScope() ? DebugVarIcon.scope() : DebugVarIcon.forVariable(node.asVar()));
            Color valueColor = DebugTheme.valueColorFor(node.value(), node.type());
            StringBuilder sb = new StringBuilder();
            sb.append(bold(DebugTheme.nameColor(), esc(node.name())));
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

        private static String bold(Color color, String text) {
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
