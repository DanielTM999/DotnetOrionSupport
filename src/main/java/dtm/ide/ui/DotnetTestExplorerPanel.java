package dtm.ide.ui;

import dtm.ide.test.DotnetTestService;
import dtm.ide.test.TestResult;
import dtm.ide.coverage.CoverageReport;
import dtm.ide.coverage.CoberturaReportReader;
import java.nio.file.Files;
import java.util.function.Consumer;
import javax.swing.JFileChooser;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.table.DefaultTableModel;
import dtm.ide.sdk.DotnetSdkService;
import dtm.stools.i18n.I18n;
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
import javax.swing.JTextField;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class DotnetTestExplorerPanel extends JPanel {

    private enum Status { UNKNOWN, RUNNING, PASSED, FAILED, SKIPPED }

    private static String text(String key, String def) {
        return I18n.getText(DotnetTestExplorerPanel.class, key, def);
    }

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
    private final LongConsumer debugProcessHandler;
    private final DotnetTestService tests = new DotnetTestService();
    private Consumer<CoverageReport> coverageSink = ignored -> {};
    private Consumer<List<TestResult>> resultSink = ignored -> {};
    private Runnable configureCoverage = () -> {};
    private Supplier<dtm.ide.project.WorkspaceTarget> targetSelection = () -> new dtm.ide.project.WorkspaceTarget(null, config(), "");
    private java.util.function.BiConsumer<Path, Integer> navigation = (path, line) -> {};
    private List<TestResult> lastResults = List.of();
    private final DefaultTableModel resultsModel = new DefaultTableModel(new String[]{
            text("result.test", "Test"), text("result.outcome", "Outcome"), text("result.duration", "Duration"),
            text("result.report", "Report")}, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable resultsTable = new JTable(resultsModel);
    private final javax.swing.JTextArea details = new javax.swing.JTextArea();
    private final JButton rerunFailedButton = new JButton(text("button.rerunFailed", "Rerun failed"));
    private final JButton coverageButton = new JButton(text("button.coverage", "Run with coverage"));
    private final JButton importCoverageButton = new JButton(text("button.importCoverage", "Import coverage..."));
    private final JButton clearCoverageButton = new JButton(text("button.clearCoverage", "Clear coverage"));
    private final JButton configureCoverageButton = new JButton(text("button.configureCoverage", "Configure coverage..."));

    public void setCoverageSink(Consumer<CoverageReport> sink) { coverageSink = sink; }
    public void setResultSink(Consumer<List<TestResult>> sink) { resultSink = sink; }
    public void setConfigureCoverage(Runnable action) { configureCoverage = action; }
    public void setTargetSelection(Supplier<dtm.ide.project.WorkspaceTarget> supplier) { targetSelection = supplier; }
    public void setNavigation(java.util.function.BiConsumer<Path, Integer> sink) { navigation = sink; }

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode(text("tree.root", "Tests"));
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final JTextPane console = new JTextPane() {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            Component parent = getParent();
            return parent == null || getUI().getPreferredSize(this).width < parent.getWidth();
        }
    };
    private final JLabel status = new JLabel(text("status.ready", "Ready."));
    private final JLabel passedLabel = new JLabel("0", new StatusIcon(Status.PASSED, 13), SwingConstants.LEADING);
    private final JLabel failedLabel = new JLabel("0", new StatusIcon(Status.FAILED, 13), SwingConstants.LEADING);
    private final JLabel runningLabel = new JLabel("0", new StatusIcon(Status.RUNNING, 13), SwingConstants.LEADING);
    private final JLabel totalLabel = new JLabel();
    private final JPanel summary = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    private final JPanel treeCards = new JPanel(new java.awt.CardLayout());
    private final Map<String, Status> statuses = new LinkedHashMap<>();
    private final List<String> allTests = new ArrayList<>();

    private final JButton refreshButton = new JButton(text("button.refresh", "Refresh"));
    private final JButton runAllButton = new JButton(text("button.runAll", "Run all"), new PlayIcon(11, new Color(0x4CAF50)));
    private final JButton runSelectedButton = new JButton(text("button.runSelected", "Run selected"));
    private final JButton debugSelectedButton = new JButton(text("button.debugSelected", "Debug selected"));
    private final JButton stopButton = new JButton(text("button.stop", "Stop"));
    private final JTextField filterField = new JTextField(20);

    private final java.util.concurrent.atomic.AtomicLong operation = new java.util.concurrent.atomic.AtomicLong();
    private volatile java.util.concurrent.Future<?> pending;
    private ExecutorService worker = newWorker();

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dotnet-test-explorer");
        t.setDaemon(true);
        return t;
        });
    }

    public void resetProject() {
        operation.incrementAndGet();
        if (pending != null) pending.cancel(true);
        tests.stop();
        worker.shutdownNow();
        worker = newWorker();
        autoDiscovered = false;
        lastResults = List.of();
        resultSink.accept(lastResults);
        resultsModel.setRowCount(0);
        details.setText("");
        statuses.clear();
        populateTree(List.of());
        clearConsole();
        setBusy(false, text("status.ready", "Ready."));
    }

    private void post(long ticket, Runnable update) {
        SwingUtilities.invokeLater(() -> {
            if (ticket == operation.get()) update.run();
        });
    }
    private volatile boolean busy;
    private boolean autoDiscovered;

    public DotnetTestExplorerPanel(Supplier<Path> projectSupplier, Supplier<DotnetSdkService> sdkSupplier,
                                   Supplier<String> configSupplier) {
        this(projectSupplier, sdkSupplier, configSupplier, null);
    }

    public DotnetTestExplorerPanel(Supplier<Path> projectSupplier, Supplier<DotnetSdkService> sdkSupplier,
                                   Supplier<String> configSupplier, LongConsumer debugProcessHandler) {
        super(new BorderLayout());
        this.projectSupplier = projectSupplier;
        this.sdkSupplier = sdkSupplier;
        this.configSupplier = configSupplier;
        this.debugProcessHandler = debugProcessHandler;
        buildUi();
        addHierarchyListener(e -> {
            if (isShowing()) {
                maybeAutoDiscover();
            }
        });
    }

    private void buildUi() {
        setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));

        JPanel toolbar = new JPanel(new WrappingFlowLayout());
        toolbar.add(runAllButton);
        toolbar.add(runSelectedButton);
        toolbar.add(debugSelectedButton);
        toolbar.add(rerunFailedButton);
        toolbar.add(coverageButton);
        toolbar.add(importCoverageButton);
        toolbar.add(clearCoverageButton);
        toolbar.add(configureCoverageButton);
        toolbar.add(stopButton);
        toolbar.add(refreshButton);
        JPanel filterControls = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        filterControls.add(new JLabel(text("label.filter", "Filter:")));
        filterField.putClientProperty("JTextField.placeholderText", text("filter.placeholder", "FullyQualifiedName~... or trait"));
        filterControls.add(filterField);
        toolbar.add(filterControls);
        toolbar.add(Box.createHorizontalStrut(12));
        summary.setOpaque(false);
        passedLabel.setToolTipText(text("tooltip.passed", "Passed"));
        failedLabel.setToolTipText(text("tooltip.failed", "Failed"));
        runningLabel.setToolTipText(text("tooltip.running", "Running"));
        totalLabel.setForeground(mutedColor());
        summary.add(passedLabel);
        summary.add(failedLabel);
        summary.add(runningLabel);
        summary.add(totalLabel);
        toolbar.add(summary);
        refreshButton.addActionListener(e -> refresh());
        runAllButton.addActionListener(e -> runAll());
        rerunFailedButton.addActionListener(e -> rerunFailed());
        coverageButton.addActionListener(e -> runFilter(filterField.getText().strip(), List.copyOf(allTests),
                text("status.runningCoverage", "Running tests with coverage..."), false, true));
        importCoverageButton.addActionListener(e -> importCoverage());
        clearCoverageButton.addActionListener(e -> coverageSink.accept(new CoverageReport(Map.of())));
        configureCoverageButton.addActionListener(e -> configureCoverage.run());
        runSelectedButton.addActionListener(e -> runSelected());
        debugSelectedButton.addActionListener(e -> debugSelected());
        stopButton.addActionListener(e -> stop());
        stopButton.setEnabled(false);
        runAllButton.setToolTipText(text("tooltip.runAll", "Runs all tests in the project"));
        refreshButton.setToolTipText(text("tooltip.refresh", "Rediscovers tests (dotnet test --list-tests)"));

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
        treePanel.add(sectionHeader(text("tree.root", "Tests")), BorderLayout.NORTH);
        JLabel empty = new JLabel("<html><center>" + text("tree.empty", "Use Refresh to discover tests, or Run all.") + "</center></html>", SwingConstants.CENTER);
        treeCards.add(empty, "empty"); treeCards.add(treeScroll, "tests");
        treePanel.add(treeCards, BorderLayout.CENTER);
        treePanel.setMinimumSize(new Dimension(180, 0));
        treePanel.setPreferredSize(new Dimension(280, 250));

        JPanel consolePanel = new JPanel(new BorderLayout());
        consolePanel.setMinimumSize(new Dimension(220, 0));
        consolePanel.setPreferredSize(new Dimension(360, 250));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(text("section.output", "Output"), consoleScroll);
        details.setEditable(false);
        details.setLineWrap(true);
        resultsTable.getSelectionModel().addListSelectionListener(event -> {
            int row = resultsTable.getSelectedRow();
            if (row >= 0 && row < lastResults.size()) {
                var result = lastResults.get(row);
                details.setText(result.displayName() + "\n" + result.message() + "\n" + result.stackTrace());
            }
        });
        resultsTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) navigateToFailure();
            }
        });
        var resultSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(resultsTable), new JScrollPane(details));
        resultSplit.setResizeWeight(.6);
        tabs.addTab(text("section.results", "Results"), resultSplit);
        consolePanel.add(tabs, BorderLayout.CENTER);

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
            setStatus(text("status.toolchainUnavailableOpen", ".NET toolchain unavailable. Open a .NET project."));
            return;
        }
        autoDiscovered = true;
        setBusy(true, text("status.discovering", "Discovering tests..."));
        clearConsole();
        appendConsole("> dotnet test --list-tests" + System.lineSeparator());
        var selected = targetSelection.get();
        String configuration = selected.configuration();
        long ticket = operation.incrementAndGet();
        pending = worker.submit(() -> {
            List<String> tests;
            try {
                var discovery = this.tests.discoverDetailed(project, dotnet.get(), configuration, selected.target(), selected.framework());
                tests = discovery.names();
                post(ticket, () -> appendConsole(discovery.output()));
            } catch (Exception ex) {
                log.debug("Falha ao listar testes: {}", ex.getMessage());
                post(ticket, () -> {
                    autoDiscovered = false;
                    appendConsole(ex.getMessage() + System.lineSeparator());
                    setBusy(false, text("status.discoveryFailed", "Test discovery failed. See output."));
                });
                return;
            }
            List<String> found = tests;
            post(ticket, () -> {
                populateTree(found);
                setBusy(false, found.isEmpty()
                        ? text("status.noTestsFound", "No tests found (is there a test project in the folder?).")
                        : text("status.testsFound", "{0} test(s) found.").replace("{0}", String.valueOf(found.size())));
            });
        });
    }

    private void runAll() {
        String filter = filterField.getText() == null ? "" : filterField.getText().trim();
        runFilter(filter.isEmpty() ? null : filter,
                filter.isEmpty() ? new ArrayList<>(allTests) : List.of(), text("status.runningAll", "Running all tests..."));
    }

    private void runSelected() {
        TreePath path = tree.getSelectionPath();
        if (path != null) {
            runNode(path);
            return;
        }
        setStatus(text("status.selectTree", "Select a test in the tree."));
    }

    private void debugSelected() {
        TreePath path = tree.getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof TestLeaf leaf)) {
            setStatus(text("status.selectIndividual", "Select an individual test to debug."));
            return;
        }
        String test = leaf.fullName();
        runFilter("FullyQualifiedName~" + filterValue(test), List.of(test),
                text("status.waitingDebugger", "Waiting for debugger for {0}...").replace("{0}", shortName(test)), true);
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
        String filter = "(global)".equals(type)
                ? scope.stream().map(DotnetTestExplorerPanel::filterValue).filter(name -> !name.isBlank())
                    .distinct().map(name -> "FullyQualifiedName~" + name).collect(java.util.stream.Collectors.joining("|"))
                : "FullyQualifiedName~" + filterValue(type);
        runFilter(filter, scope,
                text("status.runningNode", "Running {0}...").replace("{0}", shortName(type)));
    }

    public void runTest(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isBlank()) {
            return;
        }
        ensureTestVisible(fullyQualifiedName);
        runFilter("FullyQualifiedName~" + filterValue(fullyQualifiedName), List.of(fullyQualifiedName),
                text("status.runningNode", "Running {0}...").replace("{0}", shortName(fullyQualifiedName)));
    }

    public void debugTest(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isBlank()) {
            return;
        }
        if (debugProcessHandler == null) {
            setStatus(text("status.debugUnavailable", "Debug is unavailable."));
            return;
        }
        ensureTestVisible(fullyQualifiedName);
        runFilter("FullyQualifiedName~" + filterValue(fullyQualifiedName), List.of(fullyQualifiedName),
                text("status.waitingDebugger", "Waiting for debugger for {0}...").replace("{0}", shortName(fullyQualifiedName)),
                true);
    }

    private void runFilter(String filter, List<String> scope, String message) {
        runFilter(filter, scope, message, false);
    }

    private void runFilter(String filter, List<String> scope, String message, boolean debug) {
        runFilter(filter, scope, message, debug, false);
    }

    private void runFilter(String filter, List<String> scope, String message, boolean debug, boolean coverage) {
        if (busy) {
            return;
        }
        Path project = projectSupplier == null ? null : projectSupplier.get();
        Optional<Path> dotnet = resolveDotnet(project);
        if (project == null || dotnet.isEmpty()) {
            setStatus(text("status.toolchainUnavailable", ".NET toolchain unavailable."));
            return;
        }
        setBusy(true, message);
        clearConsole();
        markRunning(scope);
        var selected = targetSelection.get();
        String configuration = selected.configuration();
        long ticket = operation.incrementAndGet();
        pending = worker.submit(() -> {
            int exit = -1;
            try {
                var run = tests.start(project, dotnet.get(), configuration, filter, debug, coverage, selected.target(), selected.framework());
                String command = run.command().stream().map(arg -> arg.chars().anyMatch(Character::isWhitespace) ? "\"" + arg + "\"" : arg)
                        .collect(java.util.stream.Collectors.joining(" "));
                post(ticket, () -> appendConsole("> " + command + System.lineSeparator()));
                Process process = run.process();
                if (ticket != operation.get() || Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    return;
                }
                boolean debuggerAttached = false;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (debug && !debuggerAttached) {
                            Long pid = debugProcessId(line);
                            if (pid != null && debugProcessHandler != null) {
                                debuggerAttached = true;
                                if (ticket == operation.get()) debugProcessHandler.accept(pid);
                            }
                        }
                        String consoleLine = line;
                        post(ticket, () -> appendConsole(consoleLine + System.lineSeparator()));
                    }
                }
                exit = process.waitFor();
                var report = tests.read(run, project);
                int finalExit = exit;
                post(ticket, () -> {
                    applyResults(report.tests(), scope);
                    report.warnings().forEach(warning -> appendConsole(warning + System.lineSeparator()));
                    if (coverage) {
                        coverageSink.accept(report.coverage());
                        appendConsole(report.coverage().lines() == 0
                                ? text("coverage.missing", "No coverage report. Configure a coverage collector in the test project or import an existing Cobertura report.") + "\n"
                                : String.format(java.util.Locale.ROOT, "%.1f%% (%d/%d)\n", report.coverage().percent(), report.coverage().covered(), report.coverage().lines()));
                    }
                    long failures = report.tests().stream().filter(r -> r.outcome() == TestResult.Outcome.FAILED).count();
                    setBusy(false, report.tests().size() + " " + text("result.test", "Test") + "; "
                            + failures + " " + text("result.failed", "failed") + "; exit=" + finalExit);
                });
            } catch (Exception ex) {
                log.debug("Falha ao executar testes: {}", ex.getMessage());
                post(ticket, () -> {
                    scope.forEach(name -> applyStatus(name, Status.UNKNOWN));
                    appendConsole(ex.getMessage() + System.lineSeparator());
                    setBusy(false, text("status.executionFailed", "Test execution failed. See output."));
                });
            }
        });
    }

    private void stop() {
        operation.incrementAndGet();
        if (pending != null) pending.cancel(true);
        tests.stop();
        statuses.replaceAll((name, value) -> value == Status.RUNNING ? Status.UNKNOWN : value);
        tree.repaint(); updateSummary();
        setBusy(false, text("status.stopped", "Run stopped."));
    }

    private void populateTree(List<String> tests) {
        allTests.clear();
        allTests.addAll(tests);
        ((java.awt.CardLayout) treeCards.getLayout()).show(treeCards, tests.isEmpty() ? "empty" : "tests");
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
        ((java.awt.CardLayout) treeCards.getLayout()).show(treeCards, "tests");
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

    private void applyResults(List<TestResult> results, List<String> scope) {
        lastResults = List.copyOf(results);
        resultSink.accept(lastResults);
        details.setText("");
        resultsModel.setRowCount(0);
        for (var result : results) resultsModel.addRow(new Object[]{result.displayName(), text("outcome." + result.outcome(), result.outcome().toString()), result.duration(), result.report()});
        scope.forEach(name -> applyStatus(name, Status.UNKNOWN));
        Map<String, Status> aggregate = new LinkedHashMap<>();
        for (var result : results) {
            String key = resolveTestKey(result.displayName());
            if (key == null) key = resolveTestKey(result.name());
            if (key == null) { ensureTestVisible(result.name()); key = result.name(); }
            Status next = switch (result.outcome()) {
                case PASSED -> Status.PASSED;
                case FAILED -> Status.FAILED;
                case SKIPPED -> Status.SKIPPED;
                case UNKNOWN -> Status.UNKNOWN;
            };
            aggregate.merge(key, next, (old, value) -> old == Status.FAILED || value == Status.FAILED ? Status.FAILED
                    : old == Status.UNKNOWN || value == Status.UNKNOWN ? Status.UNKNOWN
                    : old == Status.SKIPPED || value == Status.SKIPPED ? Status.SKIPPED : Status.PASSED);
        }
        aggregate.forEach(statuses::put);
        tree.repaint();
        updateSummary();
    }

    private void rerunFailed() {
        var failed = lastResults.stream().filter(r -> r.outcome() == TestResult.Outcome.FAILED)
                .map(TestResult::name).distinct().toList();
        if (failed.isEmpty()) return;
        String filter = failed.stream().map(name -> "FullyQualifiedName=" + filterValue(name))
                .collect(java.util.stream.Collectors.joining("|"));
        runFilter(filter, failed, text("status.rerunning", "Rerunning failed tests..."));
    }

    private void importCoverage() {
        Path project = projectSupplier.get();
        if (project == null || busy) return;
        JFileChooser chooser = new JFileChooser(project.toFile());
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Cobertura XML", "xml"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path file = chooser.getSelectedFile().toPath();
        long ticket = operation.incrementAndGet();
        setBusy(true, text("coverage.loading", "Loading coverage..."));
        pending = worker.submit(() -> {
            try {
                var report = new CoberturaReportReader().read(file, project);
                post(ticket, () -> {
                    coverageSink.accept(report);
                    setBusy(false, String.format(java.util.Locale.ROOT, "%.1f%% (%d/%d)", report.percent(), report.covered(), report.lines()));
                });
            } catch (Exception error) {
                post(ticket, () -> setBusy(false, error.getMessage()));
            }
        });
    }

    private void navigateToFailure() {
        int row = resultsTable.getSelectedRow();
        if (row < 0 || row >= lastResults.size()) return;
        dtm.ide.test.TestProblems.source(lastResults.get(row), projectSupplier.get())
                .ifPresent(source -> navigation.accept(source.file(), source.line() - 1));
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
        debugSelectedButton.setEnabled(!value && debugProcessHandler != null);
        rerunFailedButton.setEnabled(!value && lastResults.stream().anyMatch(r -> r.outcome() == TestResult.Outcome.FAILED));
        coverageButton.setEnabled(!value);
        importCoverageButton.setEnabled(!value);
        configureCoverageButton.setEnabled(!value);
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
        totalLabel.setText(text("label.total", "Total: ") + statuses.size());
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

    private static String filterValue(String name) {
        return name.contains("(") ? name.substring(0, name.indexOf('(')) : name;
    }

    static Long debugProcessId(String line) { return DotnetTestService.debugProcessId(line); }

    private static String shortName(String qualified) {
        String core = qualified.contains("(") ? qualified.substring(0, qualified.indexOf('(')) : qualified;
        int dot = core.lastIndexOf('.');
        return dot >= 0 && dot + 1 < core.length() ? core.substring(dot + 1) : core;
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
