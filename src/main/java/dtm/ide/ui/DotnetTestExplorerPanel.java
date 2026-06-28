package dtm.ide.ui;

import dtm.ide.run.DotnetBuild;
import dtm.ide.sdk.DotnetSdkService;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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
import java.util.Locale;
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
    private static final Pattern WARNING_LINE = Pattern.compile(":\\s*warning\\s+[A-Z]{2}\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIN_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\s\"]+");
    private static final Pattern FAIL_COUNT =
            Pattern.compile("(?:com falha|failed)\\D*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final int RUN_ICON_WIDTH = 22;

    private static final Color C_ACCENT = new Color(0x4FB6FF);
    private static final Color C_MUTED = new Color(0x8A8F99);
    private static final Color C_PASS = new Color(0x59C36A);
    private static final Color C_FAIL = new Color(0xF14C4C);

    private final Supplier<Path> projectSupplier;
    private final Supplier<DotnetSdkService> sdkSupplier;
    private final Supplier<String> configSupplier;
    private final DotnetBuild build = new DotnetBuild();

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Testes");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final JTextPane console = new JTextPane() {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            Component parent = getParent();
            return parent == null || getUI().getPreferredSize(this).width < parent.getWidth();
        }
    };
    private final JLabel status = new JLabel("Pronto.");
    private final JLabel passedLabel = new JLabel("0", new StatusIcon(Status.PASSED, 13), SwingConstants.LEADING);
    private final JLabel failedLabel = new JLabel("0", new StatusIcon(Status.FAILED, 13), SwingConstants.LEADING);
    private final JLabel runningLabel = new JLabel("0", new StatusIcon(Status.RUNNING, 13), SwingConstants.LEADING);
    private final JLabel totalLabel = new JLabel();
    private final JPanel summary = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    private final Map<String, Status> statuses = new LinkedHashMap<>();
    private final List<String> allTests = new ArrayList<>();

    private final JButton refreshButton = new JButton("Atualizar");
    private final JButton runAllButton = new JButton("Rodar todos", new PlayIcon(11, new Color(0x4CAF50)));
    private final JButton runSelectedButton = new JButton("Rodar selecionado");
    private final JButton stopButton = new JButton("Parar");

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dotnet-test-explorer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean busy;
    private boolean autoDiscovered;

    public DotnetTestExplorerPanel(Supplier<Path> projectSupplier, Supplier<DotnetSdkService> sdkSupplier,
                                   Supplier<String> configSupplier) {
        super(new BorderLayout());
        this.projectSupplier = projectSupplier;
        this.sdkSupplier = sdkSupplier;
        this.configSupplier = configSupplier;
        buildUi();
        addHierarchyListener(e -> {
            if (isShowing()) {
                maybeAutoDiscover();
            }
        });
    }

    private void buildUi() {
        setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(runAllButton);
        toolbar.add(runSelectedButton);
        toolbar.add(stopButton);
        toolbar.add(refreshButton);
        toolbar.add(Box.createHorizontalStrut(12));
        summary.setOpaque(false);
        passedLabel.setToolTipText("Passaram");
        failedLabel.setToolTipText("Falharam");
        runningLabel.setToolTipText("Em execução");
        totalLabel.setForeground(mutedColor());
        summary.add(passedLabel);
        summary.add(failedLabel);
        summary.add(runningLabel);
        summary.add(totalLabel);
        toolbar.add(summary);
        refreshButton.addActionListener(e -> refresh());
        runAllButton.addActionListener(e -> runAll());
        runSelectedButton.addActionListener(e -> runSelected());
        stopButton.addActionListener(e -> stop());
        stopButton.setEnabled(false);
        runAllButton.setToolTipText("Executa todos os testes do projeto");
        refreshButton.setToolTipText("Redescobre os testes (dotnet test --list-tests)");

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(22);
        tree.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tree.setCellRenderer(new RowRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (isOnRunIcon(e)) {
                    runNode(tree.getPathForLocation(e.getX(), e.getY()));
                    return;
                }
                if (e.getClickCount() == 2) {
                    runSelected();
                }
            }
        });
        tree.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                tree.setCursor(isOnRunIcon(e)
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        });

        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        UiSupport.styleScroll(treeScroll);
        JScrollPane consoleScroll = new JScrollPane(console);
        consoleScroll.setBorder(BorderFactory.createEmptyBorder());
        UiSupport.styleScroll(consoleScroll);

        JPanel treePanel = new JPanel(new BorderLayout());
        treePanel.add(sectionHeader("Testes"), BorderLayout.NORTH);
        treePanel.add(treeScroll, BorderLayout.CENTER);

        JPanel consolePanel = new JPanel(new BorderLayout());
        consolePanel.add(sectionHeader("Saída"), BorderLayout.NORTH);
        consolePanel.add(consoleScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, consolePanel);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setResizeWeight(0.5);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        statusBar.add(status, BorderLayout.WEST);

        add(toolbar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
        updateSummary();
    }

    private JLabel sectionHeader(String text) {
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        Font base = label.getFont();
        label.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - 1f));
        label.setForeground(mutedColor());
        return label;
    }

    private void maybeAutoDiscover() {
        SwingUtilities.invokeLater(this::discoverIfNeeded);
    }

    public void discoverIfNeeded() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::discoverIfNeeded);
            return;
        }
        if (autoDiscovered || busy || !allTests.isEmpty()) {
            return;
        }
        Path project = projectSupplier == null ? null : projectSupplier.get();
        if (project == null || resolveDotnet(project).isEmpty()) {
            return;
        }
        refresh();
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
        autoDiscovered = true;
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
        TreePath path = tree.getSelectionPath();
        if (path != null) {
            runNode(path);
            return;
        }
        setStatus("Selecione um teste na árvore.");
    }

    private void runNode(TreePath path) {
        if (path == null) {
            return;
        }
        Object node = path.getLastPathComponent();
        if (!(node instanceof DefaultMutableTreeNode treeNode)) {
            return;
        }
        if (treeNode == root) {
            runAll();
            return;
        }
        if (treeNode.getUserObject() instanceof TestLeaf leaf) {
            runTest(leaf.fullName());
            return;
        }
        List<String> scope = leavesOf(treeNode);
        if (scope.isEmpty()) {
            return;
        }
        String type = String.valueOf(treeNode.getUserObject());
        runFilter("FullyQualifiedName~" + filterValue(type), scope, "Rodando " + shortName(type) + "...");
    }

    public void runTest(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isBlank()) {
            return;
        }
        ensureTestVisible(fullyQualifiedName);
        runFilter("FullyQualifiedName~" + filterValue(fullyQualifiedName), List.of(fullyQualifiedName),
                "Rodando " + shortName(fullyQualifiedName) + "...");
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
        appendConsole("> dotnet test" + (filter == null ? "" : " --filter \"" + filter + "\"")
                + System.lineSeparator() + System.lineSeparator());
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
                        if (!WARNING_LINE.matcher(line).find()) {
                            String consoleLine = shortenPaths(prettifyTestLine(line));
                            SwingUtilities.invokeLater(() -> appendConsole(consoleLine + System.lineSeparator()));
                        }
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
            insertTest(full, typeNodes);
        }
        treeModel.reload();
        expandAll();
        updateSummary();
    }

    private void ensureTestVisible(String full) {
        if (allTests.contains(full)) {
            return;
        }
        allTests.add(full);
        statuses.put(full, Status.UNKNOWN);
        Map<String, DefaultMutableTreeNode> typeNodes = new LinkedHashMap<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            typeNodes.put(String.valueOf(child.getUserObject()), child);
        }
        insertTest(full, typeNodes);
        treeModel.reload();
        expandAll();
        updateSummary();
    }

    private void insertTest(String full, Map<String, DefaultMutableTreeNode> typeNodes) {
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

    private List<String> leavesOf(DefaultMutableTreeNode node) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            Object child = node.getChildAt(i);
            if (child instanceof DefaultMutableTreeNode treeNode && treeNode.getUserObject() instanceof TestLeaf leaf) {
                result.add(leaf.fullName());
            }
        }
        return result;
    }

    private void markRunning(List<String> scope) {
        for (String test : scope) {
            String key = resolveTestKey(test);
            if (key != null) {
                statuses.put(key, Status.RUNNING);
            }
        }
        tree.repaint();
        updateSummary();
    }

    private void applyStatus(String name, Status state) {
        String key = resolveTestKey(name);
        if (key != null) {
            statuses.put(key, state);
            tree.repaint();
            updateSummary();
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
        updateSummary();
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

    private boolean isOnRunIcon(MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) {
            return false;
        }
        Rectangle bounds = tree.getPathBounds(path);
        return bounds != null && e.getX() >= bounds.x && e.getX() <= bounds.x + RUN_ICON_WIDTH;
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

    private void updateSummary() {
        int passed = 0;
        int failed = 0;
        int running = 0;
        for (Status state : statuses.values()) {
            switch (state) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case RUNNING -> running++;
                default -> {
                }
            }
        }
        boolean any = !statuses.isEmpty();
        passedLabel.setVisible(any);
        failedLabel.setVisible(any);
        runningLabel.setVisible(any && running > 0);
        totalLabel.setVisible(any);
        passedLabel.setText(String.valueOf(passed));
        failedLabel.setText(String.valueOf(failed));
        runningLabel.setText(String.valueOf(running));
        totalLabel.setText("Total: " + statuses.size());
        summary.revalidate();
        summary.repaint();
    }

    private void appendConsole(String text) {
        StyledDocument doc = console.getStyledDocument();
        SimpleAttributeSet attr = new SimpleAttributeSet();
        Color color = classifyColor(text);
        if (color != null) {
            StyleConstants.setForeground(attr, color);
        }
        StyleConstants.setBold(attr, isSummaryLine(text.strip()));
        try {
            doc.insertString(doc.getLength(), text, attr);
        } catch (BadLocationException ignored) {
        }
        console.setCaretPosition(doc.getLength());
    }

    private void clearConsole() {
        console.setText("");
    }

    private static Color classifyColor(String text) {
        String s = text.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith(">")) {
            return C_ACCENT;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (isSummaryLine(s)) {
            return summaryHasFailures(s) ? C_FAIL : C_PASS;
        }
        if (lower.startsWith("[erro]") || lower.contains("error ") || lower.contains(": error")
                || lower.startsWith("failed ") || lower.startsWith("reprovado")
                || lower.contains("exception") || lower.contains("stack trace")) {
            return C_FAIL;
        }
        if (lower.contains(" -> ") || lower.startsWith("determinando") || lower.startsWith("determining")
                || lower.startsWith("todos os projetos") || lower.startsWith("all projects")
                || lower.startsWith("execução de teste") || lower.startsWith("test run for")
                || lower.contains("arquivos de teste") || lower.contains("test files")
                || lower.startsWith("restaurado") || lower.startsWith("restored")) {
            return C_MUTED;
        }
        return null;
    }

    private static boolean isSummaryLine(String stripped) {
        return stripped.contains("Total:")
                && (stripped.contains(" ms") || stripped.toLowerCase(Locale.ROOT).contains("dura"));
    }

    private static boolean summaryHasFailures(String line) {
        Matcher matcher = FAIL_COUNT.matcher(line);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1)) > 0;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    private static String shortenPaths(String line) {
        Matcher matcher = WIN_PATH.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group();
            int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(name));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String prettifyTestLine(String line) {
        String stripped = line.strip();
        if (!isSummaryLine(stripped) || !stripped.contains(" - ")) {
            return line;
        }
        int dash = stripped.lastIndexOf(" - ");
        if (dash > 0) {
            String tail = stripped.substring(dash + 3);
            if (tail.contains(".dll") || tail.contains("(")) {
                stripped = stripped.substring(0, dash);
            }
        }
        return stripped.replaceAll("\\s{2,}", " ");
    }

    private static String filterValue(String name) {
        return name.contains("(") ? name.substring(0, name.indexOf('(')) : name;
    }

    private static String shortName(String qualified) {
        String core = qualified.contains("(") ? qualified.substring(0, qualified.indexOf('(')) : qualified;
        int dot = core.lastIndexOf('.');
        return dot >= 0 && dot + 1 < core.length() ? core.substring(dot + 1) : core;
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

    private static Color color(Status state) {
        return switch (state) {
            case PASSED -> new Color(0x2E7D32);
            case FAILED -> new Color(0xC62828);
            case RUNNING -> new Color(0x1565C0);
            default -> new Color(0x9E9E9E);
        };
    }

    private static Color mutedColor() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(0x888888);
    }

    private Status aggregateStatus(DefaultMutableTreeNode node) {
        boolean anyFailed = false;
        boolean anyRunning = false;
        boolean allPassed = true;
        boolean any = false;
        for (String test : leavesOf(node)) {
            any = true;
            Status state = statuses.getOrDefault(test, Status.UNKNOWN);
            if (state == Status.FAILED) {
                anyFailed = true;
            } else if (state == Status.RUNNING) {
                anyRunning = true;
            }
            if (state != Status.PASSED) {
                allPassed = false;
            }
        }
        if (!any) {
            return Status.UNKNOWN;
        }
        if (anyFailed) {
            return Status.FAILED;
        }
        if (anyRunning) {
            return Status.RUNNING;
        }
        return allPassed ? Status.PASSED : Status.UNKNOWN;
    }

    private record TestLeaf(String fullName, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final class RowRenderer extends JPanel implements TreeCellRenderer {
        private final JLabel runIcon = new JLabel(new PlayIcon(11, new Color(0x4CAF50)), SwingConstants.CENTER);
        private final JLabel content = new JLabel();

        RowRenderer() {
            super(new BorderLayout());
            setOpaque(true);
            runIcon.setPreferredSize(new Dimension(RUN_ICON_WIDTH, 18));
            content.setOpaque(false);
            content.setIconTextGap(6);
            content.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 4));
            add(runIcon, BorderLayout.WEST);
            add(content, BorderLayout.CENTER);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree treeComponent, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            Color background = selected
                    ? UIManager.getColor("Tree.selectionBackground")
                    : UIManager.getColor("Tree.background");
            setBackground(background);
            Color textColor = selected ? UIManager.getColor("Tree.selectionForeground") : null;

            DefaultMutableTreeNode node = value instanceof DefaultMutableTreeNode dm ? dm : null;
            Status state = Status.UNKNOWN;
            boolean runnable = false;
            boolean showStatus = true;
            String text;
            if (node != null && node.getUserObject() instanceof TestLeaf testLeaf) {
                state = statuses.getOrDefault(testLeaf.fullName(), Status.UNKNOWN);
                text = testLeaf.label();
                runnable = true;
            } else if (node == root) {
                state = aggregateStatus(node);
                text = String.valueOf(node.getUserObject());
                runnable = !allTests.isEmpty();
                showStatus = false;
            } else if (node != null) {
                state = aggregateStatus(node);
                text = shortName(String.valueOf(node.getUserObject()));
                runnable = true;
            } else {
                text = String.valueOf(value);
                showStatus = false;
            }

            content.setText(text);
            content.setIcon(showStatus ? new StatusIcon(state, 13) : null);
            content.setForeground(textColor != null ? textColor : color(state));
            runIcon.setVisible(runnable);
            return this;
        }
    }

    private static final class PlayIcon implements Icon {
        private final int size;
        private final Color color;

        PlayIcon(int size, Color color) {
            this.size = size;
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            int pad = Math.max(1, size / 8);
            int left = x + pad;
            int right = x + size - pad;
            int top = y + pad;
            int bottom = y + size - pad;
            int[] xs = {left, left, right};
            int[] ys = {top, bottom, y + size / 2};
            g2.fillPolygon(xs, ys, 3);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private static final class StatusIcon implements Icon {
        private final Status state;
        private final int size;

        StatusIcon(Status state, int size) {
            this.state = state;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = color(state);
            int d = size - 2;
            int cx = x + 1;
            int cy = y + 1;
            if (state == Status.UNKNOWN) {
                g2.setStroke(new BasicStroke(1.4f));
                g2.setColor(base);
                g2.drawOval(cx, cy, d, d);
                g2.dispose();
                return;
            }
            g2.setColor(base);
            g2.fillOval(cx, cy, d, d);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(Math.max(1.4f, size / 7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (state == Status.PASSED) {
                g2.drawPolyline(
                        new int[]{x + size * 28 / 100, x + size * 43 / 100, x + size * 73 / 100},
                        new int[]{y + size * 52 / 100, y + size * 67 / 100, y + size * 33 / 100}, 3);
            } else if (state == Status.FAILED) {
                int a = size * 32 / 100;
                int b = size * 68 / 100;
                g2.drawLine(x + a, y + a, x + b, y + b);
                g2.drawLine(x + b, y + a, x + a, y + b);
            } else if (state == Status.RUNNING) {
                g2.fillRect(x + size * 36 / 100, y + size * 36 / 100, size * 28 / 100, size * 28 / 100);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
