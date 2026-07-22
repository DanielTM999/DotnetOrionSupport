package dtm.ide.ui;

import dtm.ide.iis.IisAppPoolConfig;
import dtm.stools.component.inputfields.selectfield.DropdownField;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IisPropertyGridPanel extends JPanel {

    private final List<Row> rows = new ArrayList<>();
    private final DefaultTableModel model;
    private final JTable table;
    private final JTextArea description = new JTextArea();

    private static String text(String key, String def) {
        return I18n.getText(IisPropertyGridPanel.class, key, def);
    }

    public IisPropertyGridPanel(List<IisAppPoolConfig.Property> properties, Map<String, String> values) {
        super(new BorderLayout(0, 10));
        setOpaque(false);

        Map<String, List<IisAppPoolConfig.Property>> grouped = new LinkedHashMap<>();
        for (IisAppPoolConfig.Property property : properties) {
            grouped.computeIfAbsent(property.category(), key -> new ArrayList<>()).add(property);
        }
        for (Map.Entry<String, List<IisAppPoolConfig.Property>> entry : grouped.entrySet()) {
            rows.add(new Row(entry.getKey(), null, null));
            for (IisAppPoolConfig.Property property : entry.getValue()) {
                rows.add(new Row(null, property, values.getOrDefault(property.name(), "")));
            }
        }

        model = new DefaultTableModel(new String[]{
                text("column.property", "Property"), text("column.value", "Value")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1 && !rows.get(row).isCategory();
            }
        };
        for (Row row : rows) {
            model.addRow(new Object[]{row.label(), row.value()});
        }

        table = new PropertyTable();
        table.setModel(model);
        table.setRowHeight(26);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD));
        table.setDefaultRenderer(Object.class, new PropertyRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateDescription();
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(borderColor(), 1, true));
        scroll.setPreferredSize(new Dimension(680, 380));
        UiSupport.styleScroll(scroll);

        add(scroll, BorderLayout.CENTER);
        add(buildDescription(), BorderLayout.SOUTH);
        selectFirstProperty();
    }

    private JComponent buildDescription() {
        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        description.setRows(3);
        description.setFont(description.getFont().deriveFont(description.getFont().getSize2D() - 1f));
        description.setForeground(mutedForeground());
        description.setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 2));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor()));
        panel.add(description, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(680, 74));
        return panel;
    }

    private void selectFirstProperty() {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isCategory()) {
                table.setRowSelectionInterval(i, i);
                return;
            }
        }
    }

    private void updateDescription() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= rows.size() || rows.get(row).isCategory()) {
            description.setText("");
            return;
        }
        IisAppPoolConfig.Property property = rows.get(row).property();
        description.setText(property.label() + System.lineSeparator() + property.description());
        description.setCaretPosition(0);
    }

    public Map<String, String> toValues() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.isCategory()) {
                continue;
            }
            Object value = model.getValueAt(i, 1);
            values.put(row.property().name(), value == null ? "" : value.toString().strip());
        }
        return values;
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

    private record Row(String category, IisAppPoolConfig.Property property, String value) {

        private boolean isCategory() {
            return property == null;
        }

        private String label() {
            return isCategory() ? category : property.label();
        }
    }

    private final class PropertyTable extends JTable {

        @Override
        public TableCellEditor getCellEditor(int row, int column) {
            if (column != 1 || rows.get(row).isCategory()) {
                return super.getCellEditor(row, column);
            }
            IisAppPoolConfig.Property property = rows.get(row).property();
            return switch (property.kind()) {
                case BOOLEAN -> {
                    DropdownField field = new DropdownField("true", "false");
                    yield new DefaultCellEditor(field);
                }
                case ENUM -> {
                    DropdownField field = new DropdownField(property.options().toArray());
                    field.setEditable(true);
                    yield new DefaultCellEditor(field);
                }
                default -> new DefaultCellEditor(new JTextField());
            };
        }
    }

    private final class PropertyRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            Row current = rows.get(row);
            if (current.isCategory()) {
                setFont(getFont().deriveFont(Font.BOLD));
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                if (!isSelected) {
                    setBackground(categoryBackground(table));
                    setForeground(table.getForeground());
                }
                setText(column == 0 ? current.category() : "");
                return this;
            }
            setFont(getFont().deriveFont(column == 1 ? Font.BOLD : Font.PLAIN));
            setBorder(BorderFactory.createEmptyBorder(0, column == 0 ? 26 : 10, 0, 10));
            if (!isSelected) {
                setBackground(table.getBackground());
                setForeground(column == 1 ? table.getForeground() : mutedForeground());
            }
            return this;
        }

        private Color categoryBackground(JTable table) {
            Color base = table.getBackground();
            boolean dark = (base.getRed() + base.getGreen() + base.getBlue()) / 3 < 128;
            int delta = dark ? 18 : -14;
            return new Color(
                    Math.clamp(base.getRed() + delta, 0, 255),
                    Math.clamp(base.getGreen() + delta, 0, 255),
                    Math.clamp(base.getBlue() + delta, 0, 255));
        }
    }

}
