package dtm.ide.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class DotnetProcessPickerPanel extends JPanel {

    private final JTextField filter = new JTextField();
    private final DefaultListModel<ProcessItem> model = new DefaultListModel<>();
    private final JList<ProcessItem> list = new JList<>(model);
    private final List<ProcessItem> processes;

    public DotnetProcessPickerPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        processes = ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(process -> process.pid() != ProcessHandle.current().pid())
                .map(DotnetProcessPickerPanel::itemOf)
                .sorted(Comparator.comparing(ProcessItem::display, String.CASE_INSENSITIVE_ORDER))
                .toList();
        filter.putClientProperty("JTextField.placeholderText", "Filtrar por nome, comando ou PID");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(620, 360));
        add(filter, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    public Long selectedPid() {
        ProcessItem selected = list.getSelectedValue();
        return selected == null ? null : selected.pid();
    }

    private void refresh() {
        String needle = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        model.clear();
        for (ProcessItem process : processes) {
            if (needle.isEmpty() || process.display().toLowerCase(Locale.ROOT).contains(needle)) {
                model.addElement(process);
            }
        }
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    private static ProcessItem itemOf(ProcessHandle process) {
        ProcessHandle.Info info = process.info();
        String command = info.command().orElse("");
        String commandLine = info.commandLine().orElse(command);
        String name = command.isBlank() ? "processo" : java.nio.file.Path.of(command).getFileName().toString();
        return new ProcessItem(process.pid(), name + "  [PID " + process.pid() + "]  " + commandLine);
    }

    private record ProcessItem(long pid, String display) {
        @Override public String toString() { return display; }
    }
}
