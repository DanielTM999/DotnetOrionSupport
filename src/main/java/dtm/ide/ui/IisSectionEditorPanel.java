package dtm.ide.ui;

import dtm.ide.iis.IisConfig;
import dtm.stools.component.inputfields.selectfield.DropdownField;
import dtm.stools.component.inputfields.switchfield.SwitchField;
import dtm.stools.component.panels.tab.TabbedPanel;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IisSectionEditorPanel extends JPanel {

    private static final Color ACCENT = new Color(59, 130, 246);

    private final IisConfig.Section section;
    private final Map<String, JComponent> editors = new LinkedHashMap<>();
    private final Map<String, String> originalValues;
    private final Map<String, CollectionEditor> collectionEditors = new LinkedHashMap<>();

    private static String text(String key, String def) {
        return I18n.getText(IisSectionEditorPanel.class, key, def);
    }

    public IisSectionEditorPanel(IisConfig.Section section, IisConfig.Data data) {
        super(new BorderLayout(0, 16));
        this.section = section;
        this.originalValues = new LinkedHashMap<>(data.values());
        setOpaque(false);

        JComponent attributes = buildAttributes(data);
        if (attributes != null) {
            add(attributes, BorderLayout.NORTH);
        }
        if (section.hasCollections()) {
            add(buildCollections(data), BorderLayout.CENTER);
        }
    }

    private JComponent buildAttributes(IisConfig.Data data) {
        List<IisConfig.Attribute> attributes = section.attributes();
        boolean custom = attributes.isEmpty();
        if (custom && data.values().isEmpty()) {
            return null;
        }

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 10, 14);

        if (custom) {
            for (Map.Entry<String, String> entry : data.values().entrySet()) {
                addRow(grid, gbc, IisConfig.Attribute.text(entry.getKey(), entry.getKey()), entry.getValue());
            }
        } else {
            for (IisConfig.Attribute attribute : attributes) {
                addRow(grid, gbc, attribute, data.values().get(attribute.name()));
            }
        }

        JScrollPane scroll = new JScrollPane(grid,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        int rows = custom ? data.values().size() : attributes.size();
        scroll.setPreferredSize(new Dimension(640, Math.min(260, Math.max(1, rows) * 42 + 10)));
        UiSupport.styleScroll(scroll);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.add(sectionTitle(text("label.settings", "Settings")), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JLabel sectionTitle(String title) {
        JLabel label = new JLabel(title);
        label.setForeground(mutedForeground());
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() - 1f));
        return label;
    }

    private void addRow(JPanel panel, GridBagConstraints gbc, IisConfig.Attribute attribute, String value) {
        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(attribute.label()), gbc);

        JComponent editor = createEditor(attribute, value);
        editors.put(attribute.name(), editor);

        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(editor, gbc);
        gbc.gridy++;
    }

    private JComponent createEditor(IisConfig.Attribute attribute, String value) {
        switch (attribute.kind()) {
            case BOOLEAN -> {
                SwitchField field = new SwitchField(Boolean.parseBoolean(value == null ? "false" : value.strip()));
                field.setPreferredSize(new Dimension(58, 28));
                field.setMaximumSize(new Dimension(58, 28));
                JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                holder.setOpaque(false);
                holder.add(field);
                holder.putClientProperty("iisSwitch", field);
                return holder;
            }
            case ENUM -> {
                DropdownField field = new DropdownField(attribute.options().toArray());
                field.setEditable(true);
                field.setPreferredSize(new Dimension(300, 30));
                if (value != null && !value.isBlank()) {
                    field.setSelectedItem(value.strip());
                }
                return field;
            }
            default -> {
                JTextField field = new JTextField(value == null ? "" : value);
                field.setPreferredSize(new Dimension(300, 30));
                return field;
            }
        }
    }

    private JComponent buildCollections(IisConfig.Data data) {
        List<IisConfig.Collection> collections = section.collections();
        if (collections.size() == 1) {
            IisConfig.Collection collection = collections.getFirst();
            CollectionEditor editor = new CollectionEditor(collection, data.itemsOf(collection));
            collectionEditors.put(collection.id(), editor);
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.setOpaque(false);
            panel.add(sectionTitle(collection.title()), BorderLayout.NORTH);
            panel.add(editor, BorderLayout.CENTER);
            return panel;
        }

        TabbedPanel tabs = new TabbedPanel();
        for (IisConfig.Collection collection : collections) {
            CollectionEditor editor = new CollectionEditor(collection, data.itemsOf(collection));
            collectionEditors.put(collection.id(), editor);
            tabs.addTab(collection.title(), editor);
        }
        tabs.setPreferredSize(new Dimension(680, 300));
        return tabs;
    }

    public IisConfig.Data toData() {
        Map<String, String> values = new LinkedHashMap<>(originalValues);
        for (Map.Entry<String, JComponent> entry : editors.entrySet()) {
            values.put(entry.getKey(), readEditor(entry.getValue()));
        }
        Map<String, List<Map<String, String>>> items = new LinkedHashMap<>();
        for (Map.Entry<String, CollectionEditor> entry : collectionEditors.entrySet()) {
            items.put(entry.getKey(), entry.getValue().toItems());
        }
        return new IisConfig.Data(values, items);
    }

    private String readEditor(JComponent editor) {
        Object field = editor.getClientProperty("iisSwitch");
        if (field instanceof SwitchField switchField) {
            return Boolean.toString(switchField.isSelected());
        }
        if (editor instanceof DropdownField dropdown) {
            Object value = dropdown.getSelectedItem();
            return value == null ? "" : value.toString().strip();
        }
        if (editor instanceof JTextField textField) {
            return textField.getText() == null ? "" : textField.getText().strip();
        }
        return "";
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

    private static final class CollectionEditor extends JPanel {

        private final IisConfig.Collection collection;
        private final DefaultTableModel model;
        private final JTable table;

        private CollectionEditor(IisConfig.Collection collection, List<Map<String, String>> items) {
            super(new BorderLayout(0, 10));
            this.collection = collection;
            setOpaque(false);

            List<String> columns = collection.columns();
            model = new DefaultTableModel(columns.toArray(new String[0]), 0);
            for (Map<String, String> item : items) {
                Object[] row = new Object[columns.size()];
                for (int i = 0; i < columns.size(); i++) {
                    row[i] = item.getOrDefault(columns.get(i), "");
                }
                model.addRow(row);
            }

            table = new JTable(model);
            table.setRowHeight(28);
            table.setShowVerticalLines(false);
            table.setShowHorizontalLines(false);
            table.setIntercellSpacing(new Dimension(0, 0));
            table.setFillsViewportHeight(true);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.getTableHeader().setReorderingAllowed(false);
            table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD));

            JScrollPane scroll = new JScrollPane(table);
            scroll.setBorder(BorderFactory.createLineBorder(borderColor(), 1, true));
            scroll.setPreferredSize(new Dimension(640, 250));
            UiSupport.styleScroll(scroll);

            add(scroll, BorderLayout.CENTER);
            add(buildButtons(columns.size()), BorderLayout.SOUTH);
        }

        private JComponent buildButtons(int columnCount) {
            JButton add = roundButton(text("action.addRow", "Add"), true);
            add.addActionListener(e -> {
                stopEditing();
                model.addRow(new Object[columnCount]);
                int last = model.getRowCount() - 1;
                table.setRowSelectionInterval(last, last);
                table.scrollRectToVisible(table.getCellRect(last, 0, true));
                table.editCellAt(last, 0);
                table.requestFocusInWindow();
            });

            JButton remove = roundButton(text("action.removeRow", "Remove"), false);
            remove.addActionListener(e -> {
                stopEditing();
                int row = table.getSelectedRow();
                if (row >= 0) {
                    model.removeRow(row);
                }
            });

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            buttons.setOpaque(false);
            buttons.add(add);
            buttons.add(remove);
            return buttons;
        }

        private JButton roundButton(String label, boolean primary) {
            JButton button = new JButton(label);
            button.setFocusPainted(false);
            button.putClientProperty("JButton.buttonType", "roundRect");
            if (primary) {
                button.setBackground(ACCENT);
                button.setForeground(Color.WHITE);
                button.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(ACCENT, 1, true),
                        BorderFactory.createEmptyBorder(6, 16, 6, 16)));
            } else {
                button.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderColor(), 1, true),
                        BorderFactory.createEmptyBorder(6, 14, 6, 14)));
            }
            return button;
        }

        private void stopEditing() {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }

        private List<Map<String, String>> toItems() {
            stopEditing();
            List<String> columns = collection.columns();
            List<Map<String, String>> items = new ArrayList<>();
            for (int row = 0; row < model.getRowCount(); row++) {
                Map<String, String> item = new LinkedHashMap<>();
                boolean empty = true;
                for (int column = 0; column < columns.size(); column++) {
                    Object value = model.getValueAt(row, column);
                    String cell = value == null ? "" : value.toString().strip();
                    item.put(columns.get(column), cell);
                    empty &= cell.isEmpty();
                }
                if (!empty) {
                    items.add(item);
                }
            }
            return items;
        }
    }

}
