package dtm.ide.ui;

import dtm.ide.aspnet.AspNetIndex;
import dtm.stools.i18n.I18n;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class AspNetExplorerPanel extends JPanel implements AutoCloseable {
    private static String text(String key, String fallback) { return I18n.getText(AspNetExplorerPanel.class, key, fallback); }
    private final Supplier<Path> workspace;
    private final Supplier<Map<Path, String>> documents;
    private final BiConsumer<Path, Integer> navigate;
    private final Consumer<AspNetIndex.Snapshot> sink;
    private final java.util.concurrent.ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong generation = new AtomicLong();
    private java.util.concurrent.Future<?> pending;
    private List<AspNetIndex.Entry> entries = List.of();
    private final DefaultTableModel model = new DefaultTableModel(new String[]{text("kind", "Kind"), text("name", "Name"),
            text("file", "File"), text("detail", "Details")}, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");

    public AspNetExplorerPanel(Supplier<Path> workspace, Supplier<Map<Path, String>> documents,
                              BiConsumer<Path, Integer> navigate, Consumer<AspNetIndex.Snapshot> sink) {
        super(new BorderLayout(6, 6));
        this.workspace = workspace; this.documents = documents; this.navigate = navigate; this.sink = sink;
        JButton refresh = new JButton(text("refresh", "Refresh"));
        refresh.addActionListener(e -> refresh());
        JTextField filter = new JTextField(24);
        JPanel toolbar = new JPanel(new WrappingFlowLayout()); toolbar.add(refresh); toolbar.add(new JLabel(text("filter", "Filter:"))); toolbar.add(filter);
        var sorter = new TableRowSorter<>(model); table.setRowSorter(sorter);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void update() { sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(filter.getText()))); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2 || table.getSelectedRow() < 0) return;
                var entry = entries.get(table.convertRowIndexToModel(table.getSelectedRow()));
                if (entry.kind() == AspNetIndex.Kind.CONFIGURATION_USE) {
                    var definitions = entries.stream().filter(candidate -> candidate.kind() == AspNetIndex.Kind.CONFIGURATION
                            && candidate.name().equals(entry.name())).toList();
                    if (definitions.size() == 1) { navigate.accept(definitions.getFirst().file(), definitions.getFirst().line()); return; }
                    if (definitions.size() > 1) {
                        var popup = new JPopupMenu();
                        for (var definition : definitions) {
                            var item = new JMenuItem(definition.file().toString());
                            item.addActionListener(event -> navigate.accept(definition.file(), definition.line())); popup.add(item);
                        }
                        popup.show(table, e.getX(), e.getY()); return;
                    }
                }
                navigate.accept(entry.file(), entry.line());
            }
        });
        add(toolbar, BorderLayout.NORTH); add(new JScrollPane(table), BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    }
    public void refresh() {
        Path root = workspace.get(); if (root == null || worker.isShutdown()) return;
        Map<Path, String> open = documents.get();
        long ticket = generation.incrementAndGet();
        if (pending != null) pending.cancel(true);
        status.setText(text("loading", "Indexing ASP.NET Core..."));
        pending = worker.submit(() -> {
            try {
                var snapshot = new AspNetIndex().scan(root, open);
                SwingUtilities.invokeLater(() -> {
                    if (ticket != generation.get()) return;
                    entries = snapshot.entries(); model.setRowCount(0);
                    entries.forEach(entry -> model.addRow(new Object[]{text("kind." + entry.kind(), entry.kind().toString()), entry.name(),
                            root.relativize(entry.file()), entry.detail()}));
                    sink.accept(snapshot);
                    status.setText(entries.isEmpty() ? text("empty", "No conventional ASP.NET Core entries found.")
                            : entries.size() + " " + text("entries", "entries"));
                    status.setToolTipText(String.join("; ", snapshot.warnings()));
                });
            } catch (Exception error) { SwingUtilities.invokeLater(() -> { if (ticket == generation.get()) status.setText(error.getMessage()); }); }
        });
    }
    @Override public void close() { generation.incrementAndGet(); if (pending != null) pending.cancel(true); worker.shutdownNow(); }
}
