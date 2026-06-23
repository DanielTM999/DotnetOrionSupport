package dtm.ide.ui;

import dtm.ide.run.DotnetBuild;
import dtm.ide.sdk.DotnetSdkService;
import lombok.extern.slf4j.Slf4j;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class DotnetTestExplorerPanel extends JPanel {

    private enum Status { UNKNOWN, RUNNING, PASSED, FAILED }

    private static final Pattern RESULT_LINE = Pattern.compile("^(Passed|Failed|Skipped)\\s+(\\S+)");

    private final Supplier<Path> projectSupplier;
    private final Supplier<DotnetSdkService> sdkSupplier;
    private final Supplier<String> configSupplier;
    private final DotnetBuild build = new DotnetBuild();

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Testes");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final JTextArea console = new JTextArea();
    private final JLabel status = new JLabel("Pronto.");
    private final Map<String, Status> statuses = new LinkedHashMap<>();
    private final List<String> allTests = new ArrayList<>();

    private final JButton refreshButton = new JButton("Atualizar");
    private final JButton runAllButton = new JButton("Rodar todos");
    private final JButton runSelectedButton = new JButton("Rodar selecionado");
    private final JButton stopButton = new JButton("Parar");

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dotnet-test-explorer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean busy;

    public DotnetTestExplorerPanel(Supplier<Path> projectSupplier, Supplier<DotnetSdkService> sdkSupplier,
                                   Supplier<String> configSupplier) {
        super(new BorderLayout());
        this.projectSupplier = projectSupplier;
        this.sdkSupplier = sdkSupplier;
        this.configSupplier = configSupplier;
        buildUi();
    }

    private void buildUi() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(refreshButton);
        toolbar.add(runAllButton);
        toolbar.add(runSelectedButton);
        toolbar.add(stopButton);
        refreshButton.addActionListener(e -> refresh());
        runAllButton.addActionListener(e -> runAll());
        runSelectedButton.addActionListener(e -> runSelected());
        stopButton.addActionListener(e -> stop());
        stopButton.setEnabled(false);

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new StatusRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    runSelected();
                }
            }
        });

        console.setEditable(false);
        console.setLineWrap(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(tree), new JScrollPane(console));
        split.setResizeWeight(0.55);

        add(toolbar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    public void refresh() {
        if (busy) {
            return;
        }
        Path project = projectSupplier == null ? null : projectSupplier.get();
        Optional<Path> dotnet = resolveDotnet(project);
        if (project == null || dotnet.isEmpty()) {
            setStatus("Toolchain .NET indisponível. Abra um projeto .NET.");
            return;
        }
        setBusy(true, "Descobrindo testes...");
        clearConsole();
        appendConsole("> dotnet test --list-tests" + System.lineSeparator());
        String configuration = config();
        worker.execute(() -> {
            List<String> tests;
            try {
                tests = build.listTests(project, dotnet.get(), configuration);
            } catch (Exception ex) {
                tests = List.of();
                log.debug("Falha ao listar testes: {}", ex.getMessage());
            }
            List<String> found = tests;
            SwingUtilities.invokeLater(() -> {
                populateTree(found);
                setBusy(false, found.isEmpty()
                        ? "Nenhum teste encontrado (há um projeto de teste na pasta?)."
                        : found.size() + " teste(s) encontrado(s).");
            });
        });
    }

    private void runAll() {
        if (allTests.isEmpty()) {
            setStatus("Nenhum teste descoberto. Use Atualizar primeiro.");
            return;
        }
        runFilter(null, new ArrayList<>(allTests), "Rodando todos os testes...");
    }

    private void runSelected() {
        String testName = selectedTestName();
        if (testName == null) {
            setStatus("Selecione um teste na árvore.");
            return;
        }
        runFilter("FullyQualifiedName~" + filterValue(testName), List.of(testName),
                "Rodando " + testName + "...");
    }

    public void runTest(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isBlank()) {
            return;
        }
        runFilter("FullyQualifiedName~" + filterValue(fullyQualifiedName), List.of(fullyQualifiedName),
                "Rodando " + fullyQualifiedName + "...");
    }

    private void runFilter(String filter, List<String> scope, String message) {
        if (busy) {
            return;
        }
        Path project = projectSupplier == null ? null : projectSupplier.get();
        Optional<Path> dotnet = resolveDotnet(project);
        if (project == null || dotnet.isEmpty()) {
            setStatus("Toolchain .NET indisponível.");
            return;
        }
        setBusy(true, message);
        clearConsole();
        markRunning(scope);
        String configuration = config();
        worker.execute(() -> {
            int exit = -1;
            List<String> failed = new ArrayList<>();
            try {
                Process process = build.launchTest(project, dotnet.get(), configuration, filter);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String consoleLine = line;
                        SwingUtilities.invokeLater(() -> appendConsole(consoleLine + System.lineSeparator()));
                        Matcher matcher = RESULT_LINE.matcher(line.strip());
                        if (matcher.find()) {
                            String verb = matcher.group(1);
                            String name = matcher.group(2);
                            if ("Failed".equals(verb)) {
                                failed.add(name);
                                SwingUtilities.invokeLater(() -> applyStatus(name, Status.FAILED));
                            } else if ("Passed".equals(verb)) {
                                SwingUtilities.invokeLater(() -> applyStatus(name, Status.PASSED));
                            }
                        }
                    }
                }
                exit = process.waitFor();
            } catch (Exception ex) {
                log.debug("Falha ao executar testes: {}", ex.getMessage());
            }
            int finalExit = exit;
            SwingUtilities.invokeLater(() -> {
                finalizeRun(scope, failed, finalExit);
                setBusy(false, runSummary(failed, finalExit));
            });
        });
    }

    private void stop() {
        build.stopTests();
        setBusy(false, "Execução interrompida.");
    }

    private void populateTree(List<String> tests) {
        allTests.clear();
        allTests.addAll(tests);
        statuses.clear();
        for (String test : tests) {
            statuses.put(test, Status.UNKNOWN);
        }
        root.removeAllChildren();
        Map<String, DefaultMutableTreeNode> typeNodes = new LinkedHashMap<>();
        for (String full : tests) {
            String core = full.contains("(") ? full.substring(0, full.indexOf('(')) : full;
            int lastDot = core.lastIndexOf('.');
            String type = lastDot > 0 ? core.substring(0, lastDot) : "(global)";
            String method = lastDot >= 0 ? full.substring(lastDot + 1) : full;
            DefaultMutableTreeNode typeNode = typeNodes.computeIfAbsent(type, key -> {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(key);
                root.add(node);
                return node;
            });
            typeNode.add(new DefaultMutableTreeNode(new TestLeaf(full, method)));
        }
        treeModel.reload();
        expandAll();
    }

    private void markRunning(List<String> scope) {
        for (String test : scope) {
            String key = resolveTestKey(test);
            if (key != null) {
                statuses.put(key, Status.RUNNING);
            }
        }
        tree.repaint();
    }

    private void applyStatus(String name, Status state) {
        String key = resolveTestKey(name);
        if (key != null) {
            statuses.put(key, state);
            tree.repaint();
        }
    }

    private void finalizeRun(List<String> scope, List<String> failed, int exit) {
        Set<String> failedKeys = new HashSet<>();
        for (String name : failed) {
            String key = resolveTestKey(name);
            if (key != null) {
                failedKeys.add(key);
            }
        }
        for (String test : scope) {
            String key = resolveTestKey(test);
            if (key == null) {
                continue;
            }
            if (failedKeys.contains(key)) {
                statuses.put(key, Status.FAILED);
            } else if (exit == 0) {
                statuses.put(key, Status.PASSED);
            } else if (statuses.get(key) == Status.RUNNING) {
                statuses.put(key, Status.UNKNOWN);
            }
        }
        tree.repaint();
    }

    private String resolveTestKey(String name) {
        if (name == null) {
            return null;
        }
        if (statuses.containsKey(name)) {
            return name;
        }
        for (String test : allTests) {
            String core = test.contains("(") ? test.substring(0, test.indexOf('(')) : test;
            if (test.equals(name) || core.equals(name)) {
                return test;
            }
        }
        return null;
    }

    private String selectedTestName() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object node = path.getLastPathComponent();
        if (node instanceof DefaultMutableTreeNode treeNode && treeNode.getUserObject() instanceof TestLeaf leaf) {
            return leaf.fullName();
        }
        return null;
    }

    private Optional<Path> resolveDotnet(Path project) {
        DotnetSdkService sdk = sdkSupplier == null ? null : sdkSupplier.get();
        if (sdk == null || project == null) {
            return Optional.empty();
        }
        try {
            return sdk.getDotnetPath(project);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String config() {
        String configuration = configSupplier == null ? null : configSupplier.get();
        return configuration == null || configuration.isBlank() ? "Debug" : configuration;
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private void setBusy(boolean value, String message) {
        this.busy = value;
        refreshButton.setEnabled(!value);
        runAllButton.setEnabled(!value);
        runSelectedButton.setEnabled(!value);
        stopButton.setEnabled(value);
        if (message != null) {
            setStatus(message);
        }
    }

    private void setStatus(String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            status.setText(message);
        } else {
            SwingUtilities.invokeLater(() -> status.setText(message));
        }
    }

    private void appendConsole(String text) {
        console.append(text);
        console.setCaretPosition(console.getDocument().getLength());
    }

    private void clearConsole() {
        console.setText("");
    }

    private static String filterValue(String name) {
        return name.contains("(") ? name.substring(0, name.indexOf('(')) : name;
    }

    private static String runSummary(List<String> failed, int exit) {
        if (exit == 0) {
            return "Concluído: todos passaram.";
        }
        if (!failed.isEmpty()) {
            return "Concluído: " + failed.size() + " falha(s).";
        }
        if (exit < 0) {
            return "Falha ao executar (veja o console).";
        }
        return "Concluído com erros (código " + exit + ").";
    }

    private static String prefix(Status state) {
        return switch (state) {
            case PASSED -> "✓ ";
            case FAILED -> "✗ ";
            case RUNNING -> "… ";
            default -> "• ";
        };
    }

    private static Color color(Status state) {
        return switch (state) {
            case PASSED -> new Color(0x2E7D32);
            case FAILED -> new Color(0xC62828);
            case RUNNING -> new Color(0x1565C0);
            default -> null;
        };
    }

    private record TestLeaf(String fullName, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final class StatusRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree treeComponent, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(treeComponent, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof TestLeaf testLeaf) {
                Status state = statuses.getOrDefault(testLeaf.fullName(), Status.UNKNOWN);
                setText(prefix(state) + testLeaf.label());
                if (!selected) {
                    Color foreground = color(state);
                    if (foreground != null) {
                        setForeground(foreground);
                    }
                }
            }
            return this;
        }
    }
}
