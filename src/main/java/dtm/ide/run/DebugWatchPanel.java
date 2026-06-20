package dtm.ide.run;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public final class DebugWatchPanel extends JPanel {

    private final List<String> expressions = new CopyOnWriteArrayList<>();
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Expressão", "Valor", "Tipo"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JTextField input = new JTextField();
    private volatile Function<String, DebugVar> evaluator;

    public DebugWatchPanel() {
        super(new BorderLayout());
        setBackground(DebugTheme.contentBg());
        styleInput();
        styleTable();

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(DebugTheme.contentBg());

        add(input, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    public void bindEvaluator(Function<String, DebugVar> evaluator) {
        this.evaluator = evaluator;
    }

    public boolean addExpression(String expression) {
        if (expression == null || expression.isBlank() || expressions.contains(expression)) {
            return false;
        }
        expressions.add(expression);
        refresh();
        return true;
    }

    public List<String> expressions() {
        return List.copyOf(expressions);
    }

    public void refresh() {
        Function<String, DebugVar> eval = evaluator;
        List<Object[]> rows = new ArrayList<>();
        for (String expr : expressions) {
            DebugVar var = eval == null ? null : eval.apply(expr);
            rows.add(new Object[]{
                    expr,
                    var == null ? "—" : var.value(),
                    var == null ? "" : var.type()});
        }
        SwingUtilities.invokeLater(() -> {
            model.setRowCount(0);
            rows.forEach(model::addRow);
        });
    }

    public void clearValues() {
        SwingUtilities.invokeLater(() -> {
            for (int row = 0; row < model.getRowCount(); row++) {
                model.setValueAt("—", row, 1);
                model.setValueAt("", row, 2);
            }
        });
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= expressions.size()) {
            return;
        }
        expressions.remove(row);
        refresh();
    }

    private void styleInput() {
        input.setBackground(DebugTheme.stripeBg());
        input.setForeground(DebugTheme.textColor());
        input.setCaretColor(DebugTheme.textColor());
        input.setFont(DebugTheme.monoFont());
        input.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, DebugTheme.borderColor()),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        input.setToolTipText("Adicionar watch (Enter)");
        input.addActionListener(e -> {
            addExpression(input.getText().trim());
            input.setText("");
        });
    }

    private void styleTable() {
        table.setBackground(DebugTheme.contentBg());
        table.setForeground(DebugTheme.textColor());
        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(DebugTheme.selectionBg());
        table.setSelectionForeground(DebugTheme.textColor());
        table.setBorder(BorderFactory.createEmptyBorder());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeWatch");
        table.getActionMap().put("removeWatch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeSelected();
            }
        });

        JPopupMenu menu = new JPopupMenu();
        JMenuItem remove = new JMenuItem("Remover");
        remove.addActionListener(e -> removeSelected());
        menu.add(remove);
        table.addMouseListener(new MouseAdapter() {
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
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    table.setRowSelectionInterval(row, row);
                }
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setBackground(DebugTheme.headerBg());
        header.setForeground(DebugTheme.mutedColor());
        header.setFont(DebugTheme.uiFont().deriveFont(Font.BOLD, 11f));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, DebugTheme.borderColor()));
        header.setPreferredSize(new Dimension(0, 26));
        ((DefaultTableCellRenderer) header.getDefaultRenderer()).setHorizontalAlignment(SwingConstants.LEFT);

        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(160);
        table.getColumnModel().getColumn(0).setCellRenderer(renderer(0));
        table.getColumnModel().getColumn(1).setCellRenderer(renderer(1));
        table.getColumnModel().getColumn(2).setCellRenderer(renderer(2));
    }

    private DefaultTableCellRenderer renderer(int column) {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                           boolean focused, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, selected, focused, row, col);
                setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
                setFont(column == 0 ? DebugTheme.uiFont().deriveFont(Font.BOLD, 12f) : DebugTheme.monoFont());
                if (!selected) {
                    Color fg;
                    if (column == 0) {
                        fg = DebugTheme.nameColor();
                    } else if (column == 1) {
                        String type = model.getValueAt(row, 2) == null ? "" : model.getValueAt(row, 2).toString();
                        fg = DebugTheme.valueColorFor(value == null ? "" : value.toString(), type);
                    } else {
                        fg = DebugTheme.mutedColor();
                    }
                    c.setForeground(fg);
                    c.setBackground(row % 2 == 0 ? DebugTheme.contentBg() : DebugTheme.stripeBg());
                }
                return c;
            }
        };
    }
}
