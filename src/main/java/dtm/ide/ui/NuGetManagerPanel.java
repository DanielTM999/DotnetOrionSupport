package dtm.ide.ui;

import dtm.ide.nuget.NuGetClient;
import dtm.ide.nuget.NuGetConfig;
import dtm.ide.nuget.NuGetService;
import dtm.ide.nuget.NuGetSource;
import dtm.ide.nuget.models.InstalledPackage;
import dtm.ide.nuget.models.NuGetPackage;
import dtm.ide.run.TargetFramework;
import dtm.ide.sdk.DotnetSdkService;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
public final class NuGetManagerPanel extends JPanel {

    private enum Tab {BROWSE, INSTALLED, UPDATES}

    private final Supplier<Path> projectSupplier;
    private final Supplier<DotnetSdkService> sdkSupplier;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nuget-manager");
        t.setDaemon(true);
        return t;
    });
    private final NuGetIconCache iconCache = new NuGetIconCache();
    private final Map<String, Icon> letterIcons = new ConcurrentHashMap<>();

    private final JComboBox<NuGetSource> sourceCombo = new JComboBox<>();
    private final ProjectSelector projectSelector = new ProjectSelector();
    private final MaskedTextField searchField = new MaskedTextField();
    private final JCheckBox prereleaseCheck = new JCheckBox("Incluir prerelease");
    private final JToggleButton browseTab = new JToggleButton("Browse", true);
    private final JToggleButton installedTab = new JToggleButton("Installed");
    private final JToggleButton updatesTab = new JToggleButton("Updates");

    private final DefaultListModel<Object> listModel = new DefaultListModel<>();
    private final JList<Object> packageList = new JList<>(listModel);

    private final JLabel detailIcon = new JLabel();
    private final JLabel titleLabel = new JLabel(" ");
    private final JLabel authorsLabel = new JLabel(" ");
    private final JTextArea descriptionArea = new JTextArea();
    private final JComboBox<String> versionCombo = new JComboBox<>();
    private final JButton installButton = new JButton("Instalar");
    private final JButton uninstallButton = new JButton("Desinstalar");
    private final JLabel statusLabel = new JLabel(" ");

    private Tab currentTab = Tab.BROWSE;
    private volatile boolean multiProject;
    private volatile Map<String, List<InstalledPackage>> installedIndex = Map.of();

    public NuGetManagerPanel(Supplier<Path> projectSupplier, Supplier<DotnetSdkService> sdkSupplier) {
        super(new BorderLayout(0, 8));
        this.projectSupplier = projectSupplier;
        this.sdkSupplier = sdkSupplier;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        wireEvents();
        reloadSources();
        reloadProjects();
        switchTab(Tab.BROWSE);
    }

    private JComponent buildToolbar() {
        ButtonGroup group = new ButtonGroup();
        group.add(browseTab);
        group.add(installedTab);
        group.add(updatesTab);

        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabs.add(styleTab(browseTab));
        tabs.add(styleTab(installedTab));
        tabs.add(styleTab(updatesTab));

        sourceCombo.setPreferredSize(new Dimension(230, 26));
        sourceCombo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = (JLabel) new DefaultListCellRenderer()
                    .getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof NuGetSource source) {
                label.setText(source.name());
            }
            return label;
        });

        JButton manageSources = new JButton("Gerenciar...");
        manageSources.setToolTipText("Gerenciar fontes de pacotes");
        manageSources.addActionListener(e -> manageSources());

        JPanel sourcePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        sourcePanel.add(new JLabel("Origem do pacote:"));
        sourcePanel.add(sourceCombo);
        sourcePanel.add(manageSources);

        JPanel commandRow = new JPanel(new BorderLayout(8, 0));
        commandRow.add(tabs, BorderLayout.WEST);
        commandRow.add(sourcePanel, BorderLayout.EAST);

        projectSelector.setPreferredSize(new Dimension(280, 26));
        projectSelector.setToolTipText("Projetos alvo das instalacoes (marque um ou mais)");

        JPanel projectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        projectRow.add(new JLabel("Projeto:"));
        projectRow.add(projectSelector);

        searchField.setToolTipText("Buscar pacotes");
        searchField.setPlaceholder("Buscar pacotes no NuGet (ex: Newtonsoft.Json)");
        JButton searchButton = new JButton("Buscar");
        searchButton.addActionListener(e -> refreshCurrentTab());

        JPanel searchPanel = new JPanel(new BorderLayout(6, 0));
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchButton, BorderLayout.EAST);

        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        searchRow.add(searchPanel, BorderLayout.CENTER);
        searchRow.add(prereleaseCheck, BorderLayout.EAST);

        JPanel lower = new JPanel(new BorderLayout(0, 8));
        lower.add(projectRow, BorderLayout.NORTH);
        lower.add(searchRow, BorderLayout.SOUTH);

        JPanel toolbar = new JPanel(new BorderLayout(0, 8));
        toolbar.add(commandRow, BorderLayout.NORTH);
        toolbar.add(lower, BorderLayout.CENTER);
        return toolbar;
    }

    private JComponent buildBody() {
        packageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        packageList.setFixedCellHeight(58);
        packageList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            row.setOpaque(true);
            row.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());

            JLabel icon = new JLabel(rowIcon(value));
            icon.setVerticalAlignment(SwingConstants.CENTER);
            icon.setPreferredSize(new Dimension(36, 36));

            JLabel title = new JLabel(rowTitle(value));
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            title.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());

            JLabel meta = new JLabel(rowMeta(value));
            meta.setFont(meta.getFont().deriveFont(meta.getFont().getSize2D() - 1f));
            meta.setForeground(isSelected ? list.getSelectionForeground() : mutedForeground());

            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.add(leftAligned(title));
            text.add(Box.createVerticalStrut(2));
            text.add(leftAligned(meta));

            JPanel center = new JPanel(new GridBagLayout());
            center.setOpaque(false);
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1;
            gbc.anchor = GridBagConstraints.WEST;
            center.add(text, gbc);

            row.add(icon, BorderLayout.WEST);
            row.add(center, BorderLayout.CENTER);
            return row;
        });

        packageList.setBorder(BorderFactory.createEmptyBorder());
        JScrollPane listScroll = new JScrollPane(packageList);
        listScroll.setPreferredSize(new Dimension(410, 420));
        listScroll.setBorder(BorderFactory.createLineBorder(borderColor(), 1, true));
        UiSupport.styleScroll(listScroll);

        JPanel details = new JPanel(new BorderLayout(0, 10));
        details.setBorder(BorderFactory.createEmptyBorder(2, 14, 2, 2));

        titleLabel.setFont(titleLabel.getFont().deriveFont(titleLabel.getFont().getSize2D() + 5f).deriveFont(Font.BOLD));
        authorsLabel.setForeground(mutedForeground());

        JPanel titleText = new JPanel();
        titleText.setLayout(new BoxLayout(titleText, BoxLayout.Y_AXIS));
        titleText.add(leftAligned(titleLabel));
        titleText.add(Box.createVerticalStrut(3));
        titleText.add(leftAligned(authorsLabel));

        detailIcon.setPreferredSize(new Dimension(40, 40));
        detailIcon.setVerticalAlignment(SwingConstants.TOP);

        JPanel titleBlock = new JPanel(new BorderLayout(10, 0));
        titleBlock.add(detailIcon, BorderLayout.WEST);
        titleBlock.add(titleText, BorderLayout.CENTER);

        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setOpaque(false);
        descriptionArea.setRows(9);

        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        descriptionScroll.setBorder(BorderFactory.createTitledBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor()),
                "Descricao"));
        UiSupport.styleScroll(descriptionScroll);

        JPanel versionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        versionRow.add(new JLabel("Versao:"));
        versionCombo.setPreferredSize(new Dimension(190, 26));
        versionRow.add(versionCombo);
        versionRow.add(installButton);
        versionRow.add(uninstallButton);

        details.add(titleBlock, BorderLayout.NORTH);
        details.add(descriptionScroll, BorderLayout.CENTER);
        details.add(versionRow, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, details);
        split.setResizeWeight(0);
        split.setDividerLocation(410);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private JComponent buildStatusBar() {
        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor()));
        status.add(statusLabel, BorderLayout.CENTER);
        return status;
    }

    private static JComponent leftAligned(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private static JToggleButton styleTab(JToggleButton button) {
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, borderColor()),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        return button;
    }

    private void wireEvents() {
        browseTab.addActionListener(e -> switchTab(Tab.BROWSE));
        installedTab.addActionListener(e -> switchTab(Tab.INSTALLED));
        updatesTab.addActionListener(e -> switchTab(Tab.UPDATES));
        searchField.addActionListener(e -> refreshCurrentTab());
        prereleaseCheck.addActionListener(e -> refreshCurrentTab());
        sourceCombo.addActionListener(e -> {
            if (currentTab == Tab.BROWSE || currentTab == Tab.UPDATES) {
                refreshCurrentTab();
            }
        });
        projectSelector.setOnChange(() -> {
            if (currentTab == Tab.BROWSE) {
                refreshInstalledIndex();
            } else {
                refreshCurrentTab();
            }
        });
        packageList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onSelection();
            }
        });
        installButton.addActionListener(e -> onInstallOrUpdate());
        uninstallButton.addActionListener(e -> onUninstall());
    }

    private void switchTab(Tab tab) {
        currentTab = tab;
        browseTab.setSelected(tab == Tab.BROWSE);
        installedTab.setSelected(tab == Tab.INSTALLED);
        updatesTab.setSelected(tab == Tab.UPDATES);
        installButton.setText(tab == Tab.UPDATES ? "Atualizar" : "Instalar");
        searchField.setEnabled(tab != Tab.INSTALLED);
        reloadProjects();
        refreshCurrentTab();
        if (tab == Tab.BROWSE) {
            refreshInstalledIndex();
        }
    }

    public void refreshCurrentTab() {
        switch (currentTab) {
            case BROWSE -> loadBrowse();
            case INSTALLED -> loadInstalled();
            case UPDATES -> loadUpdates();
        }
    }

    public void focusProject(Path target) {
        reloadProjects();
        if (target != null && isSolutionFile(target)) {
            projectSelector.selectAll();
        } else if (target != null) {
            projectSelector.selectOnly(target);
        }
        refreshCurrentTab();
        if (currentTab == Tab.BROWSE) {
            refreshInstalledIndex();
        }
    }

    private static boolean isSolutionFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sln") || name.endsWith(".slnx");
    }

    private void loadBrowse() {
        NuGetSource source = (NuGetSource) sourceCombo.getSelectedItem();
        if (source == null) {
            return;
        }
        boolean prerelease = prereleaseCheck.isSelected();
        String query = searchField.getText();
        setStatus("Buscando em " + source.name() + "...");
        clearList();
        executor.execute(() -> {
            List<NuGetPackage> packages = NuGetClient.forSource(source).search(query, prerelease, 0, 50);
            SwingUtilities.invokeLater(() -> {
                populate(packages.toArray());
                setStatus(packages.isEmpty() ? "Nenhum pacote encontrado." : packages.size() + " pacotes.");
            });
        });
    }

    private void loadInstalled() {
        List<Path> targets = installedScopeProjects();
        if (targets.isEmpty()) {
            setStatus("Abra um projeto .NET para ver os pacotes instalados.");
            clearList();
            return;
        }
        Path project = project();
        DotnetSdkService sdk = sdk();
        setStatus("Carregando pacotes instalados...");
        clearList();
        executor.execute(() -> {
            List<InstalledPackage> installed = new ArrayList<>();
            for (Path file : targets) {
                installed.addAll(new NuGetService(project, file, sdk).listInstalled());
            }
            SwingUtilities.invokeLater(() -> {
                populate(installed.toArray());
                setStatus(installed.isEmpty() ? "Nenhum pacote instalado." : installed.size() + " pacotes instalados.");
            });
        });
    }

    private void loadUpdates() {
        List<Path> targets = installedScopeProjects();
        if (targets.isEmpty()) {
            setStatus("Abra um projeto .NET para ver atualizacoes.");
            clearList();
            return;
        }
        Path project = project();
        DotnetSdkService sdk = sdk();
        NuGetSource source = (NuGetSource) sourceCombo.getSelectedItem();
        boolean prerelease = prereleaseCheck.isSelected();
        setStatus("Procurando atualizacoes...");
        clearList();
        executor.execute(() -> {
            NuGetClient client = NuGetClient.forSource(source == null ? NuGetSource.nugetOrg() : source);
            List<Object> updatable = new ArrayList<>();
            for (Path file : targets) {
                for (InstalledPackage pkg : new NuGetService(project, file, sdk).listInstalled()) {
                    String latest = client.latestVersion(pkg.id(), prerelease);
                    if (latest != null && !latest.equalsIgnoreCase(pkg.version())) {
                        updatable.add(new UpdateRow(pkg, latest));
                    }
                }
            }
            SwingUtilities.invokeLater(() -> {
                populate(updatable.toArray());
                setStatus(updatable.isEmpty() ? "Tudo atualizado." : updatable.size() + " atualizacoes disponiveis.");
            });
        });
    }

    private void onSelection() {
        Object selected = packageList.getSelectedValue();
        if (selected == null) {
            clearDetails();
            return;
        }
        detailIcon.setIcon(rowIcon(selected));
        if (selected instanceof NuGetPackage pkg) {
            titleLabel.setText(pkg.displayTitle());
            descriptionArea.setText(pkg.description());
            installButton.setEnabled(true);
            applyInstalledState(pkg);
            loadVersionsFor(pkg);
        } else if (selected instanceof InstalledPackage pkg) {
            titleLabel.setText(pkg.id());
            String mode = pkg.mode() == InstalledPackage.Mode.PACKAGES_CONFIG
                    ? "packages.config (legado)" : "PackageReference (SDK-style)";
            authorsLabel.setText("Projeto " + projectName(pkg.projectFile()) + " | " + mode);
            descriptionArea.setText("Versao instalada: " + pkg.version());
            versionCombo.setModel(new DefaultComboBoxModel<>(new String[]{pkg.version()}));
            uninstallButton.setEnabled(true);
            installButton.setEnabled(false);
        } else if (selected instanceof UpdateRow row) {
            titleLabel.setText(row.installed().id());
            authorsLabel.setText("Projeto " + projectName(row.installed().projectFile())
                    + " | " + row.installed().version() + " -> " + row.latest());
            descriptionArea.setText("Atualizar de " + row.installed().version() + " para " + row.latest() + "?");
            versionCombo.setModel(new DefaultComboBoxModel<>(new String[]{row.latest()}));
            uninstallButton.setEnabled(true);
            installButton.setEnabled(true);
        }
    }

    private void loadVersionsFor(NuGetPackage pkg) {
        boolean prerelease = prereleaseCheck.isSelected();
        List<String> embedded = filterVersions(pkg.versions(), prerelease);
        if (!embedded.isEmpty()) {
            versionCombo.setModel(new DefaultComboBoxModel<>(embedded.toArray(new String[0])));
            return;
        }
        versionCombo.setModel(new DefaultComboBoxModel<>(new String[]{"carregando..."}));
        NuGetClient client = currentClient();
        String id = pkg.id();
        executor.execute(() -> {
            List<String> versions = client.versions(id, prerelease);
            SwingUtilities.invokeLater(() -> {
                if (packageList.getSelectedValue() == pkg) {
                    versionCombo.setModel(new DefaultComboBoxModel<>(versions.toArray(new String[0])));
                }
            });
        });
    }

    private static List<String> filterVersions(List<String> versions, boolean prerelease) {
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String version : versions) {
            if (version == null || version.isBlank()) {
                continue;
            }
            if (!prerelease && version.contains("-")) {
                continue;
            }
            out.add(version);
        }
        return out;
    }

    private void onInstallOrUpdate() {
        Object selected = packageList.getSelectedValue();
        Path project = project();
        if (project == null) {
            setStatus("Abra um projeto .NET primeiro.");
            return;
        }
        String id;
        List<Path> targets;
        if (selected instanceof NuGetPackage pkg) {
            id = pkg.id();
            targets = installTargetProjects();
        } else if (selected instanceof UpdateRow row) {
            id = row.installed().id();
            targets = row.installed().projectFile() != null
                    ? List.of(row.installed().projectFile())
                    : installTargetProjects();
        } else {
            return;
        }
        if (targets.isEmpty()) {
            setStatus("Marque ao menos um projeto de destino para instalar " + id + ".");
            return;
        }
        String version = (String) versionCombo.getSelectedItem();
        setBusy(true);
        setStatus("Instalando " + id + (version == null ? "" : " " + version)
                + " em " + targets.size() + " projeto(s)...");
        DotnetSdkService sdk = sdk();
        executor.execute(() -> {
            String message = runForEach(targets, target ->
                    new NuGetService(project, target, sdk).install(id, version));
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                setStatus(message);
                afterOperation();
            });
        });
    }

    private String runForEach(List<Path> targets, java.util.function.Function<Path, NuGetService.OperationResult> action) {
        if (targets.size() == 1) {
            return action.apply(targets.get(0)).message();
        }
        StringBuilder out = new StringBuilder();
        for (Path target : targets) {
            NuGetService.OperationResult result = action.apply(target);
            if (out.length() > 0) {
                out.append("   ");
            }
            out.append(projectName(target)).append(": ").append(result.message());
        }
        return out.toString();
    }

    private void onUninstall() {
        Object selected = packageList.getSelectedValue();
        Path project = project();
        if (project == null) {
            return;
        }
        String id;
        List<Path> targets;
        if (selected instanceof InstalledPackage pkg) {
            id = pkg.id();
            targets = pkg.projectFile() != null ? List.of(pkg.projectFile()) : oneOrNone(firstSelectedProject());
        } else if (selected instanceof UpdateRow row) {
            id = row.installed().id();
            targets = row.installed().projectFile() != null
                    ? List.of(row.installed().projectFile())
                    : oneOrNone(firstSelectedProject());
        } else if (selected instanceof NuGetPackage pkg) {
            id = pkg.id();
            targets = installedProjectsFor(id);
        } else {
            return;
        }
        if (targets.isEmpty()) {
            setStatus(id + " nao esta instalado nos projetos selecionados.");
            return;
        }
        setBusy(true);
        setStatus("Removendo " + id + " de " + targets.size() + " projeto(s)...");
        DotnetSdkService sdk = sdk();
        executor.execute(() -> {
            String message = runForEach(targets, target ->
                    new NuGetService(project, target, sdk).uninstall(id));
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                setStatus(message);
                afterOperation();
            });
        });
    }

    private void afterOperation() {
        if (currentTab == Tab.BROWSE) {
            refreshInstalledIndex();
        } else {
            refreshCurrentTab();
        }
    }

    private void refreshInstalledIndex() {
        List<Path> targets = installedScopeProjects();
        Path project = project();
        DotnetSdkService sdk = sdk();
        executor.execute(() -> {
            Map<String, List<InstalledPackage>> index = new HashMap<>();
            for (Path file : targets) {
                for (InstalledPackage pkg : new NuGetService(project, file, sdk).listInstalled()) {
                    index.computeIfAbsent(pkg.id().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(pkg);
                }
            }
            installedIndex = index;
            SwingUtilities.invokeLater(() -> {
                if (currentTab == Tab.BROWSE && packageList.getSelectedValue() instanceof NuGetPackage pkg) {
                    applyInstalledState(pkg);
                }
            });
        });
    }

    private List<Path> installedProjectsFor(String id) {
        List<Path> out = new ArrayList<>();
        for (InstalledPackage pkg : installedIndex.getOrDefault(id.toLowerCase(Locale.ROOT), List.of())) {
            if (pkg.projectFile() != null && !out.contains(pkg.projectFile())) {
                out.add(pkg.projectFile());
            }
        }
        return out;
    }

    private static List<Path> oneOrNone(Path file) {
        return file == null ? List.of() : List.of(file);
    }

    private void manageSources() {
        SourceManagementDialog dialog = new SourceManagementDialog(
                SwingUtilities.getWindowAncestor(this), new NuGetConfig(project()));
        dialog.setVisible(true);
        if (dialog.changed()) {
            reloadSources();
            setStatus("Fontes NuGet atualizadas.");
            refreshCurrentTab();
        }
    }

    private void reloadSources() {
        Object selected = sourceCombo.getSelectedItem();
        String selectedName = selected instanceof NuGetSource source ? source.name() : null;
        List<NuGetSource> sources = new NuGetConfig(project()).listSources();
        DefaultComboBoxModel<NuGetSource> model = new DefaultComboBoxModel<>();
        NuGetSource fallback = null;
        for (NuGetSource source : sources) {
            if (source.enabled()) {
                model.addElement(source);
                if (fallback == null) {
                    fallback = source;
                }
                if (source.name().equalsIgnoreCase(selectedName)) {
                    fallback = source;
                }
            }
        }
        if (model.getSize() == 0) {
            fallback = NuGetSource.nugetOrg();
            model.addElement(fallback);
        }
        sourceCombo.setModel(model);
        sourceCombo.setSelectedItem(fallback);
    }

    private void reloadProjects() {
        List<Path> files = allProjectFiles();
        multiProject = files.size() > 1;
        projectSelector.setProjects(files);
    }

    private List<Path> allProjectFiles() {
        return TargetFramework.findProjectFiles(project());
    }

    private List<Path> installedScopeProjects() {
        return projectSelector.selectedFiles();
    }

    private List<Path> installTargetProjects() {
        return projectSelector.selectedFiles();
    }

    private Path firstSelectedProject() {
        List<Path> files = projectSelector.selectedFiles();
        return files.isEmpty() ? null : files.get(0);
    }

    private void onIconLoaded() {
        packageList.repaint();
        Object selected = packageList.getSelectedValue();
        if (selected != null) {
            detailIcon.setIcon(rowIcon(selected));
        }
    }

    private void applyInstalledState(NuGetPackage pkg) {
        List<InstalledPackage> here = installedIndex.getOrDefault(pkg.id().toLowerCase(Locale.ROOT), List.of());
        uninstallButton.setEnabled(!here.isEmpty());
        String author = pkg.authors() == null || pkg.authors().isBlank() ? "" : "por " + pkg.authors();
        if (here.isEmpty()) {
            authorsLabel.setText(author.isBlank() ? " " : author);
            return;
        }
        String where = here.stream()
                .map(ip -> projectName(ip.projectFile()) + " " + ip.version())
                .distinct()
                .collect(Collectors.joining(", "));
        authorsLabel.setText((author.isBlank() ? "" : author + "   |   ") + "instalado: " + where);
    }

    private Icon rowIcon(Object value) {
        if (value instanceof NuGetPackage pkg) {
            Icon icon = iconCache.iconFor(pkg.iconUrl(), this::onIconLoaded);
            return icon != null ? icon : letterIcon(pkg.displayTitle());
        }
        if (value instanceof InstalledPackage pkg) {
            return installedIcon(pkg.id());
        }
        if (value instanceof UpdateRow row) {
            return installedIcon(row.installed().id());
        }
        return letterIcon(String.valueOf(value));
    }

    private Icon installedIcon(String id) {
        NuGetClient client = currentClient();
        Icon icon = iconCache.iconForId(id, () -> client.iconUrl(id), this::onIconLoaded);
        return icon != null ? icon : letterIcon(id);
    }

    private NuGetClient currentClient() {
        NuGetSource source = (NuGetSource) sourceCombo.getSelectedItem();
        return NuGetClient.forSource(source == null ? NuGetSource.nugetOrg() : source);
    }

    private Icon letterIcon(String text) {
        String key = text == null || text.isBlank() ? "?" : text.trim().substring(0, 1).toUpperCase();
        return letterIcons.computeIfAbsent(key, NuGetManagerPanel::makeLetterIcon);
    }

    private static Icon makeLetterIcon(String letter) {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(colorFor(letter));
        g.fillRoundRect(0, 0, size, size, 10, 10);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Dialog", Font.BOLD, 16));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(letter);
        g.drawString(letter, (size - tw) / 2, (size + fm.getAscent()) / 2 - 2);
        g.dispose();
        return new ImageIcon(image);
    }

    private static Color colorFor(String key) {
        int hash = key.hashCode();
        float hue = (((hash % 360) + 360) % 360) / 360f;
        return Color.getHSBColor(hue, 0.5f, 0.72f);
    }

    private static String projectName(Path file) {
        return ProjectSelector.projectName(file);
    }

    private String rowTitle(Object value) {
        if (value instanceof NuGetPackage pkg) {
            return pkg.displayTitle();
        }
        if (value instanceof InstalledPackage pkg) {
            return pkg.id();
        }
        if (value instanceof UpdateRow row) {
            return row.installed().id();
        }
        return String.valueOf(value);
    }

    private String rowMeta(Object value) {
        if (value instanceof NuGetPackage pkg) {
            return pkg.version() + " | " + formatDownloads(pkg.totalDownloads()) + authorSuffix(pkg.authors());
        }
        if (value instanceof InstalledPackage pkg) {
            String mode = pkg.mode() == InstalledPackage.Mode.PACKAGES_CONFIG ? "packages.config" : "PackageReference";
            String prefix = multiProject ? projectName(pkg.projectFile()) + " | " : "";
            return prefix + pkg.version() + " | " + mode;
        }
        if (value instanceof UpdateRow row) {
            String prefix = multiProject ? projectName(row.installed().projectFile()) + " | " : "";
            return prefix + row.installed().version() + " -> " + row.latest();
        }
        return "";
    }

    private static String authorSuffix(String authors) {
        if (authors == null || authors.isBlank()) {
            return "";
        }
        return " | " + authors;
    }

    private static String formatDownloads(long downloads) {
        if (downloads >= 1_000_000) {
            return (downloads / 1_000_000) + "M downloads";
        }
        if (downloads >= 1_000) {
            return (downloads / 1_000) + "K downloads";
        }
        return downloads + " downloads";
    }

    private void populate(Object[] items) {
        listModel.clear();
        for (Object item : items) {
            listModel.addElement(item);
        }
        if (listModel.getSize() > 0) {
            packageList.setSelectedIndex(0);
        } else {
            clearDetails();
        }
    }

    private void clearList() {
        listModel.clear();
        clearDetails();
    }

    private void clearDetails() {
        detailIcon.setIcon(null);
        titleLabel.setText(" ");
        authorsLabel.setText(" ");
        descriptionArea.setText("");
        versionCombo.setModel(new DefaultComboBoxModel<>());
        installButton.setEnabled(false);
        uninstallButton.setEnabled(false);
    }

    private void setBusy(boolean busy) {
        packageList.setEnabled(!busy);
        installButton.setEnabled(!busy);
        uninstallButton.setEnabled(!busy);
    }

    private void setStatus(String message) {
        statusLabel.setText(message == null ? " " : " " + message);
    }

    private Path project() {
        return projectSupplier == null ? null : projectSupplier.get();
    }

    private DotnetSdkService sdk() {
        return sdkSupplier == null ? null : sdkSupplier.get();
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

    private record UpdateRow(InstalledPackage installed, String latest) {
    }

    private static final class SourceManagementDialog extends JDialog {

        private final NuGetConfig config;
        private final SourceTableModel tableModel = new SourceTableModel();
        private final JTable table = new JTable(tableModel);
        private final JTextField nameField = new JTextField();
        private final JTextField urlField = new JTextField();
        private final JCheckBox enabledField = new JCheckBox("Ativa", true);
        private final JLabel messageLabel = new JLabel(" ");
        private final JButton addButton = new JButton("Adicionar");
        private final JButton updateButton = new JButton("Atualizar");
        private final JButton removeButton = new JButton("Remover");
        private boolean changed;

        private SourceManagementDialog(Window owner, NuGetConfig config) {
            super(owner, "Gerenciar fontes NuGet", Dialog.ModalityType.APPLICATION_MODAL);
            this.config = config;
            setContentPane(buildContent());
            setMinimumSize(new Dimension(720, 430));
            setPreferredSize(new Dimension(760, 480));
            pack();
            setLocationRelativeTo(owner);
            wireEvents();
            reloadSources(null);
        }

        private JComponent buildContent() {
            JPanel root = new JPanel(new BorderLayout(10, 10));
            root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setRowHeight(24);
            table.setFillsViewportHeight(true);
            table.getColumnModel().getColumn(0).setPreferredWidth(55);
            table.getColumnModel().getColumn(1).setPreferredWidth(150);
            table.getColumnModel().getColumn(2).setPreferredWidth(360);
            table.getColumnModel().getColumn(3).setPreferredWidth(90);

            JScrollPane tableScroll = new JScrollPane(table);
            tableScroll.setBorder(BorderFactory.createTitledBorder("Fontes de pacote"));
            UiSupport.styleScroll(tableScroll);

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createTitledBorder("Detalhes"));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            addFormRow(form, gbc, 0, "Nome:", nameField);
            addFormRow(form, gbc, 1, "Origem:", urlField);

            gbc.gridx = 1;
            gbc.gridy = 2;
            gbc.weightx = 1;
            form.add(enabledField, gbc);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            buttons.add(addButton);
            buttons.add(updateButton);
            buttons.add(removeButton);

            JPanel bottom = new JPanel(new BorderLayout(8, 8));
            bottom.add(form, BorderLayout.CENTER);
            bottom.add(buttons, BorderLayout.SOUTH);

            JButton close = new JButton("Fechar");
            close.addActionListener(e -> dispose());
            JPanel footer = new JPanel(new BorderLayout(8, 0));
            footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor()));
            footer.add(messageLabel, BorderLayout.CENTER);
            footer.add(close, BorderLayout.EAST);

            JPanel lower = new JPanel(new BorderLayout(0, 8));
            lower.add(bottom, BorderLayout.CENTER);
            lower.add(footer, BorderLayout.SOUTH);

            root.add(tableScroll, BorderLayout.CENTER);
            root.add(lower, BorderLayout.SOUTH);
            return root;
        }

        private static void addFormRow(JPanel form, GridBagConstraints gbc, int row, String label, JComponent field) {
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            form.add(new JLabel(label), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            form.add(field, gbc);
        }

        private void wireEvents() {
            table.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    onSelection();
                }
            });
            addButton.addActionListener(e -> addSource());
            updateButton.addActionListener(e -> updateSource());
            removeButton.addActionListener(e -> removeSource());
        }

        private void reloadSources(String selectName) {
            tableModel.setSources(config.listSources());
            if (tableModel.getRowCount() == 0) {
                clearForm();
                return;
            }
            int selected = 0;
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if (tableModel.sourceAt(i).name().equalsIgnoreCase(selectName)) {
                    selected = i;
                    break;
                }
            }
            table.setRowSelectionInterval(selected, selected);
            onSelection();
        }

        private void onSelection() {
            NuGetSource source = selectedSource();
            if (source == null) {
                clearForm();
                return;
            }
            nameField.setText(source.name());
            urlField.setText(source.url());
            enabledField.setSelected(source.enabled());
            boolean readOnly = source.isNugetOrg();
            enabledField.setEnabled(!readOnly);
            updateButton.setEnabled(!readOnly);
            removeButton.setEnabled(!readOnly);
            messageLabel.setText(readOnly
                    ? " nuget.org e a fonte padrao e nao pode ser alterada ou removida."
                    : " ");
        }

        private void clearForm() {
            nameField.setText("");
            urlField.setText("");
            enabledField.setSelected(true);
            enabledField.setEnabled(true);
            updateButton.setEnabled(false);
            removeButton.setEnabled(false);
            messageLabel.setText(" ");
        }

        private void addSource() {
            SourceFields fields = readFields();
            if (fields == null) {
                return;
            }
            if (tableModel.findByName(fields.name()) != null) {
                messageLabel.setText(" Fonte ja existe. Selecione a linha e use Atualizar.");
                return;
            }
            try {
                config.addOrUpdateSource(fields.name(), fields.url());
                config.setEnabled(fields.name(), fields.enabled());
                changed = true;
                reloadSources(fields.name());
                messageLabel.setText(" Fonte adicionada.");
            } catch (Exception e) {
                messageLabel.setText(" Falha ao adicionar fonte: " + e.getMessage());
            }
        }

        private void updateSource() {
            NuGetSource selected = selectedSource();
            SourceFields fields = readFields();
            if (selected == null || fields == null) {
                return;
            }
            if (selected.isNugetOrg()) {
                messageLabel.setText(" nuget.org e somente leitura.");
                return;
            }
            try {
                if (!selected.name().equalsIgnoreCase(fields.name())) {
                    config.removeSource(selected.name());
                }
                config.addOrUpdateSource(fields.name(), fields.url());
                config.setEnabled(fields.name(), fields.enabled());
                changed = true;
                reloadSources(fields.name());
                messageLabel.setText(" Fonte atualizada.");
            } catch (Exception e) {
                messageLabel.setText(" Falha ao atualizar fonte: " + e.getMessage());
            }
        }

        private void removeSource() {
            NuGetSource selected = selectedSource();
            if (selected == null) {
                return;
            }
            if (selected.isNugetOrg()) {
                messageLabel.setText(" nuget.org e somente leitura.");
                return;
            }
            int answer = JOptionPane.showConfirmDialog(this,
                    "Remover a fonte '" + selected.name() + "'?",
                    "Remover fonte",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
            try {
                config.removeSource(selected.name());
                changed = true;
                reloadSources(null);
                messageLabel.setText(" Fonte removida.");
            } catch (Exception e) {
                messageLabel.setText(" Falha ao remover fonte: " + e.getMessage());
            }
        }

        private SourceFields readFields() {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            if (name.isBlank() || url.isBlank()) {
                messageLabel.setText(" Nome e origem sao obrigatorios.");
                return null;
            }
            if (NuGetSource.isNugetOrg(name, url)) {
                messageLabel.setText(" nuget.org e a fonte padrao e nao pode ser alterada.");
                return null;
            }
            return new SourceFields(name, url, enabledField.isSelected());
        }

        private NuGetSource selectedSource() {
            int row = table.getSelectedRow();
            if (row < 0) {
                return null;
            }
            return tableModel.sourceAt(table.convertRowIndexToModel(row));
        }

        private boolean changed() {
            return changed;
        }
    }

    private record SourceFields(String name, String url, boolean enabled) {
    }

    private static final class SourceTableModel extends AbstractTableModel {

        private final String[] columns = {"Ativa", "Nome", "Origem", "Tipo"};
        private final List<NuGetSource> sources = new ArrayList<>();

        private void setSources(List<NuGetSource> values) {
            sources.clear();
            sources.addAll(values);
            fireTableDataChanged();
        }

        private NuGetSource sourceAt(int row) {
            return sources.get(row);
        }

        private NuGetSource findByName(String name) {
            for (NuGetSource source : sources) {
                if (source.name().equalsIgnoreCase(name)) {
                    return source;
                }
            }
            return null;
        }

        @Override
        public int getRowCount() {
            return sources.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            NuGetSource source = sources.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> source.enabled();
                case 1 -> source.name();
                case 2 -> source.url();
                case 3 -> source.isNugetOrg() ? "Padrao" : "Customizada";
                default -> "";
            };
        }
    }
}
