package dtm.ide.ui;

import dtm.ide.iis.IisAppPool;
import dtm.ide.iis.IisAppPoolConfig;
import dtm.ide.iis.IisApplication;
import dtm.ide.iis.IisBinding;
import dtm.ide.iis.IisBroker;
import dtm.ide.iis.IisConfig;
import dtm.ide.iis.IisEnvironment;
import dtm.ide.iis.IisService;
import dtm.ide.iis.IisSite;
import dtm.ide.iis.IisVirtualDirectory;
import dtm.ide.iis.IisWarmUp;
import dtm.ide.iis.IisWorkerProcess;
import dtm.stools.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

@Slf4j
public final class IisManagerPanel extends JPanel {

    public interface DialogHost {

        boolean confirmForm(String title, JComponent form, String confirmText);

        boolean confirmDelete(String title, String message);

        void message(String title, String message, boolean error);

        int choose(String title, JComponent form, List<String> options);
    }

    private static final Color ACCENT = new Color(59, 130, 246);
    private static final Color DANGER = new Color(220, 53, 69);
    private static final Color SUCCESS = new Color(52, 168, 83);
    private static final int ACTIONS_WIDTH = 210;

    private final LongConsumer attachDebugger;
    private final DialogHost dialogs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "iis-manager");
        thread.setDaemon(true);
        return thread;
    });

    private final JLabel titleLabel = new JLabel(text("title", "IIS"));
    private final JLabel statusLabel = new JLabel();
    private final JLabel elevationLabel = new JLabel();
    private final StatusBadge serverStateLabel = new StatusBadge();
    private JButton startServerButton;
    private JButton stopServerButton;
    private JButton restartServerButton;

    private final ReadOnlyTableModel poolModel = new ReadOnlyTableModel(new String[]{
            text("column.pool", "Pool"), text("column.state", "State"),
            text("column.runtime", ".NET CLR"), text("column.pipeline", "Pipeline"),
            text("column.identity", "Identity")});
    private final ReadOnlyTableModel siteModel = new ReadOnlyTableModel(new String[]{
            text("column.site", "Site"), text("column.id", "ID"), text("column.state", "State"),
            text("column.bindings", "Bindings"), text("column.physicalPath", "Physical path")});
    private final ReadOnlyTableModel applicationModel = new ReadOnlyTableModel(new String[]{
            text("column.application", "Application"), text("column.pool", "Pool"),
            text("column.physicalPath", "Physical path")});
    private final ReadOnlyTableModel virtualDirectoryModel = new ReadOnlyTableModel(new String[]{
            text("column.virtualDirectory", "Virtual directory"), text("column.application", "Application"),
            text("column.physicalPath", "Physical path")});
    private final ReadOnlyTableModel processModel = new ReadOnlyTableModel(new String[]{
            text("column.pid", "PID"), text("column.pool", "Pool")});

    private final JTable poolTable = styledTable(poolModel, 1);
    private final JTable siteTable = styledTable(siteModel, 2);
    private final JTable applicationTable = styledTable(applicationModel, -1);
    private final JTable virtualDirectoryTable = styledTable(virtualDirectoryModel, -1);
    private final JTable processTable = styledTable(processModel, -1);

    private final JPanel content = new JPanel(new BorderLayout());
    private final JPanel childContent = new JPanel(new BorderLayout());
    private final List<TabButton> tabs = new ArrayList<>();
    private final List<TabButton> childTabs = new ArrayList<>();

    private List<IisAppPool> pools = List.of();
    private List<IisSite> sites = List.of();
    private List<IisApplication> applications = List.of();
    private List<IisVirtualDirectory> virtualDirectories = List.of();
    private List<IisWorkerProcess> processes = List.of();

    private static String text(String key, String def) {
        return I18n.getText(IisManagerPanel.class, key, def);
    }

    public IisManagerPanel(LongConsumer attachDebugger, DialogHost dialogs) {
        super(new BorderLayout());
        this.attachDebugger = attachDebugger;
        this.dialogs = dialogs;
        setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        add(buildHeader(), BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        siteTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshChildTables();
            }
        });
        registerTabs();
    }

    private JComponent buildHeader() {
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 4f));
        elevationLabel.setForeground(mutedForeground());
        elevationLabel.setFont(smallFont(elevationLabel.getFont()));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(titleLabel);
        left.add(elevationLabel);

        startServerButton = secondaryButton(text("action.startServer", "Start IIS"),
                () -> serverAction(IisService::startServer, text("status.startingServer", "Starting IIS...")));
        stopServerButton = secondaryButton(text("action.stopServer", "Stop IIS"),
                () -> serverAction(IisService::stopServer, text("status.stoppingServer", "Stopping IIS...")));
        restartServerButton = secondaryButton(text("action.restartServer", "Restart IIS"),
                () -> serverAction(IisService::restartServer, text("status.restartingServer", "Restarting IIS...")));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(serverStateLabel);
        right.add(startServerButton);
        right.add(stopServerButton);
        right.add(restartServerButton);
        right.add(secondaryButton(text("action.refresh", "Refresh"), this::refresh));

        JPanel titleRow = new JPanel(new BorderLayout(12, 0));
        titleRow.setOpaque(false);
        titleRow.add(left, BorderLayout.CENTER);
        titleRow.add(right, BorderLayout.EAST);

        JPanel tabRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tabRow.setOpaque(false);
        tabRow.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor()),
                BorderFactory.createEmptyBorder(8, 0, 0, 0)));
        tabs.add(new TabButton(text("tab.pools", "Application pools"), this::buildPoolsTab));
        tabs.add(new TabButton(text("tab.sites", "Sites"), this::buildSitesTab));
        tabs.add(new TabButton(text("tab.processes", "Worker processes"), this::buildProcessesTab));
        for (TabButton tab : tabs) {
            tabRow.add(tab);
        }

        JPanel header = new JPanel(new BorderLayout(0, 10));
        header.setOpaque(false);
        header.add(titleRow, BorderLayout.NORTH);
        header.add(tabRow, BorderLayout.SOUTH);
        return header;
    }

    private JComponent buildStatusBar() {
        statusLabel.setForeground(mutedForeground());
        statusLabel.setFont(smallFont(statusLabel.getFont()));
        JPanel status = new JPanel(new BorderLayout());
        status.setOpaque(false);
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor()),
                BorderFactory.createEmptyBorder(8, 2, 0, 2)));
        status.add(statusLabel, BorderLayout.CENTER);
        return status;
    }

    private void registerTabs() {
        for (int i = 0; i < tabs.size(); i++) {
            int index = i;
            tabs.get(i).addActionListener(e -> selectTab(index));
        }
        selectTab(0);
    }

    private void selectTab(int index) {
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).setActive(i == index);
        }
        content.removeAll();
        content.add(tabs.get(index).createContent(), BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
    }

    private JComponent buildPoolsTab() {
        return withActions(scroll(poolTable),
                new ActionGroup(text("group.manage", "Manage"), List.of(
                        primaryButton(text("action.addPool", "Add pool"), this::addPool),
                        secondaryButton(text("action.basicSettings", "Basic settings..."), this::editPoolBasic),
                        secondaryButton(text("action.advancedSettings", "Advanced settings..."),
                                this::editPoolAdvanced))),
                new ActionGroup(text("group.control", "Control"), List.of(
                        secondaryButton(text("action.start", "Start"), () -> poolAction(IisService::startAppPool)),
                        secondaryButton(text("action.stop", "Stop"), () -> poolAction(IisService::stopAppPool)),
                        secondaryButton(text("action.restart", "Restart"), this::restartPool),
                        secondaryButton(text("action.recycle", "Recycle"), () -> poolAction(IisService::recycleAppPool)))),
                new ActionGroup("", List.of(
                        dangerButton(text("action.remove", "Remove"), this::removePool))));
    }

    private JComponent buildSitesTab() {
        JComponent sitesPanel = withActions(scroll(siteTable),
                new ActionGroup(text("group.manage", "Manage"), List.of(
                        primaryButton(text("action.addSite", "Add site"), this::addSite),
                        secondaryButton(text("action.bindings", "Bindings..."), this::editBindings),
                        secondaryButton(text("action.physicalPath", "Physical path..."), this::changeSitePath),
                        secondaryButton(text("action.features", "Features..."),
                                () -> openConfiguration(siteTarget())),
                        secondaryButton(text("action.logs", "Logging..."), this::editSiteLogs),
                        secondaryButton(text("action.limits", "Limits..."), this::editSiteLimits))),
                new ActionGroup(text("group.control", "Control"), List.of(
                        secondaryButton(text("action.start", "Start"), () -> siteAction(IisService::startSite)),
                        secondaryButton(text("action.stop", "Stop"), () -> siteAction(IisService::stopSite)),
                        secondaryButton(text("action.restart", "Restart"), this::restartSite))),
                new ActionGroup(text("group.open", "Open"), List.of(
                        secondaryButton(text("action.browse", "Open in browser"), this::browseSite),
                        secondaryButton(text("action.explore", "Explore"), this::exploreSite),
                        secondaryButton(text("action.openLogs", "Open log folder"), this::openSiteLogFolder))),
                new ActionGroup("", List.of(
                        dangerButton(text("action.remove", "Remove"), this::removeSite))));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sitesPanel, buildChildPanel());
        split.setResizeWeight(0.58);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(8);
        split.setOpaque(false);
        return split;
    }

    private JComponent buildChildPanel() {
        JPanel tabRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tabRow.setOpaque(false);
        tabRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        if (childTabs.isEmpty()) {
            childTabs.add(new TabButton(text("tab.applications", "Applications"), this::buildApplicationsPane));
            childTabs.add(new TabButton(text("tab.virtualDirectories", "Virtual directories"),
                    this::buildVirtualDirectoriesPane));
            for (int i = 0; i < childTabs.size(); i++) {
                int index = i;
                childTabs.get(i).addActionListener(e -> selectChildTab(index));
            }
        }
        for (TabButton tab : childTabs) {
            tabRow.add(tab);
        }

        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(tabRow, BorderLayout.NORTH);
        panel.add(childContent, BorderLayout.CENTER);
        selectChildTab(0);
        return panel;
    }

    private void selectChildTab(int index) {
        for (int i = 0; i < childTabs.size(); i++) {
            childTabs.get(i).setActive(i == index);
        }
        childContent.removeAll();
        childContent.add(childTabs.get(index).createContent(), BorderLayout.CENTER);
        childContent.revalidate();
        childContent.repaint();
    }

    private JComponent buildApplicationsPane() {
        return withActions(scroll(applicationTable),
                new ActionGroup(text("group.manage", "Manage"), List.of(
                        primaryButton(text("action.addApplication", "Add application"), this::addApplication),
                        secondaryButton(text("action.changePool", "Change pool..."), this::changeApplicationPool),
                        secondaryButton(text("action.features", "Features..."),
                                () -> openConfiguration(applicationTarget())),
                        secondaryButton(text("action.explore", "Explore"), this::exploreApplication))),
                new ActionGroup("", List.of(
                        dangerButton(text("action.remove", "Remove"), this::removeApplication))));
    }

    private JComponent buildVirtualDirectoriesPane() {
        return withActions(scroll(virtualDirectoryTable),
                new ActionGroup(text("group.manage", "Manage"), List.of(
                        primaryButton(text("action.addVirtualDirectory", "Add virtual directory"),
                                this::addVirtualDirectory),
                        secondaryButton(text("action.physicalPath", "Physical path..."),
                                this::changeVirtualDirectoryPath),
                        secondaryButton(text("action.explore", "Explore"), this::exploreVirtualDirectory))),
                new ActionGroup("", List.of(
                        dangerButton(text("action.remove", "Remove"), this::removeVirtualDirectory))));
    }

    private JComponent buildProcessesTab() {
        return withActions(scroll(processTable),
                new ActionGroup(text("group.debug", "Debug"), List.of(
                        primaryButton(text("action.attach", "Attach debugger"), this::attachToSelectedProcess))));
    }

    private record ActionGroup(String title, List<JButton> buttons) {
    }

    private JComponent withActions(JComponent center, ActionGroup... groups) {
        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        actions.setOpaque(false);
        actions.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));

        for (ActionGroup group : groups) {
            if (group.title() != null && !group.title().isBlank()) {
                JLabel label = new JLabel(group.title());
                label.setForeground(mutedForeground());
                label.setFont(smallFont(label.getFont()).deriveFont(Font.BOLD));
                label.setAlignmentX(Component.LEFT_ALIGNMENT);
                label.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
                actions.add(label);
            }
            for (JButton button : group.buttons()) {
                button.setAlignmentX(Component.LEFT_ALIGNMENT);
                button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
                button.setHorizontalAlignment(SwingConstants.LEFT);
                actions.add(button);
                actions.add(Box.createVerticalStrut(6));
            }
            actions.add(Box.createVerticalStrut(10));
        }
        actions.add(Box.createVerticalGlue());

        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(actions, BorderLayout.NORTH);

        JScrollPane actionsScroll = new JScrollPane(holder,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        actionsScroll.setBorder(BorderFactory.createEmptyBorder());
        actionsScroll.setOpaque(false);
        actionsScroll.getViewport().setOpaque(false);
        actionsScroll.getVerticalScrollBar().setUnitIncrement(16);
        actionsScroll.setPreferredSize(new Dimension(ACTIONS_WIDTH, 10));
        actionsScroll.setMinimumSize(new Dimension(ACTIONS_WIDTH, 60));
        UiSupport.styleScroll(actionsScroll);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        panel.setMinimumSize(new Dimension(320, 140));
        panel.add(center, BorderLayout.CENTER);
        panel.add(actionsScroll, BorderLayout.EAST);
        return panel;
    }

    private JTable styledTable(DefaultTableModel model, int stateColumn) {
        JTable table = new JTable(model);
        table.setRowHeight(30);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setDefaultRenderer(Object.class, new CellRenderer(stateColumn));

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor()));
        header.setDefaultRenderer(new HeaderRenderer(header.getDefaultRenderer()));
        return table;
    }

    private JScrollPane scroll(JTable table) {
        JScrollPane pane = new JScrollPane(table);
        pane.setBorder(BorderFactory.createLineBorder(borderColor(), 1, true));
        pane.getViewport().setBackground(table.getBackground());
        UiSupport.styleScroll(pane);
        return pane;
    }

    private JButton primaryButton(String label, Runnable action) {
        JButton button = baseButton(label, action);
        button.setBackground(ACCENT);
        button.setForeground(Color.WHITE);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 1, true),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)));
        return button;
    }

    private JButton secondaryButton(String label, Runnable action) {
        JButton button = baseButton(label, action);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor(), 1, true),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        return button;
    }

    private JButton dangerButton(String label, Runnable action) {
        JButton button = baseButton(label, action);
        button.setForeground(DANGER);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DANGER, 1, true),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        return button;
    }

    private JButton baseButton(String label, Runnable action) {
        JButton button = new JButton(label);
        button.setFocusPainted(false);
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.addActionListener(e -> action.run());
        return button;
    }

    public void refresh() {
        IisEnvironment.Info environment = IisEnvironment.current();
        log.info("Painel do IIS atualizando. {}", environment.describe());
        if (!environment.iisInstalled()) {
            statusLabel.setText(text("status.notInstalled",
                    "IIS is not installed on this machine. Enable \"Internet Information Services\" in Windows Features."));
            clearTables();
            return;
        }
        if (!environment.manageable()) {
            statusLabel.setText(text("status.noAppCmd",
                    "IIS is installed but appcmd.exe was not found. Enable \"IIS Management Scripts and Tools\" in Windows Features."));
            clearTables();
            return;
        }
        statusLabel.setText(text("status.loading", "Reading IIS configuration..."));
        executor.execute(() -> {
            IisService.Snapshot snapshot;
            List<IisVirtualDirectory> directories;
            try {
                snapshot = IisService.snapshot();
                directories = IisService.virtualDirectories();
            } catch (Exception e) {
                log.debug("Falha ao consultar o IIS: {}", e.getMessage());
                snapshot = IisService.Snapshot.empty();
                directories = List.of();
            }
            IisService.Snapshot loaded = snapshot;
            List<IisVirtualDirectory> loadedDirectories = directories;
            String version = IisEnvironment.version();
            String service = IisEnvironment.serviceState();
            boolean elevated = IisEnvironment.isElevated();
            SwingUtilities.invokeLater(() -> {
                pools = loaded.pools();
                sites = loaded.sites();
                applications = loaded.applications();
                virtualDirectories = loadedDirectories;
                processes = loaded.workerProcesses();
                applyEnvironmentLabels(version, service, elevated);
                refreshTables();
            });
        });
    }

    private void applyEnvironmentLabels(String version, String service, boolean elevated) {
        titleLabel.setText(version == null || version.isBlank() ? text("title", "IIS") : "IIS " + version);
        boolean running = "RUNNING".equalsIgnoreCase(service);
        boolean unknown = service == null || service.isBlank();
        serverStateLabel.update(unknown
                        ? text("status.serverUnknown", "server: unknown")
                        : running ? text("status.serverRunning", "server running")
                                : text("status.serverStopped", "server stopped"),
                unknown ? mutedForeground() : running ? SUCCESS : DANGER);
        startServerButton.setEnabled(!running);
        stopServerButton.setEnabled(running || unknown);
        restartServerButton.setEnabled(running || unknown);

        boolean brokerActive = IisBroker.isRunning();
        elevationLabel.setText(elevated
                ? text("status.elevated", "elevated session")
                : brokerActive
                        ? text("status.brokerActive", "elevated helper active (no further UAC prompts)")
                        : text("status.notElevated", "without elevation (changes will prompt for UAC)"));
        elevationLabel.setForeground(elevated || brokerActive ? SUCCESS : mutedForeground());

        statusLabel.setText(text("status.service", "W3SVC") + ": "
                + (service == null || service.isBlank() ? "?" : service)
                + "   ·   " + pools.size() + " " + text("status.poolsCount", "pools")
                + "   ·   " + sites.size() + " " + text("status.sitesCount", "sites")
                + "   ·   " + processes.size() + " " + text("status.processesCount", "worker processes"));
    }

    private void serverAction(Supplier<IisService.Result> action, String progress) {
        statusLabel.setText(progress);
        executor.execute(() -> {
            IisService.Result result;
            try {
                result = action.get();
            } catch (Exception e) {
                result = IisService.Result.fail(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            IisService.Result outcome = result;
            SwingUtilities.invokeLater(() -> {
                if (!outcome.success()) {
                    dialogs.message(text("dialog.error", "IIS error"), outcome.message(), true);
                }
                refresh();
            });
        });
    }

    private void clearTables() {
        pools = List.of();
        sites = List.of();
        applications = List.of();
        virtualDirectories = List.of();
        processes = List.of();
        refreshTables();
    }

    private void refreshTables() {
        String selectedPool = selectedKey(poolTable, poolModel);
        String selectedSite = selectedKey(siteTable, siteModel);
        String selectedApplication = selectedKey(applicationTable, applicationModel);
        String selectedVirtualDirectory = selectedKey(virtualDirectoryTable, virtualDirectoryModel);
        String selectedProcess = selectedKey(processTable, processModel);

        poolModel.setRowCount(0);
        for (IisAppPool pool : pools) {
            poolModel.addRow(new Object[]{pool.name(), IisService.describeState(pool.state()),
                    pool.runtimeLabel(), pool.managedPipelineMode(), pool.identityLabel()});
        }
        siteModel.setRowCount(0);
        for (IisSite site : sites) {
            siteModel.addRow(new Object[]{site.name(), site.id(), IisService.describeState(site.state()),
                    site.bindingsLabel(), site.physicalPath() == null ? "" : site.physicalPath()});
        }
        processModel.setRowCount(0);
        for (IisWorkerProcess process : processes) {
            processModel.addRow(new Object[]{process.pid(), process.appPoolName()});
        }

        restoreSelection(poolTable, poolModel, selectedPool);
        restoreSelection(siteTable, siteModel, selectedSite);
        restoreSelection(processTable, processModel, selectedProcess);
        refreshChildTables(selectedApplication, selectedVirtualDirectory);
    }

    private void refreshChildTables() {
        refreshChildTables(selectedKey(applicationTable, applicationModel),
                selectedKey(virtualDirectoryTable, virtualDirectoryModel));
    }

    private void refreshChildTables(String selectedApplication, String selectedVirtualDirectory) {
        applicationModel.setRowCount(0);
        virtualDirectoryModel.setRowCount(0);
        IisSite site = selectedSite();
        if (site != null) {
            for (IisApplication application : visibleApplications()) {
                applicationModel.addRow(new Object[]{application.displayPath(), application.applicationPool(),
                        application.physicalPath() == null ? "" : application.physicalPath()});
            }
            for (IisVirtualDirectory directory : visibleVirtualDirectories()) {
                virtualDirectoryModel.addRow(new Object[]{directory.path(), directory.applicationName(),
                        directory.physicalPath() == null ? "" : directory.physicalPath()});
            }
        }
        restoreSelection(applicationTable, applicationModel, selectedApplication);
        restoreSelection(virtualDirectoryTable, virtualDirectoryModel, selectedVirtualDirectory);
    }

    private String selectedKey(JTable table, DefaultTableModel model) {
        int row = table.getSelectedRow();
        if (row < 0 || row >= model.getRowCount()) {
            return null;
        }
        Object value = model.getValueAt(row, 0);
        return value == null ? null : value.toString();
    }

    private void restoreSelection(JTable table, DefaultTableModel model, String key) {
        if (key == null) {
            return;
        }
        for (int row = 0; row < model.getRowCount(); row++) {
            Object value = model.getValueAt(row, 0);
            if (value != null && key.equals(value.toString())) {
                table.setRowSelectionInterval(row, row);
                return;
            }
        }
    }

    private List<IisApplication> visibleApplications() {
        IisSite site = selectedSite();
        List<IisApplication> visible = new ArrayList<>();
        if (site == null) {
            return visible;
        }
        for (IisApplication application : applications) {
            if (site.name().equals(application.siteName())) {
                visible.add(application);
            }
        }
        return visible;
    }

    private List<IisVirtualDirectory> visibleVirtualDirectories() {
        IisSite site = selectedSite();
        List<IisVirtualDirectory> visible = new ArrayList<>();
        if (site == null) {
            return visible;
        }
        String prefix = site.name() + "/";
        for (IisVirtualDirectory directory : virtualDirectories) {
            String application = directory.applicationName();
            if (application != null && application.startsWith(prefix) && !directory.isRoot()) {
                visible.add(directory);
            }
        }
        return visible;
    }

    private IisAppPool selectedPool() {
        int row = poolTable.getSelectedRow();
        return row < 0 || row >= pools.size() ? null : pools.get(row);
    }

    private IisSite selectedSite() {
        int row = siteTable.getSelectedRow();
        return row < 0 || row >= sites.size() ? null : sites.get(row);
    }

    private IisApplication selectedApplication() {
        int row = applicationTable.getSelectedRow();
        List<IisApplication> visible = visibleApplications();
        return row < 0 || row >= visible.size() ? null : visible.get(row);
    }

    private IisVirtualDirectory selectedVirtualDirectory() {
        int row = virtualDirectoryTable.getSelectedRow();
        List<IisVirtualDirectory> visible = visibleVirtualDirectories();
        return row < 0 || row >= visible.size() ? null : visible.get(row);
    }

    private void poolAction(Function<String, IisService.Result> action) {
        IisAppPool pool = selectedPool();
        if (pool == null) {
            warn(text("warn.selectPool", "Select an application pool."));
            return;
        }
        execute(() -> action.apply(pool.name()));
    }

    private void siteAction(Function<String, IisService.Result> action) {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        execute(() -> action.apply(site.name()));
    }

    private void restartPool() {
        IisAppPool pool = selectedPool();
        if (pool == null) {
            warn(text("warn.selectPool", "Select an application pool."));
            return;
        }
        execute(() -> {
            IisService.stopAppPool(pool.name());
            return IisService.startAppPool(pool.name());
        });
    }

    private void restartSite() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        execute(() -> {
            IisService.stopSite(site.name());
            return IisService.startSite(site.name());
        });
    }

    private void addPool() {
        IisAppPoolBasicPanel form = new IisAppPoolBasicPanel(null);
        if (!IisFeatureDialog.show(this, text("dialog.addPool", "Add application pool"), null, form,
                text("action.create", "Create"))) {
            return;
        }
        String name = form.poolName();
        if (name.isBlank()) {
            warn(text("warn.poolName", "Enter a name for the pool."));
            return;
        }
        String runtime = form.runtimeVersion();
        String pipeline = form.pipelineMode();
        boolean start = form.startImmediately();
        execute(() -> {
            IisService.Result created = IisService.addAppPool(name, runtime, pipeline);
            if (!created.success()) {
                return created;
            }
            return start ? IisService.startAppPool(name) : IisService.Result.ok();
        });
    }

    private void editPoolBasic() {
        IisAppPool pool = selectedPool();
        if (pool == null) {
            warn(text("warn.selectPool", "Select an application pool."));
            return;
        }
        IisAppPoolBasicPanel form = new IisAppPoolBasicPanel(pool);
        if (!IisFeatureDialog.show(this, text("dialog.poolBasic", "Edit application pool"), pool.name(),
                form, text("action.apply", "Apply"))) {
            return;
        }
        String runtime = form.runtimeVersion();
        String pipeline = form.pipelineMode();
        boolean start = form.startImmediately();
        execute(() -> {
            IisService.Result applied = IisService.applyAppPoolBasics(pool.name(), runtime, pipeline, start);
            if (!applied.success()) {
                return applied;
            }
            return start && !pool.started() ? IisService.startAppPool(pool.name()) : IisService.Result.ok();
        });
    }

    private void editPoolAdvanced() {
        IisAppPool pool = selectedPool();
        if (pool == null) {
            warn(text("warn.selectPool", "Select an application pool."));
            return;
        }
        statusLabel.setText(text("status.readingPool", "Reading application pool settings..."));
        executor.execute(() -> {
            Map<String, String> current;
            try {
                current = IisAppPoolConfig.read(IisService.findAppPool(pool.name()));
            } catch (Exception e) {
                log.debug("Falha ao ler o pool {}: {}", pool.name(), e.getMessage());
                current = IisAppPoolConfig.read(pool);
            }
            Map<String, String> loaded = current;
            SwingUtilities.invokeLater(() -> {
                IisPropertyGridPanel form = new IisPropertyGridPanel(IisAppPoolConfig.properties(), loaded);
                if (!IisFeatureDialog.show(this, text("dialog.poolAdvanced", "Advanced settings"),
                        pool.name(), form, text("action.apply", "Apply"))) {
                    statusLabel.setText("");
                    return;
                }
                Map<String, String> desired = form.toValues();
                execute(() -> IisAppPoolConfig.apply(pool.name(), loaded, desired));
            });
        });
    }

    private void removePool() {
        IisAppPool pool = selectedPool();
        if (pool == null) {
            warn(text("warn.selectPool", "Select an application pool."));
            return;
        }
        if (!confirmDelete(pool.name())) {
            return;
        }
        execute(() -> IisService.deleteAppPool(pool.name()));
    }

    private void addSite() {
        JTextField name = new JTextField();
        JTextField physicalPath = new JTextField();
        JTextField port = new JTextField("8080");
        JTextField host = new JTextField("localhost");
        JComboBox<String> protocol = new JComboBox<>(new String[]{"http", "https"});
        JPanel form = form(
                text("field.siteName", "Site name:"), name,
                text("field.physicalPath", "Physical path:"), pathField(physicalPath),
                text("field.protocol", "Protocol:"), protocol,
                text("field.port", "Port:"), port,
                text("field.host", "Host name:"), host);
        if (!dialogs.confirmForm(text("dialog.addSite", "New site"), form, text("action.create", "Create"))) {
            return;
        }
        if (name.getText().isBlank() || physicalPath.getText().isBlank()) {
            warn(text("warn.siteFields", "Provide the site name and the physical path."));
            return;
        }
        IisBinding binding = new IisBinding(String.valueOf(protocol.getSelectedItem()), "*",
                port.getText().strip(), host.getText().strip());
        Path root = Path.of(physicalPath.getText().strip());
        execute(() -> IisService.addSite(name.getText().strip(), binding, root));
    }

    private void removeSite() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        if (!confirmDelete(site.name())) {
            return;
        }
        execute(() -> IisService.deleteSite(site.name()));
    }

    private void browseSite() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        IisWarmUp.openBrowser(site.browseUrl());
    }

    private void exploreSite() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        openFolder(site.physicalPath());
    }

    private void exploreApplication() {
        IisApplication application = selectedApplication();
        if (application == null) {
            warn(text("warn.selectApplication", "Select an application."));
            return;
        }
        openFolder(application.physicalPath());
    }

    private void exploreVirtualDirectory() {
        IisVirtualDirectory directory = selectedVirtualDirectory();
        if (directory == null) {
            warn(text("warn.selectVirtualDirectory", "Select a virtual directory."));
            return;
        }
        openFolder(directory.physicalPath());
    }

    private void openFolder(String path) {
        if (path == null || path.isBlank()) {
            warn(text("warn.noPhysicalPath", "This item has no physical path."));
            return;
        }
        java.io.File folder = new java.io.File(IisService.expandEnvironment(path));
        if (!folder.isDirectory()) {
            warn(text("warn.folderMissing", "Folder not found: ") + folder);
            return;
        }
        try {
            java.awt.Desktop.getDesktop().open(folder);
        } catch (Exception e) {
            log.debug("Falha ao abrir a pasta {}: {}", path, e.getMessage());
            warn(text("warn.folderOpen", "Could not open the folder: ") + folder);
        }
    }




    private String siteTarget() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return null;
        }
        return site.name();
    }

    private String applicationTarget() {
        IisApplication application = selectedApplication();
        if (application == null) {
            warn(text("warn.selectApplication", "Select an application."));
            return null;
        }
        return application.name();
    }

    private void openConfiguration(String target) {
        if (target == null) {
            return;
        }
        List<IisConfig.Section> catalog = IisConfig.catalog();
        List<String> names = new ArrayList<>();
        for (IisConfig.Section section : catalog) {
            names.add(section.title());
        }
        JComboBox<String> chooser = new JComboBox<>(names.toArray(new String[0]));
        JTextField customPath = new JTextField();
        JPanel form = form(
                text("field.section", "Section:"), chooser,
                text("field.customSection", "Or section path:"), customPath);
        if (!dialogs.confirmForm(text("dialog.configuration", "Configuration") + " — " + target, form,
                text("action.open", "Open"))) {
            return;
        }
        String path = customPath.getText().strip();
        IisConfig.Section section = path.isEmpty()
                ? catalog.get(Math.max(0, chooser.getSelectedIndex()))
                : IisConfig.custom(path);
        loadSection(target, section);
    }

    private void loadSection(String target, IisConfig.Section section) {
        statusLabel.setText(text("status.readingSection", "Reading section...") + " " + section.path());
        executor.execute(() -> {
            IisConfig.Data current;
            try {
                current = IisConfig.read(target, section);
            } catch (Exception e) {
                log.debug("Falha ao ler seção {}: {}", section.path(), e.getMessage());
                current = IisConfig.Data.empty();
            }
            IisConfig.Data loaded = current;
            SwingUtilities.invokeLater(() -> {
                if (loaded.isEmpty()) {
                    dialogs.message(text("dialog.error", "IIS error"),
                            text("warn.sectionUnavailable", "Could not read section: ") + section.path(), true);
                    statusLabel.setText("");
                    return;
                }
                IisSectionEditorPanel form = new IisSectionEditorPanel(section, loaded);
                if (!IisFeatureDialog.show(this, section.title(), target + "   ·   " + section.path(),
                        form, text("action.apply", "Apply"))) {
                    statusLabel.setText("");
                    return;
                }
                IisConfig.Data desired = form.toData();
                execute(() -> IisConfig.apply(target, section, loaded, desired));
            });
        });
    }

    private void editSiteLimits() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        statusLabel.setText(text("status.readingLimits", "Reading limits..."));
        executor.execute(() -> {
            IisService.SiteLimits current;
            try {
                current = IisService.readLimits(site.name());
            } catch (Exception e) {
                current = IisService.SiteLimits.defaults();
            }
            IisService.SiteLimits loaded = current;
            SwingUtilities.invokeLater(() -> {
                JTextField connectionTimeout = new JTextField(loaded.connectionTimeout());
                JTextField maxBandwidth = new JTextField(loaded.maxBandwidth());
                JTextField maxConnections = new JTextField(loaded.maxConnections());
                JPanel form = form(
                        text("field.connectionTimeout", "Connection timeout (hh:mm:ss):"), connectionTimeout,
                        text("field.maxBandwidth", "Maximum bandwidth (bytes/s):"), maxBandwidth,
                        text("field.maxConnections", "Maximum connections:"), maxConnections);
                if (!IisFeatureDialog.show(this, text("dialog.limits", "Limits"), site.name(), form,
                        text("action.apply", "Apply"))) {
                    statusLabel.setText("");
                    return;
                }
                IisService.SiteLimits desired = new IisService.SiteLimits(
                        connectionTimeout.getText().strip(), maxBandwidth.getText().strip(),
                        maxConnections.getText().strip());
                execute(() -> IisService.applyLimits(site.name(), desired));
            });
        });
    }

    private void editSiteLogs() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        statusLabel.setText(text("status.readingLogs", "Reading log settings..."));
        executor.execute(() -> {
            IisService.LogSettings current;
            try {
                current = IisService.readLogSettings(site.name());
            } catch (Exception e) {
                log.debug("Falha ao ler logs de {}: {}", site.name(), e.getMessage());
                current = IisService.LogSettings.defaults();
            }
            IisService.LogSettings loaded = current;
            SwingUtilities.invokeLater(() -> {
                JCheckBox enabled = new JCheckBox(text("field.logEnabled", "Enable logging"), loaded.enabled());
                JTextField directory = new JTextField(loaded.directory());
                JComboBox<String> format = new JComboBox<>(new String[]{"W3C", "IIS", "NCSA", "Custom"});
                format.setSelectedItem(loaded.format());
                JComboBox<String> period = new JComboBox<>(new String[]{
                        "Daily", "Hourly", "Weekly", "Monthly", "MaxSize"});
                period.setSelectedItem(loaded.period());
                JPanel form = form(
                        "", enabled,
                        text("field.logDirectory", "Log directory:"), pathField(directory),
                        text("field.logFormat", "Format:"), format,
                        text("field.logPeriod", "Rollover:"), period);
                if (!IisFeatureDialog.show(this, text("dialog.logs", "Logging"), site.name(), form,
                        text("action.apply", "Apply"))) {
                    statusLabel.setText("");
                    return;
                }
                IisService.LogSettings desired = new IisService.LogSettings(enabled.isSelected(),
                        directory.getText().strip(), String.valueOf(format.getSelectedItem()),
                        String.valueOf(period.getSelectedItem()));
                execute(() -> IisService.applyLogSettings(site.name(), desired));
            });
        });
    }

    private void openSiteLogFolder() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        executor.execute(() -> {
            IisService.LogSettings settings;
            try {
                settings = IisService.readLogSettings(site.name());
            } catch (Exception e) {
                settings = IisService.LogSettings.defaults();
            }
            Path folder = IisService.resolveLogFolder(site, settings);
            SwingUtilities.invokeLater(() -> openFolder(folder == null ? null : folder.toString()));
        });
    }

    private void editBindings() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        DefaultComboBoxModel<String> existing = new DefaultComboBoxModel<>();
        for (IisBinding binding : site.bindings()) {
            existing.addElement(binding.descriptor());
        }
        JComboBox<String> current = new JComboBox<>(existing);
        JTextField port = new JTextField("8080");
        JTextField host = new JTextField("localhost");
        JComboBox<String> protocol = new JComboBox<>(new String[]{"http", "https"});
        JPanel form = form(
                text("field.existingBindings", "Existing bindings:"), current,
                text("field.protocol", "Protocol:"), protocol,
                text("field.port", "Port:"), port,
                text("field.host", "Host name:"), host);
        int choice = dialogs.choose(text("dialog.bindings", "Site bindings"), form,
                List.of(text("action.addBinding", "Add"), text("action.removeBinding", "Remove selected")));
        if (choice == 0) {
            IisBinding binding = new IisBinding(String.valueOf(protocol.getSelectedItem()), "*",
                    port.getText().strip(), host.getText().strip());
            execute(() -> IisService.addBinding(site.name(), binding));
        } else if (choice == 1) {
            Object selected = current.getSelectedItem();
            IisBinding binding = selected == null ? null : IisBinding.parseSingle(String.valueOf(selected));
            if (binding == null) {
                warn(text("warn.selectBinding", "Select a binding to remove."));
                return;
            }
            execute(() -> IisService.removeBinding(site.name(), binding));
        }
    }

    private void changeSitePath() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        JTextField physicalPath = new JTextField(site.physicalPath() == null ? "" : site.physicalPath());
        JPanel form = form(text("field.physicalPath", "Physical path:"), pathField(physicalPath));
        if (!dialogs.confirmForm(text("dialog.physicalPath", "Physical path"), form,
                text("action.apply", "Apply"))) {
            return;
        }
        if (physicalPath.getText().isBlank()) {
            return;
        }
        Path root = Path.of(physicalPath.getText().strip());
        execute(() -> IisService.setSitePhysicalPath(site.name(), root));
    }

    private void addApplication() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        JTextField path = new JTextField("/app");
        JTextField physicalPath = new JTextField();
        JComboBox<String> pool = new JComboBox<>(poolNames());
        JPanel form = form(
                text("field.appPath", "Application path:"), path,
                text("field.physicalPath", "Physical path:"), pathField(physicalPath),
                text("field.pool", "Application pool:"), pool);
        if (!dialogs.confirmForm(text("dialog.addApplication", "New application"), form,
                text("action.create", "Create"))) {
            return;
        }
        if (physicalPath.getText().isBlank()) {
            warn(text("warn.physicalPath", "Provide the physical path."));
            return;
        }
        Path root = Path.of(physicalPath.getText().strip());
        String selectedPool = String.valueOf(pool.getSelectedItem());
        execute(() -> IisService.addApplication(site.name(), path.getText().strip(), root, selectedPool));
    }

    private void changeApplicationPool() {
        IisApplication application = selectedApplication();
        if (application == null) {
            warn(text("warn.selectApplication", "Select an application."));
            return;
        }
        JComboBox<String> pool = new JComboBox<>(poolNames());
        pool.setSelectedItem(application.applicationPool());
        JPanel form = form(text("field.pool", "Application pool:"), pool);
        if (!dialogs.confirmForm(text("dialog.changePool", "Change application pool"), form,
                text("action.apply", "Apply"))) {
            return;
        }
        String selectedPool = String.valueOf(pool.getSelectedItem());
        execute(() -> IisService.setApplicationPool(application.name(), selectedPool));
    }

    private void removeApplication() {
        IisApplication application = selectedApplication();
        if (application == null) {
            warn(text("warn.selectApplication", "Select an application."));
            return;
        }
        if (application.isRoot()) {
            warn(text("warn.rootApplication", "The site root application cannot be removed; remove the site instead."));
            return;
        }
        if (!confirmDelete(application.name())) {
            return;
        }
        execute(() -> IisService.deleteApplication(application.name()));
    }

    private void addVirtualDirectory() {
        IisSite site = selectedSite();
        if (site == null) {
            warn(text("warn.selectSite", "Select a site."));
            return;
        }
        JTextField path = new JTextField("/vdir");
        JTextField physicalPath = new JTextField();
        JComboBox<String> application = new JComboBox<>(applicationNames(site));
        JPanel form = form(
                text("field.application", "Application:"), application,
                text("field.vdirPath", "Virtual directory path:"), path,
                text("field.physicalPath", "Physical path:"), pathField(physicalPath));
        if (!dialogs.confirmForm(text("dialog.addVirtualDirectory", "New virtual directory"), form,
                text("action.create", "Create"))) {
            return;
        }
        if (physicalPath.getText().isBlank()) {
            warn(text("warn.physicalPath", "Provide the physical path."));
            return;
        }
        Path root = Path.of(physicalPath.getText().strip());
        String applicationName = String.valueOf(application.getSelectedItem());
        execute(() -> IisService.addVirtualDirectory(applicationName, path.getText().strip(), root));
    }

    private void changeVirtualDirectoryPath() {
        IisVirtualDirectory directory = selectedVirtualDirectory();
        if (directory == null) {
            warn(text("warn.selectVirtualDirectory", "Select a virtual directory."));
            return;
        }
        JTextField physicalPath = new JTextField(directory.physicalPath() == null ? "" : directory.physicalPath());
        JPanel form = form(text("field.physicalPath", "Physical path:"), pathField(physicalPath));
        if (!dialogs.confirmForm(text("dialog.physicalPath", "Physical path"), form,
                text("action.apply", "Apply"))) {
            return;
        }
        if (physicalPath.getText().isBlank()) {
            return;
        }
        Path root = Path.of(physicalPath.getText().strip());
        execute(() -> IisService.setVirtualDirectoryPath(directory.name(), root));
    }

    private void removeVirtualDirectory() {
        IisVirtualDirectory directory = selectedVirtualDirectory();
        if (directory == null) {
            warn(text("warn.selectVirtualDirectory", "Select a virtual directory."));
            return;
        }
        if (!confirmDelete(directory.name())) {
            return;
        }
        execute(() -> IisService.deleteVirtualDirectory(directory.name()));
    }

    private void attachToSelectedProcess() {
        int row = processTable.getSelectedRow();
        if (row < 0 || row >= processes.size()) {
            warn(text("warn.selectProcess", "Select a worker process."));
            return;
        }
        if (attachDebugger == null) {
            return;
        }
        attachDebugger.accept(processes.get(row).pid());
    }

    private String[] poolNames() {
        List<String> names = new ArrayList<>();
        for (IisAppPool pool : pools) {
            names.add(pool.name());
        }
        return names.toArray(new String[0]);
    }

    private String[] applicationNames(IisSite site) {
        List<String> names = new ArrayList<>();
        for (IisApplication application : applications) {
            if (site.name().equals(application.siteName())) {
                names.add(application.name());
            }
        }
        if (names.isEmpty()) {
            names.add(site.name() + "/");
        }
        return names.toArray(new String[0]);
    }

    private JPanel pathField(JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        JButton browse = secondaryButton("...", () -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (!field.getText().isBlank()) {
                chooser.setCurrentDirectory(new java.io.File(IisService.expandEnvironment(field.getText())));
            }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        browse.setPreferredSize(new Dimension(42, 28));
        panel.add(field, BorderLayout.CENTER);
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    private JPanel form(Object... labelsAndFields) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        for (int i = 0; i + 1 < labelsAndFields.length; i += 2) {
            gbc.gridx = 0;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.insets = new Insets(0, 0, 10, 12);
            panel.add(new JLabel(String.valueOf(labelsAndFields[i])), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(0, 0, 10, 0);
            Component field = (Component) labelsAndFields[i + 1];
            field.setPreferredSize(new Dimension(300, 30));
            panel.add(field, gbc);
            gbc.gridy++;
        }
        return panel;
    }

    private boolean confirmDelete(String name) {
        return dialogs.confirmDelete(text("dialog.confirm", "Confirm"),
                text("confirm.delete", "Remove") + " \"" + name + "\"?");
    }

    private void warn(String message) {
        dialogs.message(text("dialog.attention", "Attention"), message, false);
    }

    private void execute(Supplier<IisService.Result> action) {
        statusLabel.setText(text("status.applying", "Applying changes to IIS..."));
        executor.execute(() -> {
            IisService.Result result;
            try {
                result = action.get();
            } catch (Exception e) {
                result = IisService.Result.fail(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            IisService.Result outcome = result;
            SwingUtilities.invokeLater(() -> {
                if (!outcome.success()) {
                    dialogs.message(text("dialog.error", "IIS error"), outcome.message(), true);
                }
                refresh();
            });
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static Font smallFont(Font font) {
        return font.deriveFont(font.getSize2D() - 1f);
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

    private static final class StatusBadge extends JLabel {

        private Color accent = mutedForeground();

        private StatusBadge() {
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(4, 22, 4, 12));
            setFont(smallFont(getFont()).deriveFont(Font.BOLD));
        }

        private void update(String label, Color color) {
            this.accent = color;
            setText(label);
            setForeground(color);
            setToolTipText(label);
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D graphics = (java.awt.Graphics2D) g.create();
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 28));
            graphics.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 110));
            graphics.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            int diameter = 8;
            graphics.setColor(accent);
            graphics.fillOval(9, (getHeight() - diameter) / 2, diameter, diameter);
            graphics.dispose();
            super.paintComponent(g);
        }
    }

    private final class TabButton extends JButton {

        private final Supplier<JComponent> contentFactory;
        private boolean active;

        private TabButton(String label, Supplier<JComponent> contentFactory) {
            super(label);
            this.contentFactory = contentFactory;
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(true);
            refreshStyle();
        }

        private JComponent createContent() {
            return contentFactory.get();
        }

        private void setActive(boolean active) {
            this.active = active;
            refreshStyle();
        }

        private void refreshStyle() {
            setForeground(active ? ACCENT : mutedForeground());
            setFont(getFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, active ? ACCENT : new Color(0, 0, 0, 0)),
                    BorderFactory.createEmptyBorder(6, 14, 6, 14)));
        }
    }

    private static final class CellRenderer extends DefaultTableCellRenderer {

        private final int stateColumn;

        private CellRenderer(int stateColumn) {
            this.stateColumn = stateColumn;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            if (!isSelected) {
                setBackground(row % 2 == 0 ? table.getBackground() : alternateRow(table));
                setForeground(column == stateColumn ? stateColor(value) : table.getForeground());
            }
            return this;
        }

        private static Color alternateRow(JTable table) {
            Color color = UIManager.getColor("Table.alternateRowColor");
            if (color != null) {
                return color;
            }
            Color base = table.getBackground();
            boolean dark = (base.getRed() + base.getGreen() + base.getBlue()) / 3 < 128;
            int delta = dark ? 12 : -8;
            return new Color(
                    Math.clamp(base.getRed() + delta, 0, 255),
                    Math.clamp(base.getGreen() + delta, 0, 255),
                    Math.clamp(base.getBlue() + delta, 0, 255));
        }

        private static Color stateColor(Object value) {
            String state = value == null ? "" : value.toString().toLowerCase(Locale.ROOT);
            if (state.startsWith("start")) {
                return SUCCESS;
            }
            if (state.startsWith("stop")) {
                return DANGER;
            }
            return mutedForeground();
        }
    }

    private static final class HeaderRenderer extends DefaultTableCellRenderer {

        private final javax.swing.table.TableCellRenderer delegate;

        private HeaderRenderer(javax.swing.table.TableCellRenderer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component component = delegate.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (component instanceof JLabel label) {
                label.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
                label.setHorizontalAlignment(SwingConstants.LEFT);
                label.setForeground(mutedForeground());
            }
            return component;
        }
    }

    private static final class ReadOnlyTableModel extends DefaultTableModel {

        private ReadOnlyTableModel(String[] columns) {
            super(columns, 0);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }
}
