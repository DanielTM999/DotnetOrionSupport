package dtm.ide;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.context.IdeProjectContext;
import dtm.ide.api.extension.IdeAdapter;
import dtm.ide.api.extension.NotificationContext;
import dtm.ide.api.extension.event.BreakpointChangedEvent;
import dtm.ide.api.extension.event.KeyboardEvent;
import dtm.ide.api.extension.menu.IdeMenuBarBuilder;
import dtm.ide.api.extension.menu.IdeMenuBuilder;
import dtm.ide.api.extension.output.OutputPanelHandle;
import dtm.ide.api.extension.screen.ToolIconType;
import dtm.ide.api.extension.settings.PluginSettingsPage;
import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.project.editor.*;
import dtm.ide.api.extension.runconfig.RunConfigurationContribution;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.api.project.tree.ProjectTreeIgnoreRule;
import dtm.ide.api.project.tree.ProjectTreeNode;
import dtm.ide.api.search.GlobalSearchMatch;
import dtm.ide.api.search.GlobalSearchQuery;
import dtm.ide.api.search.GlobalSearchResult;
import dtm.ide.api.theme.EditorTheme;
import dtm.ide.editor.theme.DotnetEditorTheme;
import dtm.ide.lsp.DotnetWorkspaceEdit;
import dtm.ide.lsp.LanguageServerSelector;
import dtm.ide.lsp.LspServerKind;
import dtm.ide.lsp.LspService;
import dtm.ide.lsp.OmniSharpLspService;
import dtm.ide.lsp.RoslynLspService;
import dtm.ide.lsp.UsagesPopup;
import dtm.ide.reference.ProjectReferenceService;
import dtm.ide.run.DebugCallStackPanel;
import dtm.ide.run.DebugCompletion;
import dtm.ide.run.DebugExceptionInfo;
import dtm.ide.run.DebugExceptionPopup;
import dtm.ide.run.DebugExpressionField;
import dtm.ide.run.DebugFrame;
import dtm.ide.run.DebugScope;
import dtm.ide.run.DebugToolbar;
import dtm.ide.run.DebugObjectTreePanel;
import dtm.ide.run.DebugValuePopup;
import dtm.ide.run.DebugVariablesPanel;
import dtm.ide.run.DebugWatchPanel;
import dtm.ide.run.DebugVar;
import dtm.ide.run.DotnetDebugView;
import dtm.ide.run.DotnetHotReloadResult;
import dtm.ide.run.DotnetRunConfigurationContribution;
import dtm.ide.run.DotnetRunSupport;
import dtm.ide.run.TargetFramework;
import dtm.ide.sdk.DotnetSdkService;
import dtm.ide.settings.DotnetPluginSettings;
import dtm.ide.settings.DotnetSettingsPage;
import dtm.ide.settings.TreeLayout;
import dtm.ide.ui.DotnetProjectConfigPanel;
import dtm.ide.ui.DotnetProcessPickerPanel;
import dtm.ide.ui.DotnetPublishPanel;
import dtm.ide.ui.DotnetTestExplorerPanel;
import dtm.ide.ui.NewCSharpItemPanel;
import dtm.ide.ui.NuGetManagerPanel;
import dtm.ide.ui.ProjectReferenceDialog;
import dtm.ide.ui.RunProjectChooserPanel;
import dtm.ide.ui.SolutionReferenceDialog;
import dtm.ide.wizard.NewSolutionProjectPanel;
import dtm.request_actions.http.download.core.DownloadObserver;
import dtm.stools.component.menu.bar.tree.MenuNode;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.Command;
import dtm.stools.component.panels.editor.code.api.CommandHandler;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.codelens.CodeLens;
import dtm.stools.component.panels.editor.code.codelens.CodeLensItem;
import dtm.stools.component.panels.editor.code.codelens.CodeLensPlacement;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;
import dtm.stools.component.panels.dock.DockRegion;
import dtm.stools.component.popup.ModernDialog;
import dtm.stools.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static dtm.ide.DotnetProjectConventions.isCSharpLike;
import static dtm.ide.DotnetProjectConventions.isHighlightable;
import static dtm.ide.DotnetProjectConventions.normalizePath;

@Slf4j
@Singleton
@PluginReference(id = "dotnet-ide-adapter")
public class DotnetIdeAdapter extends IdeAdapter {

    private final DotnetEditorRegistry editorRegistry = new DotnetEditorRegistry();
    private final EditorTheme editorTheme = new DotnetEditorTheme();
    private volatile Path projectPath;
    private volatile IdeProjectContext projectContext;
    private volatile DotnetSdkService sdkService;
    private volatile LspService lspService;
    private final DotnetRunSupport runSupport = new DotnetRunSupport();
    private volatile ExecutorService languageSetupExecutor;
    private volatile ExecutorService navigationExecutor;
    private volatile NuGetManagerPanel nugetPanel;
    private volatile DotnetTestExplorerPanel testPanel;
    private String testToolPanelId;
    private static final int CODE_LENS_LIMIT = 100;
    private final Set<Path> featureRefreshedFiles = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean analyzeProgressShown = new AtomicBoolean(false);
    private volatile DotnetProjectConfigPanel projectConfigPanel;
    private volatile DotnetPluginSettings pluginSettings;
    private final AtomicBoolean toolchainDeclined = new AtomicBoolean(false);
    private volatile Path activeFile;
    private volatile RunConfigurationData selectedRunConfig;
    private final AtomicLong projectLifecycleTicket = new AtomicLong();

    private final DebugVariablesPanel debugVariablesPanel = new DebugVariablesPanel();
    private final DebugCallStackPanel debugCallStackPanel = new DebugCallStackPanel();
    private final DebugWatchPanel debugWatchPanel = new DebugWatchPanel();
    private final DebugValuePopup debugValuePopup = new DebugValuePopup();
    private final DebugExceptionPopup debugExceptionPopup = new DebugExceptionPopup();
    private final DebugToolbar debugToolbar = new DebugToolbar();
    private final AtomicBoolean debugActive = new AtomicBoolean(false);
    private final AtomicBoolean runActive = new AtomicBoolean(false);
    private final AtomicLong runWatchTicket = new AtomicLong();
    private final AtomicBoolean hotReloadBusy = new AtomicBoolean(false);
    private final AtomicLong hotReloadUiTicket = new AtomicLong();
    private volatile JTabbedPane debugTabs;
    private volatile String debugToolPanelId;
    private static final Color DEBUG_LINE_COLOR = new Color(227, 100, 100, 80);
    private volatile IdeEditorContext debugLineContext;
    private volatile int debugLineNumber = -1;
    private volatile Path lastDebugStopFile;
    private volatile int lastDebugStopLine = -1;
    private final AtomicLong debugRefreshTicket = new AtomicLong();
    private volatile boolean debugPanelRegistered;
    private final ExecutorService debugQueryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dotnet-debug-query");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService debugRefreshDelayExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dotnet-debug-refresh-delay");
        t.setDaemon(true);
        return t;
    });

    private final Set<Path> breakPointsSteppedFiles = ConcurrentHashMap.newKeySet();
    private volatile JComponent codeActionLamp;
    private volatile JLayeredPane codeActionLampLayer;
    private final AtomicLong wordCaretTicket = new AtomicLong();
    private final ScheduledExecutorService codeActionDelayExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dotnet-code-action-delay");
        t.setDaemon(true);
        return t;
    });

    private static final String NUGET_TAB_ID = "dotnet.nuget";
    private static final String PROJECT_CONFIG_TAB_ID = "dotnet.projectConfig";
    private static final String LSP_PROGRESS_ID = "dotnetLspStartup";
    private static final long RESTORE_TIMEOUT_SECONDS = 180;
    private static final String LSP_ANALYZE_PROGRESS_ID = "dotnetLspAnalyze";
    private static final String NAV_PROGRESS_ID = "dotnetNavigate";
    private static final String HOT_RELOAD_PROGRESS_ID = "dotnetHotReload";

    @Override
    public boolean supports(Path path) {
        return DotnetProjectConventions.supports(path);
    }

    @Override
    public String getProjectType() {
        return DotnetProjectConventions.PROJECT_TYPE;
    }

    @Override
    public void onAdapterSelected(IdeProjectContext context) {
        bindProject(context);
        startLanguageServicesAsync();
    }

    @Override
    public void onProjectOpened(IdeProjectContext context) {
        bindProject(context);
        setupDebugPanel();
        runOnUiThread(this::ensureTestPanel);
        startLanguageServicesAsync();
    }

    @Override
    public void onProjectClosed(IdeProjectContext context) {
        projectLifecycleTicket.incrementAndGet();
        shutdownProjectProcesses();
        debugFinished();
        wordCaretTicket.incrementAndGet();
        SwingUtilities.invokeLater(this::hideCodeActionLamp);
        LspService service = lspService;
        if (service != null) {
            service.clearMetadataCache();
        }
        stopLanguageServices();
        editorRegistry.clearProjectEditors();
        this.projectContext = null;
        this.projectPath = null;
        this.activeFile = null;
        this.selectedRunConfig = null;
        runSupport.bindProject(null);
        runSupport.bindSdk(null);
        runSupport.bindDebugSessionStateListener(null);
        runSupport.bindHotReloadResultListener(null);
        runOnUiThread(() -> {
            finishHotReloadUi();
            requestSetHotReloadButtonEnabled(false);
            requestSetHotReloadButtonVisible(false);
        });
    }

    @Override
    public void clearCaches() {
        LspService service = lspService;
        if (service != null) {
            service.clearMetadataCache();
            service.stop();
            service.start();
        }
    }

    @Override
    public Collection<FileAssociated> getFileAssociations() {
        return List.of(
                new FileAssociated("csproj",  NativeEditorType.XML),
                new FileAssociated("slnx",  NativeEditorType.XML)
        );
    }

    private void bindProject(IdeProjectContext context) {
        this.projectContext = context;
        if (context != null) {
            this.projectPath = context.getProjectPath().map(DotnetProjectConventions::normalizePath).orElse(null);
        }
        projectLifecycleTicket.incrementAndGet();
        runSupport.bindProject(projectPath);
        runSupport.bindDownloadProgress(progressListener());
        runSupport.bindActiveFile(() -> activeFile);
        runSupport.bindActiveText(() -> currentTextOf(activeFile));
        runSupport.bindOutputPanels(this::requestOutputPanel);
        runSupport.bindRunOutputFocus(this::requestShowRunOutput);
        runSupport.bindBreakOnAllExceptions(() -> ensurePluginSettings().isBreakOnAllExceptions());
        runSupport.bindDebugSessionStateListener(active -> runOnUiThread(this::refreshHotReloadButton));
        runSupport.bindHotReloadResultListener(result -> runOnUiThread(() -> handleHotReloadResult(result)));
        runSupport.bindRunnableProjectChooser(this::chooseRunnableProject);
        if (projectPath != null) {
            boolean canRun = TargetFramework.canRunOnHost(projectPath);
            SwingUtilities.invokeLater(() -> {
                requestSetRunButtonEnabled(true);
                requestSetDebugButtonEnabled(canRun);
                refreshHotReloadButton();
            });
        }
    }

    @Override
    public IdeProjectContext getProjectContext() {
        return projectContext;
    }

    private void startLanguageServicesAsync() {
        Path project = projectPath;
        if (project == null) {
            return;
        }
        long ticket = projectLifecycleTicket.get();
        languageSetupExecutor().execute(() -> {
            try {
                if (!isProjectCurrent(ticket, project)) {
                    return;
                }
                DotnetSdkService sdk = ensureSdkService();
                if (sdk == null) {
                    return;
                }
                runSupport.bindSdk(sdk);
                DotnetSdkService.DownloadProgressListener progress = progressListener();
                String requiredSdkVersion = sdk.resolveSdkVersion(project);
                LspServerKind kind = LanguageServerSelector.select(project, ensurePluginSettings());
                boolean roslyn = kind == LspServerKind.ROSLYN;
                boolean needDotnet = sdk.getDotnetPath(requiredSdkVersion).isEmpty();
                boolean needServer = roslyn
                        ? sdk.getRoslynLanguageServerPath().isEmpty()
                        : sdk.getOmniSharpPath().isEmpty();
                boolean needRoslynRuntime = roslyn && sdk.getRoslynRuntimeRoot().isEmpty();

                if ((needDotnet || needServer || needRoslynRuntime)
                        && !confirmToolchainDownload(needDotnet ? requiredSdkVersion : null,
                                needServer || needRoslynRuntime)) {
                    return;
                }

                if (needDotnet) {
                    sdk.ensureDotnet(requiredSdkVersion, progress);
                }

                if (needRoslynRuntime) {
                    sdk.ensureRoslynRuntime(progress);
                }

                if (needServer) {
                    if (roslyn) {
                        sdk.ensureRoslyn(progress);
                    } else {
                        sdk.ensureOmniSharp(progress);
                    }
                }
                if (!isProjectCurrent(ticket, project)) {
                    return;
                }
                ensureProjectRestored(sdk, project, requiredSdkVersion, ticket);
                if (!isProjectCurrent(ticket, project)) {
                    return;
                }
                LspService service = ensureLspService(sdk, kind);
                service.bindProject(project);

                analyzeProgressShown.set(true);
                SwingUtilities.invokeLater(() -> {
                    showProgress(LSP_ANALYZE_PROGRESS_ID, "Carregando projeto C#");
                    updateProgress(LSP_ANALYZE_PROGRESS_ID, "Carregando projeto C#", 0);
                });
                service.start();
                if (!isProjectCurrent(ticket, project)) {
                    service.stop();
                    return;
                }
                if (service.isRunning()) {
                    SwingUtilities.invokeLater(() -> {
                        createNotification(new NotificationContext("C#", "IntelliSense ativo."));
                    });

                    refreshOpenEditors();
                    requestBackgroundTestDiscovery();
                } else {
                    if (analyzeProgressShown.compareAndSet(true, false)) {
                        SwingUtilities.invokeLater(() -> hideProgress(LSP_ANALYZE_PROGRESS_ID));
                    }
                    String error = service.getLastError();
                    SwingUtilities.invokeLater(() -> setStatusBarText("C#: falha ao iniciar Intellisense" + (error == null ? "." : " — " + error)));
                }
            } catch (Exception e) {
                log.warn("Falha ao iniciar serviços de linguagem .NET: {}", e.getMessage());
            }
        });
    }

    private void ensureProjectRestored(DotnetSdkService sdk, Path project, String sdkVersion, long ticket) {
        if (sdk == null || project == null) {
            return;
        }
        List<Path> projectFiles = TargetFramework.findProjectFiles(project);
        if (projectFiles.isEmpty() || !needsRestore(projectFiles)) {
            return;
        }
        Path dotnet = sdk.getDotnetPath(sdkVersion).orElse(null);
        if (dotnet == null || !isProjectCurrent(ticket, project)) {
            return;
        }
        SwingUtilities.invokeLater(() -> showProgress(LSP_PROGRESS_ID, "Restaurando pacotes (dotnet restore)..."));
        try {
            for (Path projectFile : projectFiles) {
                if (!isProjectCurrent(ticket, project) || !needsRestore(projectFile)) {
                    continue;
                }
                restoreProject(dotnet, projectFile);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Falha ao restaurar pacotes do projeto: {}", e.getMessage());
        } finally {
            SwingUtilities.invokeLater(() -> hideProgress(LSP_PROGRESS_ID));
        }
    }

    private static void restoreProject(Path dotnet, Path projectFile) throws Exception {
        Path directory = projectFile.getParent();
        Process process = new ProcessBuilder(
                dotnet.toAbsolutePath().toString(), "restore", projectFile.toAbsolutePath().toString())
                .directory(directory == null ? projectFile.toAbsolutePath().getParent().toFile() : directory.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            Thread drain = new Thread(() -> drainQuietly(process.getInputStream()), "dotnet-restore-drain");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(RESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("dotnet restore de {} excedeu {}s e foi abortado.",
                        projectFile.getFileName(), RESTORE_TIMEOUT_SECONDS);
                process.destroyForcibly();
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static boolean needsRestore(List<Path> projectFiles) {
        for (Path projectFile : projectFiles) {
            if (needsRestore(projectFile)) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsRestore(Path projectFile) {
        Path dir = projectFile == null ? null : projectFile.getParent();
        return dir != null && !Files.isRegularFile(dir.resolve("obj").resolve("project.assets.json"));
    }

    private static void drainQuietly(InputStream in) {
        try (in) {
            byte[] buffer = new byte[8192];
            while (in.read(buffer) != -1) {
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isProjectCurrent(long ticket, Path project) {
        return ticket == projectLifecycleTicket.get() && Objects.equals(projectPath, project);
    }

    private void stopLanguageServices() {
        LspService service = lspService;
        if (service != null) {
            service.stop();
        }
    }

    private void shutdownProjectProcesses() {
        try {
            runSupport.stop(null);
        } catch (Exception e) {
            log.debug("Falha ao parar processos .NET do projeto: {}", e.getMessage());
        }
    }

    private synchronized DotnetSdkService ensureSdkService() {
        if (sdkService != null) {
            return sdkService;
        }
        try {
            sdkService = new DotnetSdkService(getResource(), resolveDownloadObserver());
        } catch (Exception e) {
            log.warn("Não foi possível criar o serviço de SDK .NET: {}", e.getMessage());
        }
        return sdkService;
    }

    private synchronized LspService ensureLspService(DotnetSdkService sdk, LspServerKind kind) {
        if (lspService != null) {
            return lspService;
        }
        lspService = kind == LspServerKind.ROSLYN
                ? new RoslynLspService(getResource(), sdk)
                : new OmniSharpLspService(getResource(), sdk);
        lspService.setApplyEditSink(this::applyWorkspaceEditFromServer);
        lspService.addDiagnosticsPublishedListener(uri -> {
            Path file = DotnetProjectConventions.pathFromUri(uri);
            if (file == null) {
                return;
            }
            requestRefreshDiagnostics(file);
            Path normalized = normalizePath(file);
            if (featureRefreshedFiles.add(normalized)) {
                refreshEditorFeatures(normalized);
            }
        });
        lspService.addLoadProgressListener((percent, finished) -> SwingUtilities.invokeLater(() -> {
            if (finished) {
                if (analyzeProgressShown.compareAndSet(true, false)) {
                    hideProgress(LSP_ANALYZE_PROGRESS_ID);
                }
                return;
            }
            if (analyzeProgressShown.compareAndSet(false, true)) {
                showProgress(LSP_ANALYZE_PROGRESS_ID, "Analisando projeto");
            }
            updateProgress(LSP_ANALYZE_PROGRESS_ID, "Analisando projeto", percent);
            log.debug("Analisando projeto {}%", percent);
        }));
        return lspService;
    }

    private DownloadObserver resolveDownloadObserver() {
        try {
            return getService(DownloadObserver.class);
        } catch (Exception e) {
            log.debug("DownloadObserver indisponível: {}", e.getMessage());
            return null;
        }
    }

    private DotnetSdkService.DownloadProgressListener progressListener() {
        return new DotnetSdkService.DownloadProgressListener() {
            @Override
            public void onStart(String id, String label) {
                SwingUtilities.invokeLater(() -> showProgress(id, label + "..."));
            }

            @Override
            public void onProgress(String id, String label, int percent) {
                SwingUtilities.invokeLater(() -> updateProgress(id, label + "...", percent));
            }

            @Override
            public void onFinish(String id) {
                SwingUtilities.invokeLater(() -> hideProgress(id));
            }
        };
    }

    private boolean confirmToolchainDownload(String dotnetSdkVersion, boolean needServer) {
        if (toolchainDeclined.get()) {
            return false;
        }
        List<String> missing = new ArrayList<>();
        if (dotnetSdkVersion != null && !dotnetSdkVersion.isBlank()) {
            missing.add(".NET SDK " + dotnetSdkVersion);
        }
        if (needServer) {
            missing.add("IntelliSense (language server C#)");
        }
        String message = "O Orion precisa baixar " + String.join(" e ", missing)
                + " para habilitar o suporte a C# (IntelliSense e build) deste projeto."
                + " Todos os componentes têm licença livre para uso comercial.";
        final int[] result = {-1};
        Runnable show = () -> result[0] = createModernDialogBuilder()
                .title("Toolchain .NET não encontrada")
                .draggable(true)
                .message(message)
                .accentColor(new Color(59, 130, 246))
                .option("Baixar", 0, new Color(59, 130, 246), Color.WHITE)
                .option("Cancelar", 1, new Color(220, 53, 69), Color.WHITE)
                .type(ModernDialog.Type.QUESTION)
                .show();
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                show.run();
            } else {
                SwingUtilities.invokeAndWait(show);
            }
        } catch (Exception e) {
            log.debug("Falha ao exibir diálogo de download da toolchain: {}", e.getMessage());
            return false;
        }
        boolean accepted = result[0] == 0;
        if (!accepted) {
            toolchainDeclined.set(true);
        }
        return accepted;
    }

    private ExecutorService languageSetupExecutor() {
        ExecutorService executor = languageSetupExecutor;
        if (executor == null) {
            synchronized (this) {
                if (languageSetupExecutor == null) {
                    languageSetupExecutor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "dotnet-language-setup");
                        t.setDaemon(true);
                        return t;
                    });
                }
                executor = languageSetupExecutor;
            }
        }
        return executor;
    }

    @Override
    public EditorTheme getEditorTheme() {
        return editorTheme;
    }

    @Override
    public TokenizerCodeEditorProvider resolveSyntaxHighlightTokenizer(Path filePath) {
        if (filePath == null || !isHighlightable(filePath)) {
            return null;
        }
        return editorRegistry.tokenizerFor(filePath);
    }

    @Override
    public Collection<FoldRule> resolveFoldRules(Path filePath) {
        return DotnetProjectConventions.foldRules(filePath);
    }

    @Override
    public String getLineCommentPrefix(Path filePath) {
        return DotnetProjectConventions.lineCommentPrefix(filePath);
    }

    @Override
    public void configureEditor(IdeEditorContext context) {
        if (context == null || context.filePath() == null || !isHighlightable(context.filePath())) {
            return;
        }
        editorRegistry.trackEditor(normalizePath(context.filePath()), context);
        installCodeActionCommandHandler(context);
        triggerDiagnostics(normalizePath(context.filePath()), context.getText());
    }

    @Override
    public void onEditorOpen(IdeEditorContext editorContext) {
        if (editorContext == null || editorContext.filePath() == null) {
            return;
        }
        setActiveFile(editorContext.filePath());
        if (!isHighlightable(editorContext.filePath())) {
            return;
        }
        Path file = normalizePath(editorContext.filePath());
        editorRegistry.trackEditor(file, editorContext);
        installCodeActionCommandHandler(editorContext);
        triggerDiagnostics(file, editorContext.getText());
    }

    @Override
    public void onEditorSelected(IdeEditorContext editorContext) {
        if (editorContext == null || editorContext.filePath() == null) {
            return;
        }
        setActiveFile(editorContext.filePath());
    }

    @Override
    public void onCodeEditorTextChanged(IdeEditorContext editorContext) {
        if (editorContext == null || editorContext.filePath() == null) {
            return;
        }

        setActiveFile(editorContext.filePath());
    }

    @Override
    public void onCodeEditorInsertText(IdeEditorContext editorContext, int offset, String inserted) {
        if (editorContext == null || editorContext.filePath() == null
                || inserted == null || inserted.isEmpty()
                || !isCSharpLike(editorContext.filePath())) {
            return;
        }
        if (!ensurePluginSettings().isOnTypeFormatting()) {
            return;
        }
        LspService service = lspService;
        if (service == null || !service.isOnTypeFormattingSupported()) {
            return;
        }
        char trigger = inserted.charAt(inserted.length() - 1);
        if (trigger == '\r') {
            trigger = '\n';
        }
        if (!service.isOnTypeTrigger(trigger)) {
            return;
        }
        applyOnTypeFormatting(editorContext, trigger);
    }

    private void applyOnTypeFormatting(IdeEditorContext context, char trigger) {
        Path file = normalizePath(context.filePath());
        String text = context.getText();
        if (text == null) {
            return;
        }
        int line = context.getCaretLine();
        int col = context.getCaretCol();
        int caretOffset = context.getCaretOffset();
        navigationExecutor().execute(() -> {
            LspService service = lspService;
            if (service == null) {
                return;
            }
            EditorConfigSettings.FormatOptions options = EditorConfigSettings.resolve(file, 4, true);
            List<TextEdit> edits = service.onTypeFormatting(
                    file, text, line, col, String.valueOf(trigger), options.tabSize(), options.insertSpaces());
            if (edits == null || edits.isEmpty()) {
                return;
            }
            String updated = applyTextEdits(text, edits);
            if (updated.equals(text)) {
                return;
            }
            int newCaret = shiftCaretOffset(text, edits, caretOffset);
            SwingUtilities.invokeLater(() -> {
                if (!Objects.equals(context.getText(), text)) {
                    return;
                }
                context.setText(updated);
                int[] lineCol = lineColOf(updated, newCaret);
                context.setCaretPosition(lineCol[0], lineCol[1]);
            });
        });
    }

    private static int shiftCaretOffset(String text, List<TextEdit> edits, int caretOffset) {
        int[] lineStarts = lineStartOffsets(text);
        int delta = 0;
        for (TextEdit edit : edits) {
            if (edit == null || edit.range() == null) {
                continue;
            }
            int start = offsetOf(lineStarts, text, edit.range().start().line(), edit.range().start().col());
            int end = offsetOf(lineStarts, text, edit.range().end().line(), edit.range().end().col());
            int newLen = edit.newText() == null ? 0 : edit.newText().length();
            if (end <= caretOffset) {
                delta += newLen - (end - start);
            } else if (start < caretOffset) {
                return Math.max(0, start + newLen);
            }
        }
        return Math.max(0, caretOffset + delta);
    }

    private static int[] lineColOf(String text, int offset) {
        int safe = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < safe; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[]{line, safe - lineStart};
    }

    private void triggerDiagnostics(Path file, String text) {
        LspService service = lspService;
        if (service == null || file == null || !isCSharpLike(file)) {
            return;
        }
        navigationExecutor().execute(() -> {
            try {
                service.diagnose(file, text);
            } catch (Exception ignored) {
            }
            SwingUtilities.invokeLater(() -> requestRefreshDiagnostics(file));
        });
    }

    private void refreshOpenEditors() {
        featureRefreshedFiles.clear();
        for (Path file : editorRegistry.regularOpenCsPaths()) {
            Path normalized = normalizePath(file);
            IdeEditorContext context = editorRegistry.editorContext(normalized);
            if (context == null) {
                continue;
            }
            triggerDiagnostics(normalized, context.getText());
            refreshEditorFeatures(normalized);
        }
    }

    private void refreshEditorFeatures(Path file) {
        requestRefreshCodeLenses(file);
        requestRefreshInlayHints(file);
        IdeEditorContext context = editorRegistry.editorContext(file);
        if (context != null) {
            SwingUtilities.invokeLater(() -> {
                try {
                    context.applySyntaxHighlight();
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Override
    public void onEditorClose(Path filePath) {
        editorRegistry.close(filePath);
    }

    @Override
    public void onPathDeleted(Path path) {
        editorRegistry.delete(path);
    }

    @Override
    public void onPathRenamed(Path oldPath, Path newPath) {
        editorRegistry.rename(oldPath, newPath);
    }

    @Override
    public List<AutoCompleteItem> getCompletionSuggestions(IdeCompletionContext context) {
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.completeForEditor(
                context.filePath(), context.text(), context.caretLine(), context.caretCol(), context.prefix());
    }

    @Override
    public boolean shouldAutoTriggerCompletion(IdeCompletionContext context) {
        if (context == null || !isCSharpLike(context.filePath())) {
            return false;
        }
        String line = context.currentLine();
        int col = context.caretCol();
        if (line == null || col <= 0 || col > line.length()) {
            return false;
        }
        char typed = line.charAt(col - 1);
        return typed == '.' || Character.isLetter(typed) || typed == '_';
    }

    @Override
    public boolean isAutoCompletionOnTypingEnabled() {
        return true;
    }

    @Override
    public Set<Character> getCompletionTriggerCharacters() {
        return Set.of('.');
    }

    @Override
    public String getGhostText(IdeGhostTextContext context) {
        if (debugActive.get()) {
            return null;
        }
        LspService service = lspService;

        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return null;
        }
        DotnetPluginSettings settings = pluginSettings;
        if (settings != null && !settings.isGhostTextEnabled()) {
            return null;
        }
        boolean explicit = context.triggerKind() == IdeGhostTextTriggerKind.EXPLICIT;
        String prefix = ghostTextPrefix(context.currentLine(), context.caretCol());
        if (!explicit && prefix.isEmpty()) {
            return null;
        }
        List<AutoCompleteItem> items = service.complete(
                context.filePath(), context.text(), context.caretLine(), context.caretCol());
        return ghostTextSuffix(items, prefix);
    }

    private static String ghostTextPrefix(String currentLine, int caretCol) {
        if (currentLine == null || caretCol <= 0 || caretCol > currentLine.length()) {
            return "";
        }
        int start = caretCol;
        while (start > 0 && isIdentifierChar(currentLine.charAt(start - 1))) {
            start--;
        }
        return currentLine.substring(start, caretCol);
    }

    private static String ghostTextSuffix(List<AutoCompleteItem> items, String prefix) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (AutoCompleteItem item : items) {
            if (item == null || item.isSnippet()) {
                continue;
            }
            String insert = cleanCompletionInsertText(item.insertText(), item.label());
            if (insert == null || insert.isBlank() || insert.indexOf('\n') >= 0) {
                continue;
            }
            if (!insert.startsWith(prefix) || insert.length() <= prefix.length()) {
                continue;
            }
            return insert.substring(prefix.length());
        }
        return null;
    }

    @Override
    public HoverInfo getHover(IdeHoverContext context) {
        if (debugActive.get()) {
            return null;
        }
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return null;
        }
        HoverInfo diagnosticHover = service.diagnosticHover(context.filePath(), context.line(), context.col());
        if (diagnosticHover != null) {
            return diagnosticHover;
        }
        return service.hover(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public void onHover(IdeHoverContext context) {
        if (!debugActive.get() || context == null || context.text() == null || !isCSharpLike(context.filePath())) {
            debugValuePopup.hide();
            return;
        }
        String expression = identifierAt(context.text(), context.offset());
        if (expression == null) {
            debugValuePopup.hide();
            return;
        }
        Point mouse = mouseScreenLocation();
        debugQueryExecutor.execute(() -> {
            DebugVar var = runSupport.evaluateDebug(expression);
            if (var == null || !debugActive.get()) {
                debugValuePopup.hide();
            } else {
                debugValuePopup.show(var, mouse);
            }
        });
    }

    @Override
    public SignatureHelp provideSignatureHelp(IdeSignatureHelpContext context) {
        if (debugActive.get()) {
            return null;
        }
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return null;
        }
        return service.signatureHelp(context.filePath(), context.text(), context.caretLine(), context.caretCol());
    }

    @Override
    public Set<Character> getSignatureTriggerCharacters() {
        return Set.of('(', ',');
    }

    @Override
    public void contributeEditorMenu(IdeMenuBuilder menu, IdeEditorContext editorContext) {
        if (menu == null || editorContext == null || editorContext.filePath() == null
                || !isCSharpLike(editorContext.filePath())) {
            return;
        }
        boolean enabled = debugActive.get();
        String expression = expressionAtEditorContext(editorContext);
        menu.separator()
                .item("Go to Implementation", isImplementationAvailable(editorContext),
                        e -> onGoToImplementation(editorContext))
                .item("Go to Type Definition", isTypeDefinitionAvailable(editorContext),
                        e -> onGoToTypeDefinition(editorContext))
                .item("Rename Symbol...", isRenameAvailable(editorContext),
                        e -> showRenameSymbolDialog(editorContext))
                .item("Run to Cursor", enabled,
                        e -> runToCursor(editorContext))
                .item("Set Next Statement", enabled,
                        e -> setNextStatement(editorContext))
                .item("Evaluate Expression...", enabled, e -> showEvaluateDialog(editorContext, expression))
                .item("Add Watch", enabled && expression != null && !expression.isBlank(),
                        e -> addWatchExpression(expression));
    }

    private void runToCursor(IdeEditorContext context) {
        boolean sent = context != null && runSupport.runToCursor(
                context.filePath(), context.getCaretLine());
        if (!sent) {
            setStatusBarText("Run to Cursor exige uma sessão de debug pausada.");
        }
    }

    private void setNextStatement(IdeEditorContext context) {
        boolean sent = context != null && runSupport.setNextStatement(
                context.filePath(), context.getCaretLine());
        if (!sent) {
            setStatusBarText("Set Next Statement exige uma sessão de debug pausada.");
        }
    }

    @Override
    public List<Location> findDefinitions(IdeDefinitionContext context) {
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.definitions(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public List<Location> findReferences(IdeDefinitionContext context) {
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.references(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public List<DocumentSymbol> getDocumentSymbols(IdeDocumentSymbolContext context) {
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.documentSymbols(context.filePath(), context.text());
    }

    @Override
    public List<TextEdit> computeRenameEdits(IdeRenameContext context) {
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.rename(context.filePath(), context.text(), context.line(), context.col(), context.newName());
    }

    private boolean isRenameAvailable(IdeEditorContext context) {
        LspService service = lspService;
        return service != null && service.isRunning()
                && context != null
                && context.filePath() != null
                && isCSharpLike(context.filePath())
                && identifierAt(context.getText(), context.getCaretOffset()) != null;
    }

    private boolean isImplementationAvailable(IdeEditorContext context) {
        LspService service = lspService;
        return service != null && service.isRunning()
                && context != null
                && context.filePath() != null
                && isCSharpLike(context.filePath())
                && identifierAt(context.getText(), context.getCaretOffset()) != null;
    }

    private boolean isTypeDefinitionAvailable(IdeEditorContext context) {
        LspService service = lspService;
        return service != null && service.isTypeDefinitionReady()
                && context != null
                && context.filePath() != null
                && isCSharpLike(context.filePath())
                && identifierAt(context.getText(), context.getCaretOffset()) != null;
    }

    private void showRenameSymbolDialog(IdeEditorContext context) {
        if (context == null || context.filePath() == null) {
            return;
        }
        String current = identifierAt(context.getText(), context.getCaretOffset());
        if (current == null || current.isBlank()) {
            setStatusBarText("Nenhum simbolo C# no cursor para renomear.");
            return;
        }
        JTextField field = new JTextField(current, Math.max(18, current.length() + 4));
        field.selectAll();
        Component parent = resolveEditorComponent(context);
        String value = createModernInputDialogBuilder()
                .title("Rename Symbol")
                .message("Novo nome para '" + current + "'")
                .input(field)
                .confirmText("Rename")
                .cancelText("Cancel")
                .draggable(true)
                .show(parent);
        String newName = value == null ? "" : value.trim();
        if (newName.isEmpty() || Objects.equals(newName, current)) {
            return;
        }
        if (!isValidCSharpIdentifier(newName)) {
            setStatusBarText("Nome invalido para simbolo C#: " + newName);
            return;
        }
        renameSymbol(context, newName);
    }

    private void renameSymbol(IdeEditorContext context, String newName) {
        LspService service = lspService;
        if (service == null || context == null || context.filePath() == null) {
            return;
        }
        Path file = normalizePath(context.filePath());
        navigationExecutor().execute(() -> {
            SwingUtilities.invokeLater(() -> showProgress(NAV_PROGRESS_ID, "Renomeando simbolo..."));
            try {
                DotnetWorkspaceEdit workspaceEdit = service.renameWorkspace(
                        file, context.getText(), context.getCaretLine(), context.getCaretCol(), newName);
                if (workspaceEdit.isEmpty()) {
                    SwingUtilities.invokeLater(() -> setStatusBarText("Rename nao retornou alteracoes."));
                    return;
                }
                Map<Path, String> updated = computeWorkspaceEditTexts(workspaceEdit);
                if (!confirmRenamePreview(workspaceEdit, newName)) {
                    SwingUtilities.invokeLater(() -> setStatusBarText("Rename cancelado."));
                    return;
                }
                SwingUtilities.invokeAndWait(() -> applyWorkspaceEditTexts(updated));
                SwingUtilities.invokeLater(() -> {
                    updated.keySet().forEach(this::requestRefreshDiagnostics);
                });
            } catch (Exception e) {
                log.debug("Falha ao renomear simbolo: {}", e.getMessage());
                SwingUtilities.invokeLater(() -> setStatusBarText("Falha ao renomear: " + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> hideProgress(NAV_PROGRESS_ID));
            }
        });
    }

    private boolean confirmRenamePreview(DotnetWorkspaceEdit edit, String newName) {
        StringBuilder message = new StringBuilder("Renomear para '")
                .append(newName).append("' fará ")
                .append(edit.editCount()).append(" alteração(ões) em ")
                .append(edit.changes().size()).append(" arquivo(s):");
        int shown = 0;
        for (Map.Entry<Path, List<TextEdit>> entry : edit.changes().entrySet()) {
            if (shown++ == 8) {
                message.append("\n… e mais ").append(edit.changes().size() - 8).append(" arquivo(s)");
                break;
            }
            message.append("\n• ").append(entry.getKey().getFileName())
                    .append(" (").append(entry.getValue().size()).append(")");
        }
        final int[] result = {-1};
        Runnable show = () -> result[0] = createModernDialogBuilder()
                .title("Preview de Rename")
                .draggable(true)
                .message(message.toString())
                .accentColor(new Color(59, 130, 246))
                .option("Aplicar", 0, new Color(59, 130, 246), Color.WHITE)
                .option("Cancelar", 1, new Color(220, 53, 69), Color.WHITE)
                .type(ModernDialog.Type.QUESTION)
                .show();
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                show.run();
            } else {
                SwingUtilities.invokeAndWait(show);
            }
            return result[0] == 0;
        } catch (Exception e) {
            log.debug("Falha ao exibir preview de rename: {}", e.getMessage());
            return false;
        }
    }

    private Map<Path, String> computeWorkspaceEditTexts(DotnetWorkspaceEdit workspaceEdit) throws Exception {
        Map<Path, String> updated = new LinkedHashMap<>();
        for (Map.Entry<Path, List<TextEdit>> entry : workspaceEdit.changes().entrySet()) {
            Path file = normalizePath(entry.getKey());
            String original = currentTextOf(file);
            if (original == null) {
                throw new IllegalStateException("Nao foi possivel ler " + file);
            }
            updated.put(file, applyTextEdits(original, entry.getValue()));
        }
        return updated;
    }

    private void applyWorkspaceEditTexts(Map<Path, String> updated) {
        for (Map.Entry<Path, String> entry : updated.entrySet()) {
            Path file = normalizePath(entry.getKey());
            String text = entry.getValue();
            IdeEditorContext open = editorRegistry.editorContext(file);
            if (open != null) {
                open.setText(text);
            } else {
                try {
                    Files.writeString(file, text, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException("Falha ao gravar " + file.getFileName() + ": " + e.getMessage(), e);
                }
            }
        }
    }

    private static String applyTextEdits(String text, List<TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return text;
        }
        List<TextEdit> sorted = new ArrayList<>(edits);
        int[] lineStarts = lineStartOffsets(text);
        sorted.sort((a, b) -> Integer.compare(editEndOffset(lineStarts, text, b), editEndOffset(lineStarts, text, a)));
        StringBuilder sb = new StringBuilder(text);
        for (TextEdit edit : sorted) {
            int start = offsetOf(lineStarts, text, edit.range().start().line(), edit.range().start().col());
            int end = offsetOf(lineStarts, text, edit.range().end().line(), edit.range().end().col());
            if (start < 0 || end < start || end > sb.length()) {
                throw new IllegalArgumentException("Range de rename invalido.");
            }
            sb.replace(start, end, edit.newText() == null ? "" : edit.newText());
        }
        return sb.toString();
    }

    private static int editEndOffset(int[] lineStarts, String text, TextEdit edit) {
        return offsetOf(lineStarts, text, edit.range().end().line(), edit.range().end().col());
    }

    private static int[] lineStartOffsets(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int offsetOf(int[] lineStarts, String text, int line, int col) {
        int safeLine = Math.max(0, Math.min(line, lineStarts.length - 1));
        int lineStart = lineStarts[safeLine];
        int lineEnd = text.length();
        for (int i = lineStart; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                lineEnd = i;
                break;
            }
        }
        return Math.max(lineStart, Math.min(lineStart + Math.max(0, col), lineEnd));
    }

    private static boolean isValidCSharpIdentifier(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String value = name.startsWith("@") ? name.substring(1) : name;
        if (value.isBlank() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!isIdentifierChar(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean supportsIncrementalDiagnostics() {
        return false;
    }

    @Override
    public Collection<Diagnostic> getDiagnostics(IdeDiagnosticsContext context,
                                                 boolean incremental,
                                                 Collection<Diagnostic> previous) {
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.getFilePath())) {
            return Collections.emptyList();
        }
        return service.diagnose(context.getFilePath(), context.getText());
    }

    @Override
    public List<CodeAction> getCodeActions(IdeCodeActionContext context) {
        LspService service = lspService;
        if (service == null || context == null || context.filePath() == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.codeActions(context.filePath(), context.text(), context.range(), context.diagnostics());
    }

    private void installCodeActionCommandHandler(IdeEditorContext context) {
        Object codeEditor = resolveField(context, "codeEditor");
        if (codeEditor == null) {
            return;
        }
        try {
            Method method = codeEditor.getClass().getMethod("setCommandHandler", CommandHandler.class);
            CommandHandler handler = this::handleCodeActionCommand;
            method.invoke(codeEditor, handler);
        } catch (Exception e) {
            log.debug("Não foi possível registrar CommandHandler de code action: {}", e.getMessage());
        }
    }

    private void handleCodeActionCommand(Command command) {
        if (command == null || !LspService.APPLY_CODE_ACTION_COMMAND.equals(command.id())) {
            return;
        }
        List<Object> args = command.arguments();
        if (args == null || args.size() < 2 || !(args.get(1) instanceof JsonNode rawAction)) {
            return;
        }
        Object uriArg = args.get(0);
        Path file = uriArg == null ? null : DotnetProjectConventions.pathFromUri(uriArg.toString());
        LspService service = lspService;
        if (service == null) {
            return;
        }
        navigationExecutor().execute(() -> {
            SwingUtilities.invokeLater(() -> showProgress(NAV_PROGRESS_ID, "Aplicando ação de código..."));
            try {
                DotnetWorkspaceEdit edit = service.resolveCodeActionEdit(rawAction);
                if (!edit.isEmpty()) {
                    Map<Path, String> updated = computeWorkspaceEditTexts(edit);
                    SwingUtilities.invokeAndWait(() -> applyWorkspaceEditTexts(updated));
                    SwingUtilities.invokeLater(() -> updated.keySet().forEach(this::requestRefreshDiagnostics));
                } else if (file != null) {
                    SwingUtilities.invokeLater(() -> requestRefreshDiagnostics(file));
                }
            } catch (Exception e) {
                log.debug("Falha ao aplicar code action: {}", e.getMessage());
                SwingUtilities.invokeLater(() -> setStatusBarText("Falha ao aplicar ação: " + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> hideProgress(NAV_PROGRESS_ID));
            }
        });
    }

    private void applyWorkspaceEditFromServer(DotnetWorkspaceEdit edit) {
        if (edit == null || edit.isEmpty()) {
            return;
        }
        try {
            Map<Path, String> updated = computeWorkspaceEditTexts(edit);
            SwingUtilities.invokeAndWait(() -> applyWorkspaceEditTexts(updated));
            SwingUtilities.invokeLater(() -> updated.keySet().forEach(this::requestRefreshDiagnostics));
        } catch (Exception e) {
            log.debug("Falha ao aplicar edição vinda do servidor: {}", e.getMessage());
        }
    }

    @Override
    public String formatCode(FormatCodeContext context) {
        LspService service = lspService;
        if (service == null || context == null || context.file() == null || !isCSharpLike(context.file())) {
            return context == null ? null : context.text();
        }
        EditorConfigSettings.FormatOptions options = EditorConfigSettings.resolve(
                context.file(), context.tabSize(), context.useSpacesForTab());
        if (context.formatScope() == IdeFormatScope.SELECTION) {
            String selectionSource = context.fullText() == null ? context.text() : context.fullText();
            String formattedSelection = service.formatRange(context.file(), selectionSource,
                    context.startOffset(), context.endOffset(), options.tabSize(), options.insertSpaces());
            return formattedSelection == null ? context.text() : formattedSelection;
        }
        String fullText = context.fullText() == null ? context.text() : context.fullText();
        String formatted = service.format(context.file(), fullText, options.tabSize(), options.insertSpaces());
        return formatted == null ? fullText : formatted;
    }

    @Override
    public boolean isSemanticTokensEnabled() {
        LspService service = lspService;
        return service != null && service.isSemanticTokensReady();
    }

    @Override
    public List<SemanticToken> getSemanticTokens(IdeSemanticTokensContext context) {
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.semanticTokens(context.filePath(), context.text());
    }

    @Override
    public List<InlayHint> getInlayHints(IdeInlayHintContext context) {
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.inlayHints(context.filePath(), context.text(), context.firstLine(), context.lastLine());
    }

    @Override
    public List<DocumentHighlight> getDocumentHighlights(IdeDocumentHighlightContext context) {
        if (debugActive.get()) {
            return Collections.emptyList();
        }
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.documentHighlights(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public List<CodeLens> getCodeLenses(IdeCodeLensContext context) {
        LspService service = lspService;
        if (service == null || context == null || context.filePath() == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        List<DocumentSymbol> symbols = service.documentSymbols(context.filePath(), context.text());
        if (symbols.isEmpty()) {
            return Collections.emptyList();
        }
        List<CodeLens> lenses = new ArrayList<>();
        int[] budget = {CODE_LENS_LIMIT};
        String[] sourceLines = context.text() == null ? new String[0] : context.text().split("\n", -1);
        collectTestLenses(symbols, "", sourceLines, lenses);
        collectCodeLenses(service, symbols, context.filePath(), context.text(), lenses, budget);
        return mergeInlineLenses(lenses);
    }

    private static List<CodeLens> mergeInlineLenses(List<CodeLens> lenses) {
        Map<Integer, List<CodeLensItem>> inlineByLine = new LinkedHashMap<>();
        List<CodeLens> result = new ArrayList<>();
        for (CodeLens lens : lenses) {
            if (lens == null) {
                continue;
            }
            if (lens.placement() == CodeLensPlacement.INLINE) {
                inlineByLine.computeIfAbsent(lens.line(), key -> new ArrayList<>()).addAll(lens.items());
            } else {
                result.add(lens);
            }
        }
        for (Map.Entry<Integer, List<CodeLensItem>> entry : inlineByLine.entrySet()) {
            result.add(CodeLens.inline(entry.getKey(), entry.getValue().toArray(new CodeLensItem[0])));
        }
        return result;
    }

    private void collectTestLenses(List<DocumentSymbol> symbols, String container, String[] lines, List<CodeLens> out) {
        for (DocumentSymbol symbol : symbols) {
            if (symbol == null || symbol.name() == null) {
                continue;
            }
            String qualified = container.isEmpty() ? symbol.name() : container + "." + symbol.name();
            if (symbol.kind() == SymbolKind.METHOD && symbol.selectionRange() != null
                    && symbol.selectionRange().start() != null
                    && isTestMethod(lines, symbol.selectionRange().start().line())) {
                String testId = stripSignature(qualified);
                CodeLensItem run = CodeLensItem.builder()
                        .text("▶ Run")
                        .tooltip("Executar " + testId)
                        .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                        .onClick(event -> runTestFromLens(testId))
                        .build();
                out.add(CodeLens.inline(symbol.selectionRange().start().line(), run));
            }
            if (symbol.children() != null && !symbol.children().isEmpty()) {
                collectTestLenses(symbol.children(), qualified, lines, out);
            }
        }
    }

    private static boolean isTestMethod(String[] lines, int methodLine0) {
        if (lines == null || methodLine0 < 0 || methodLine0 >= lines.length) {
            return false;
        }
        if (hasTestMarker(lines[methodLine0])) {
            return true;
        }
        int scanned = 0;
        for (int i = methodLine0 - 1; i >= 0 && scanned < 8; i--, scanned++) {
            String stripped = lines[i].strip();
            if (stripped.isEmpty()) {
                continue;
            }
            if (stripped.startsWith("[")) {
                if (hasTestMarker(stripped)) {
                    return true;
                }
                continue;
            }
            break;
        }
        return false;
    }

    private static boolean hasTestMarker(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("[fact") || lower.contains("[theory")
                || lower.contains("[testmethod") || lower.contains("[testcase")
                || lower.contains("[test]") || lower.contains("[test(");
    }

    private static String stripSignature(String qualified) {
        int paren = qualified.indexOf('(');
        String base = paren >= 0 ? qualified.substring(0, paren) : qualified;
        int angle = base.indexOf('<');
        return angle >= 0 ? base.substring(0, angle) : base;
    }

    private void collectCodeLenses(LspService service, List<DocumentSymbol> symbols, Path filePath,
                                   String text, List<CodeLens> out, int[] budget) {
        for (DocumentSymbol symbol : symbols) {
            if (symbol == null || symbol.selectionRange() == null || symbol.selectionRange().start() == null) {
                continue;
            }
            if (isLensableSymbol(symbol.kind()) && budget[0] > 0) {
                budget[0]--;
                Position pos = symbol.selectionRange().start();
                List<Location> usages = normalizedReferences(filePath,
                        service.references(filePath, text, pos.line(), pos.col()));
                usages.removeIf(usage -> isDeclarationAt(usage, filePath, pos));
                if (!usages.isEmpty()) {
                    int count = usages.size();
                    CodeLensItem item = CodeLensItem.builder()
                            .text(count == 1 ? "1 usage" : count + " usages")
                            .tooltip("Mostrar usos de " + symbol.name())
                            .cursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                            .onClick(event -> {
                                MouseEvent me = event.mouseEvent();
                                Component comp = me != null ? me.getComponent() : null;
                                Point screen = me != null ? me.getLocationOnScreen() : null;
                                showUsagesPopup(usages, filePath, text, comp, screen);
                            })
                            .build();
                    out.add(CodeLens.inline(pos.line(), item));
                }
            }
            if (symbol.children() != null && !symbol.children().isEmpty()) {
                collectCodeLenses(service, symbol.children(), filePath, text, out, budget);
            }
        }
    }

    private static boolean isLensableSymbol(SymbolKind kind) {
        return kind == SymbolKind.CLASS
                || kind == SymbolKind.INTERFACE
                || kind == SymbolKind.STRUCT
                || kind == SymbolKind.ENUM
                || kind == SymbolKind.METHOD
                || kind == SymbolKind.CONSTRUCTOR
                || kind == SymbolKind.PROPERTY;
    }

    private boolean isDeclarationAt(Location usage, Path declFile, Position declPos) {
        if (usage == null || usage.range() == null || usage.range().start() == null) {
            return false;
        }
        Path usagePath = DotnetProjectConventions.pathFromUri(usage.uri());
        return usagePath != null
                && normalizePath(usagePath).equals(normalizePath(declFile))
                && usage.range().start().line() == declPos.line()
                && usage.range().start().col() == declPos.col();
    }

    private List<Location> normalizedReferences(Path searched, List<Location> refs) {
        List<Location> usages = new ArrayList<>();
        if (refs == null) {
            return usages;
        }
        for (Location ref : refs) {
            if (ref == null || ref.range() == null || ref.range().start() == null) {
                continue;
            }
            Path path = ref.isLocal() ? searched : DotnetProjectConventions.pathFromUri(ref.uri());
            if (path == null) {
                continue;
            }
            usages.add(Location.of(path.toUri().toString(), ref.range()));
        }
        return usages;
    }

    private void showUsagesPopup(List<Location> usages, Path currentFile, String currentText,
                                 Component invoker, Point screen) {
        if (usages == null || usages.isEmpty()) {
            return;
        }
        Window owner = invoker == null ? null : SwingUtilities.getWindowAncestor(invoker);
        List<UsagesPopup.Item> items = buildUsageItems(usages, currentFile, currentText);
        String header = items.size() == 1 ? "1 usage" : items.size() + " usages";
        UsagesPopup.show(owner, screen, header, items);
    }

    private List<UsagesPopup.Item> buildUsageItems(List<Location> usages, Path currentFile, String currentText) {
        List<UsagesPopup.Item> items = new ArrayList<>();
        Map<Path, List<String>> cache = new java.util.HashMap<>();
        Path root = projectPath;
        for (Location usage : usages) {
            Path path = DotnetProjectConventions.pathFromUri(usage.uri());
            int line0 = usage.range().start().line();
            String snippet = sourceLine(path, currentFile, currentText, line0, cache);
            Path shown = (path != null && root != null && path.startsWith(root))
                    ? root.relativize(path)
                    : (path != null ? path.getFileName() : null);
            String location = (shown == null ? usage.uri() : shown.toString()) + ":" + (line0 + 1);
            items.add(new UsagesPopup.Item(snippet, location, () -> navigateToDefinition(usage)));
        }
        return items;
    }

    private String sourceLine(Path file, Path currentFile, String currentText, int line0,
                              Map<Path, List<String>> cache) {
        if (file == null || line0 < 0) {
            return "";
        }
        List<String> lines;
        if (currentText != null && currentFile != null && normalizePath(file).equals(normalizePath(currentFile))) {
            lines = currentText.lines().toList();
        } else {
            lines = cache.computeIfAbsent(file, f -> {
                try {
                    return Files.readAllLines(f);
                } catch (IOException ex) {
                    return List.of();
                }
            });
        }
        return line0 < lines.size() ? lines.get(line0).strip() : "";
    }

    @Override
    public GlobalSearchResult search(GlobalSearchQuery query, GlobalSearchResult current) {
        LspService service = lspService;
        if (service == null || query == null || !query.hasTerm()) {
            return current;
        }
        String term = query.term() == null ? "" : query.term().trim();
        if (term.length() < 2) {
            return current;
        }
        List<LspService.WorkspaceSymbol> symbols = service.workspaceSymbols(term);
        if (symbols.isEmpty()) {
            return current;
        }
        int limit = query.maxResults() > 0 ? query.maxResults() : symbols.size();
        List<GlobalSearchMatch> matches = new ArrayList<>();
        for (LspService.WorkspaceSymbol symbol : symbols) {
            if (matches.size() >= limit) {
                break;
            }
            Location location = symbol.location();
            if (location == null || location.range() == null || location.range().start() == null) {
                continue;
            }
            Path file = DotnetProjectConventions.pathFromUri(location.uri());
            if (file == null || !isCSharpLike(file) || !Files.isRegularFile(file)) {
                continue;
            }
            int line = location.range().start().line();
            int startCol = location.range().start().col();
            int endCol = location.range().end() != null && location.range().end().line() == line
                    ? location.range().end().col() : startCol;
            matches.add(GlobalSearchMatch.content(file, line, startCol, endCol, symbolPreview(symbol)));
        }
        if (matches.isEmpty()) {
            return current;
        }
        GlobalSearchResult symbolResult = GlobalSearchResult.of(matches);
        return current == null ? symbolResult : current.merge(symbolResult);
    }

    private static String symbolPreview(LspService.WorkspaceSymbol symbol) {
        String container = symbol.container();
        return container == null || container.isBlank()
                ? symbol.name()
                : symbol.name() + "  —  " + container;
    }

    @Override
    public boolean isFindUsagesEnabled() {
        return true;
    }

    @Override
    public boolean isGoToImplementationEnabled() {
        return true;
    }

    @Override
    public boolean isGoToDeclarationEnabled() {
        return true;
    }

    @Override
    public boolean isCallHierarchyEnabled() {

        return true;
    }

    @Override
    public List<CallHierarchyItem> prepareCallHierarchy(IdeCallHierarchyContext context) {
        LspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.prepareCallHierarchy(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public List<CallHierarchyCall> getIncomingCalls(CallHierarchyItem item) {
        LspService service = lspService;
        return service == null ? Collections.emptyList() : service.incomingCalls(item);
    }

    @Override
    public List<CallHierarchyCall> getOutgoingCalls(CallHierarchyItem item) {
        LspService service = lspService;
        return service == null ? Collections.emptyList() : service.outgoingCalls(item);
    }

    @Override
    public void onWordCaretChange(IdeWordCaretContext context) {
        long ticket = wordCaretTicket.incrementAndGet();
        SwingUtilities.invokeLater(this::hideCodeActionLamp);
        if (context == null || context.filePath() == null || context.editorContext() == null
                || !isCSharpLike(context.filePath())) {
            return;
        }
        LspService service = lspService;
        if (service == null || !service.isRunning()) {
            return;
        }
        codeActionDelayExecutor.schedule(
                () -> showCodeActionLampIfCaretStayed(context, ticket), 600, TimeUnit.MILLISECONDS);
    }

    private void showCodeActionLampIfCaretStayed(IdeWordCaretContext context, long ticket) {
        if (ticket != wordCaretTicket.get()) {
            return;
        }
        IdeEditorContext editorContext = context.editorContext();
        if (!isSameCaretContext(context, editorContext)) {
            return;
        }
        LspService service = lspService;
        if (service == null) {
            return;
        }
        Diagnostic diagnostic = service.diagnosticAt(context.filePath(), context.line(), context.col());
        if (diagnostic == null) {
            return;
        }
        boolean error = diagnostic.severity() == DiagnosticSeverity.ERROR;
        SwingUtilities.invokeLater(() -> {
            if (ticket == wordCaretTicket.get() && isSameCaretContext(context, editorContext)) {
                showCodeActionLamp(context, error);
            }
        });
    }

    private boolean isSameCaretContext(IdeWordCaretContext context, IdeEditorContext editorContext) {
        if (context == null || editorContext == null || editorContext.filePath() == null) {
            return false;
        }
        return Objects.equals(normalizePath(editorContext.filePath()), normalizePath(context.filePath()))
                && editorContext.getCaretLine() == context.line()
                && editorContext.getCaretCol() == context.col()
                && Objects.equals(editorContext.getText(), context.text());
    }

    private void showCodeActionLamp(IdeWordCaretContext context, boolean error) {
        Component editorComponent = resolveEditorComponent(context.editorContext());
        if (editorComponent == null || !editorComponent.isShowing()) {
            return;
        }
        JRootPane rootPane = SwingUtilities.getRootPane(editorComponent);
        if (rootPane == null) {
            return;
        }
        hideCodeActionLamp();

        CodeActionLamp lamp = new CodeActionLamp(loadCodeActionLampIcon(error));
        lamp.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                hideCodeActionLamp();
                requestShowCodeActions(context.filePath());
            }
        });

        Dimension size = lamp.getPreferredSize();
        Point location = codeActionLampLocation(editorComponent, context, size);
        JLayeredPane layeredPane = rootPane.getLayeredPane();
        SwingUtilities.convertPointFromScreen(location, layeredPane);
        lamp.setBounds(location.x, location.y, size.width, size.height);

        layeredPane.add(lamp, JLayeredPane.POPUP_LAYER);
        layeredPane.repaint(lamp.getBounds());
        codeActionLamp = lamp;
        codeActionLampLayer = layeredPane;
    }

    private Icon loadCodeActionLampIcon(boolean error) {
        String path = error ? "imgs/codeActionLampRed.svg" : "imgs/codeActionLampYellow.svg";
        return ImageUtils.getIconByResource(DotnetIdeAdapter.class, path)
                .map(icon -> ImageUtils.resizeIcon(icon, 18, 18))
                .orElseGet(() -> UIManager.getIcon(error ? "OptionPane.errorIcon" : "OptionPane.warningIcon"));
    }

    private Point codeActionLampLocation(Component editorComponent, IdeWordCaretContext context, Dimension lampSize) {
        Point screenLocation = editorComponent.getLocationOnScreen();
        int editorHeight = editorComponent.getHeight();
        int x = -lampSize.width + 2;
        int anchorY = caretLineCenterY(editorComponent, context);
        int y = Math.max(0, Math.min(anchorY - lampSize.height / 2,
                Math.max(0, editorHeight - lampSize.height)));
        return new Point(screenLocation.x + x, screenLocation.y + y);
    }

    private int caretLineCenterY(Component editorComponent, IdeWordCaretContext context) {
        if (editorComponent instanceof JTextComponent textComponent) {
            try {
                int offset = Math.max(0, Math.min(context.startOffset(), textComponent.getDocument().getLength()));
                Rectangle2D rect = textComponent.modelToView2D(offset);
                if (rect != null) {
                    return (int) Math.round(rect.getY() + rect.getHeight() / 2.0);
                }
            } catch (Exception e) {
                log.debug("Falha ao posicionar lâmpada na linha do caret: {}", e.getMessage());
            }
        }
        return context.mouseY();
    }

    private void hideCodeActionLamp() {
        JComponent lamp = codeActionLamp;
        JLayeredPane layer = codeActionLampLayer;
        codeActionLamp = null;
        codeActionLampLayer = null;
        if (lamp != null && layer != null) {
            Rectangle bounds = lamp.getBounds();
            layer.remove(lamp);
            layer.repaint(bounds);
        }
    }

    private Component resolveEditorComponent(IdeEditorContext editorContext) {
        Object codeEditor = resolveField(editorContext, "codeEditor");
        Component textArea = invokeGetTextArea(codeEditor);
        if (textArea != null) {
            return textArea;
        }
        return codeEditor instanceof Component component ? component : null;
    }

    private void showEvaluateDialog(IdeEditorContext context, String initialExpression) {
        Component editor = resolveEditorComponent(context);
        java.awt.Window owner = editor == null ? null : SwingUtilities.getWindowAncestor(editor);
        JDialog dialog = new JDialog(owner, "Evaluate Expression");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setModal(false);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        Color panelBg = UIManager.getColor("Panel.background");
        Color textColor = UIManager.getColor("Label.foreground");
        Color contentBg = UIManager.getColor("TextArea.background");
        Color border = UIManager.getColor("Component.borderColor");
        if (panelBg == null) panelBg = new Color(0x1E2127);
        if (textColor == null) textColor = new Color(0xD7DEE8);
        if (contentBg == null) contentBg = panelBg;
        if (border == null) border = new Color(0x4B5563);
        root.setBackground(panelBg);

        DebugExpressionField expression = new DebugExpressionField();
        expression.setColors(contentBg, textColor, border);
        expression.bindCompletionProvider(text -> debugExpressionCompletions(context, text));
        expression.setText(initialExpression == null ? "" : initialExpression);

        DebugObjectTreePanel result = new DebugObjectTreePanel();
        result.bindChildrenProvider(runSupport::debugVariables);
        result.setPreferredSize(new Dimension(560, 300));

        JLabel status = new JLabel(" ");
        status.setForeground(textColor.darker());
        Font labelFont = UIManager.getFont("Label.font");
        status.setFont((labelFont == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12) : labelFont).deriveFont(12f));
        JProgressBar evaluationProgress = new JProgressBar();
        evaluationProgress.setIndeterminate(true);
        evaluationProgress.setVisible(false);
        evaluationProgress.setPreferredSize(new Dimension(120, 14));

        JButton evaluate = new JButton("Evaluate");
        JButton addWatch = new JButton("Add Watch");
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(addWatch);
        actions.add(evaluate);

        JPanel top = new JPanel(new BorderLayout(8, 6));
        top.setOpaque(false);
        JLabel label = new JLabel("Expression");
        label.setForeground(textColor);
        top.add(label, BorderLayout.NORTH);
        top.add(expression, BorderLayout.CENTER);
        top.add(actions, BorderLayout.EAST);

        root.add(top, BorderLayout.NORTH);
        root.add(result, BorderLayout.CENTER);
        JPanel statusLine = new JPanel(new BorderLayout(8, 0));
        statusLine.setOpaque(false);
        statusLine.add(status, BorderLayout.CENTER);
        statusLine.add(evaluationProgress, BorderLayout.EAST);
        root.add(statusLine, BorderLayout.SOUTH);
        dialog.setContentPane(root);

        Runnable runEval = () -> {
            String expr = expression.text();
            if (expr.isEmpty()) {
                return;
            }
            status.setText("Evaluating...");
            expression.setError(false);
            evaluationProgress.setVisible(true);
            evaluate.setEnabled(false);
            addWatch.setEnabled(false);
            dialog.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            debugQueryExecutor.execute(() -> {
                DebugVar value = runSupport.evaluateDebug(expr);
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (value == null) {
                            status.setText("Expression returned no value in the current frame.");
                            expression.setError(true);
                            result.setValue(null);
                        } else {
                            status.setText(value.type() == null || value.type().isBlank() ? " " : value.type());
                            expression.setError(false);
                            result.setValue(value);
                        }
                    } finally {
                        evaluationProgress.setVisible(false);
                        evaluate.setEnabled(true);
                        addWatch.setEnabled(true);
                        dialog.setCursor(Cursor.getDefaultCursor());
                    }
                });
            });
        };
        evaluate.addActionListener(e -> runEval.run());
        expression.addActionListener(e -> runEval.run());
        addWatch.addActionListener(e -> {
            String expr = expression.text();
            if (!expr.isEmpty()) {
                boolean added = addWatchExpression(expr);
                status.setText(added ? "Added to Watch." : "Already in Watch.");
            }
        });

        dialog.setSize(680, 440);
        dialog.setLocationRelativeTo(editor);
        dialog.setVisible(true);
        expression.requestFocusAndSelectAll();
        if (initialExpression != null && !initialExpression.isBlank()) {
            runEval.run();
        }
    }

    private boolean addWatchExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        boolean added = debugWatchPanel.addExpression(expression.trim());
        if (!added) {
            debugWatchPanel.refresh();
        }
        selectDebugWatchTab();
        return added;
    }

    private void selectDebugWatchTab() {
        JTabbedPane tabs = debugTabs;
        if (tabs == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            int index = tabs.indexOfComponent(debugWatchPanel);
            if (index >= 0) {
                tabs.setSelectedIndex(index);
            }
        });
    }

    private List<DebugCompletion> debugExpressionCompletions(IdeEditorContext context, String expression) {
        String text = expression == null ? "" : expression;
        List<DebugCompletion> dapCompletions = runSupport.debugCompletions(text, text.length() + 1);
        if (!dapCompletions.isEmpty()) {
            return dapCompletions;
        }
        List<DebugCompletion> lspCompletions = lspExpressionCompletions(context, text);
        if (!lspCompletions.isEmpty()) {
            return lspCompletions;
        }
        if (isMemberCompletion(text)) {
            return debugMemberCompletions(text);
        }

        String token = expressionCompletionToken(text).toLowerCase(Locale.ROOT);
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<DebugCompletion> result = new ArrayList<>();
        addLocalCompletion(result, names, "this", "current instance");
        addLocalCompletion(result, names, "base", "base instance");
        addLocalCompletion(result, names, "args", "argument array");
        for (String watch : debugWatchPanel.expressions()) {
            addLocalCompletion(result, names, watch, "Watch");
        }
        try {
            for (DebugScope scope : runSupport.debugScopes()) {
                if (scope == null || scope.variablesReference() <= 0) {
                    continue;
                }
                for (DebugVar variable : runSupport.debugVariables(scope.variablesReference())) {
                    if (variable != null && variable.name() != null && !variable.name().isBlank()
                            && isSimpleExpressionName(variable.name())) {
                        addLocalCompletion(result, names, variable.name(), variable.type());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Falha ao carregar sugestoes de evaluate: {}", e.getMessage());
        }
        if (token.isEmpty()) {
            return result;
        }
        return result.stream()
                .filter(item -> item.label().toLowerCase(Locale.ROOT).startsWith(token))
                .toList();
    }

    private List<DebugCompletion> debugMemberCompletions(String expression) {
        String targetExpression = memberCompletionTarget(expression);
        if (targetExpression == null || targetExpression.isBlank()) {
            return List.of();
        }
        String token = expressionCompletionToken(expression).toLowerCase(Locale.ROOT);
        try {
            DebugVar target = runSupport.evaluateDebug(targetExpression);
            if (target == null || target.variablesReference() <= 0) {
                return List.of();
            }
            TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            List<DebugCompletion> result = new ArrayList<>();
            for (DebugVar child : runSupport.debugVariables(target.variablesReference())) {
                if (child == null || child.name() == null || child.name().isBlank()
                        || !isSimpleExpressionName(child.name())) {
                    continue;
                }
                String name = child.name();
                if (!token.isEmpty() && !name.toLowerCase(Locale.ROOT).startsWith(token)) {
                    continue;
                }
                String detail = child.type() == null || child.type().isBlank() ? child.value() : child.type();
                addLocalCompletion(result, names, name, detail);
            }
            return result;
        } catch (Exception e) {
            log.debug("Falha ao completar membros de evaluate: {}", e.getMessage());
            return List.of();
        }
    }

    private List<DebugCompletion> lspExpressionCompletions(IdeEditorContext context, String expression) {
        LspService service = lspService;
        if (service == null || context == null || context.filePath() == null || !isCSharpLike(context.filePath())) {
            return List.of();
        }
        String expressionText = expression == null ? "" : expression;
        String source = expressionContextSource(context, expressionText);
        if (source == null) {
            return List.of();
        }
        String[] lines = source.split("\\R", -1);
        int line = Math.max(0, Math.min(context.getCaretLine(), lines.length - 1));
        int column = lines[line].length();
        try {
            return service.complete(context.filePath(), source, line, column).stream()
                    .map(DotnetIdeAdapter::toDebugCompletion)
                    .filter(Objects::nonNull)
                    .limit(120)
                    .toList();
        } catch (Exception e) {
            log.debug("Falha ao completar expressao via LSP: {}", e.getMessage());
            return List.of();
        }
    }

    private static DebugCompletion toDebugCompletion(AutoCompleteItem item) {
        if (item == null || item.label() == null || item.label().isBlank()) {
            return null;
        }
        String insert = cleanCompletionInsertText(item.insertText(), item.label());
        String detail = item.detail() == null ? "" : item.detail();
        String kind = item.kind() == null ? "" : item.kind().name();
        return new DebugCompletion(item.label(), insert, detail, kind, 0, 0);
    }

    private static String cleanCompletionInsertText(String insertText, String fallback) {
        String text = insertText == null || insertText.isBlank() ? fallback : insertText;
        if (text == null) {
            return "";
        }
        if (text.contains("$")) {
            text = text.replace("$0", "");
            text = text.replaceAll("\\$\\{\\d+:([^}]*)}", "$1");
            text = text.replaceAll("\\$\\d+", "");
        }
        return text;
    }

    private static String expressionContextSource(IdeEditorContext context, String expression) {
        String text = context.getText();
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\\R", -1);
        int line = Math.max(0, Math.min(context.getCaretLine(), lines.length - 1));
        String current = lines.length == 0 ? "" : lines[line];
        String indent = current.replaceFirst("\\S.*$", "");
        lines[line] = indent + (expression == null ? "" : expression);
        return String.join("\n", lines);
    }

    private static void addLocalCompletion(List<DebugCompletion> result, Set<String> names,
                                           String name, String detail) {
        if (name == null || name.isBlank() || !names.add(name)) {
            return;
        }
        result.add(new DebugCompletion(name, name, detail, "Variable", 0, 0));
    }

    private static String expressionCompletionToken(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        int end = expression.length();
        int start = end;
        while (start > 0 && isIdentifierChar(expression.charAt(start - 1))) {
            start--;
        }
        return expression.substring(start, end);
    }

    private static String memberCompletionTarget(String expression) {
        if (!isMemberCompletion(expression)) {
            return null;
        }
        int dot = expression.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        return expression.substring(0, dot).trim();
    }

    private static boolean isMemberCompletion(String expression) {
        if (expression == null) {
            return false;
        }
        int dot = expression.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        for (int i = dot + 1; i < expression.length(); i++) {
            if (!isIdentifierChar(expression.charAt(i))) {
                return false;
            }
        }
        for (int i = dot - 1; i >= 0; i--) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c) || "()+-*/%<>=!&|?:,;[]{}".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSimpleExpressionName(String name) {
        if (name == null || name.isBlank() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!isIdentifierChar(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String expressionAtEditorContext(IdeEditorContext context) {
        if (context == null) {
            return null;
        }
        try {
            if (context.isSelectionActive()) {
                String selected = context.getSelectedTextOrEmpty();
                if (selected != null && !selected.isBlank()) {
                    return selected.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return identifierAt(context.getText(), context.getCaretOffset());
    }

    private static String identifierAt(String text, int offset) {
        if (text == null || offset < 0 || offset > text.length()) {
            return null;
        }
        int start = offset;
        int end = offset;
        while (start > 0 && isIdentifierChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isIdentifierChar(text.charAt(end))) {
            end++;
        }
        if (start >= end) {
            return null;
        }
        String word = text.substring(start, end);
        return Character.isJavaIdentifierStart(word.charAt(0)) ? word : null;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private Point mouseScreenLocation() {
        try {
            return java.awt.MouseInfo.getPointerInfo().getLocation();
        } catch (Exception e) {
            return null;
        }
    }

    private Object resolveField(IdeEditorContext editorContext, String field) {
        if (editorContext == null) {
            return null;
        }
        try {
            Field f = editorContext.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(editorContext);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Component invokeGetTextArea(Object codeEditor) {
        if (codeEditor == null) {
            return null;
        }
        try {
            Method method = codeEditor.getClass().getMethod("getTextArea");
            Object textArea = method.invoke(codeEditor);
            return textArea instanceof Component component ? component : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class CodeActionLamp extends JComponent {
        private static final Color HOVER_BG = new Color(128, 128, 128, 60);
        private static final Color HOVER_BORDER = new Color(128, 128, 128, 120);

        private final Icon icon;
        private boolean hovered;

        CodeActionLamp(Icon icon) {
            this.icon = icon;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Mostrar ações de código (Alt+Enter)");
            int w = (icon == null ? 16 : icon.getIconWidth()) + 8;
            int h = (icon == null ? 16 : icon.getIconHeight()) + 4;
            Dimension size = new Dimension(w, h);
            setPreferredSize(size);
            setSize(size);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hovered = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (hovered) {
                    g2.setColor(HOVER_BG);
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                    g2.setColor(HOVER_BORDER);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                }
                if (icon != null) {
                    int x = (getWidth() - icon.getIconWidth()) / 2;
                    int y = (getHeight() - icon.getIconHeight()) / 2;
                    icon.paintIcon(this, g2, x, y);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    @Override
    public void onWordClick(IdeWordClickContext context) {
        if (context == null || context.filePath() == null || !isCSharpLike(context.filePath())) {
            return;
        }
        navigateAsync(context.text(), context.filePath(), context.line(), context.col(), context.startOffset());
    }

    @Override
    public void onGoToDeclaration(IdeEditorContext context) {
        navigateFromEditor(context);
    }

    @Override
    public void onGoToImplementation(IdeEditorContext context) {
        if (context == null || context.filePath() == null || !isCSharpLike(context.filePath())) {
            return;
        }
        navigateImplementationsAsync(context.getText(), context.filePath(),
                context.getCaretLine(), context.getCaretCol());
    }

    private void onGoToTypeDefinition(IdeEditorContext context) {
        if (context == null || context.filePath() == null || !isCSharpLike(context.filePath())) {
            return;
        }
        navigateTypeDefinitionsAsync(context.getText(), context.filePath(),
                context.getCaretLine(), context.getCaretCol());
    }

    @Override
    public void onBreakpointChanged(BreakpointChangedEvent event) {
        super.onBreakpointChanged(event);
        if (event == null || event.getBreakpointIde() == null) {
            return;
        }
        runSupport.applyBreakpointChange(event.getFile(), event.getBreakpointIde().line(), event.isBreakpointAdded());
    }

    private void navigateFromEditor(IdeEditorContext context) {
        if (context == null || context.filePath() == null || !isCSharpLike(context.filePath())) {
            return;
        }
        navigateAsync(context.getText(), context.filePath(),
                context.getCaretLine(), context.getCaretCol(), context.getCaretOffset());
    }

    private void navigateAsync(String text, Path file, int line, int col, int offset) {
        LspService service = lspService;
        if (service == null) return;
        navigationExecutor().execute(() -> {
            SwingUtilities.invokeLater(() -> showProgress(NAV_PROGRESS_ID, "Abrindo definição (descompilando se necessário)..."));
            try {
                List<Location> targets = service.definitions(file, text, line, col);
                if (!targets.isEmpty()) {
                    navigateToDefinition(targets.getFirst());
                }
            } catch (Exception e) {
                log.debug("Falha ao navegar para definição: {}", e.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> hideProgress(NAV_PROGRESS_ID));
            }
        });
    }

    private void navigateImplementationsAsync(String text, Path file, int line, int col) {
        LspService service = lspService;
        if (service == null) {
            return;
        }
        navigationExecutor().execute(() -> {
            SwingUtilities.invokeLater(() ->
                    showProgress(NAV_PROGRESS_ID, "Procurando implementações..."));
            try {
                List<Location> targets = service.implementations(file, text, line, col);
                if (targets.isEmpty()) {
                    targets = service.definitions(file, text, line, col);
                }
                if (!targets.isEmpty()) {
                    navigateToDefinition(targets.get(0));
                }
            } catch (Exception e) {
                log.debug("Falha ao navegar para implementação: {}", e.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> hideProgress(NAV_PROGRESS_ID));
            }
        });
    }

    private void navigateTypeDefinitionsAsync(String text, Path file, int line, int col) {
        LspService service = lspService;
        if (service == null) {
            return;
        }
        navigationExecutor().execute(() -> {
            SwingUtilities.invokeLater(() ->
                    showProgress(NAV_PROGRESS_ID, "Procurando definição de tipo..."));
            try {
                List<Location> targets = service.typeDefinitions(file, text, line, col);
                if (!targets.isEmpty()) {
                    navigateToDefinition(targets.getFirst());
                }
            } catch (Exception e) {
                log.debug("Falha ao navegar para definição de tipo: {}", e.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> hideProgress(NAV_PROGRESS_ID));
            }
        });
    }

    private void navigateToDefinition(Location location) {
        if (location == null || location.range() == null || location.range().start() == null) {
            return;
        }
        int targetLine = location.range().start().line();
        int targetCol = location.range().start().col();
        if (location.isLocal()) {
            runOnUiThread(() -> setCaretPosition(targetLine, targetCol));
            return;
        }
        Path targetPath = DotnetProjectConventions.pathFromUri(location.uri());

        if (targetPath == null || !Files.isRegularFile(targetPath)) {
            if (targetPath != null) {
                log.debug("Definição aponta para arquivo inexistente, ignorando: {}", targetPath);
            }
            return;
        }
        openOrReuseEditor(targetPath, targetLine, targetCol);
    }

    private void openOrReuseEditor(Path targetPath, int targetLine, int targetCol) {
        Path normalized = normalizePath(targetPath);
        IdeEditorContext open = editorRegistry.editorContext(normalized);
        if (open != null) {
            runOnUiThread(() -> {
                switchToCenterTab(normalized.toString());
                open.setCaretPosition(targetLine, targetCol);
            });
            return;
        }
        runOnUiThread(() -> requestOpenFile(targetPath));
        SwingUtilities.invokeLater(() -> setCaretPosition(targetLine, targetCol));
    }

    private ExecutorService navigationExecutor() {
        ExecutorService executor = navigationExecutor;
        if (executor == null) {
            synchronized (this) {
                if (navigationExecutor == null) {
                    navigationExecutor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "dotnet-navigation");
                        t.setDaemon(true);
                        return t;
                    });
                }
                executor = navigationExecutor;
            }
        }
        return executor;
    }

    private void runOnUiThread(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    @Override
    public String onBeforeFileSave(Path path, String content) {
        LspService service = lspService;
        DotnetPluginSettings settings = pluginSettings;
        if (service == null || settings == null || !settings.isFormatOnSave()
                || path == null || !isCSharpLike(path)) {
            return content;
        }
        EditorConfigSettings.FormatOptions options = EditorConfigSettings.resolve(path, 4, true);
        String formatted = service.format(path, content, options.tabSize(), options.insertSpaces());
        return formatted == null ? content : formatted;
    }

    @Override
    public List<PluginSettingsPage> getSettingsPages() {
        return List.of(new DotnetSettingsPage(ensurePluginSettings(), this::requestProjectTreeViewRefresh));
    }

    private synchronized DotnetPluginSettings ensurePluginSettings() {
        if (pluginSettings != null) {
            return pluginSettings;
        }
        Path settingsDir = null;
        try {
            settingsDir = getResource() == null ? null : getResource().getResourcePath();
        } catch (Exception e) {
            log.debug("Resource indisponível para settings do plugin: {}", e.getMessage());
        }
        pluginSettings = new DotnetPluginSettings(settingsDir);
        return pluginSettings;
    }

    @Override
    public void contributeMenuBar(IdeMenuBarBuilder menu) {
        menu.into("file")
                .separator()
                .add(
                        MenuNode.item("dotnetProjectConfig", "Configuração do Projeto (.NET)")
                                .tooltip("Mudar TargetFramework/SDK, OutputType, LangVersion...")
                                .onClick(e -> openProjectConfig())
                );

        menu.submenu("dotnetBuildMenu", "Build", build -> build
                .item("dotnetBuildDebug", "Compilar (Debug)",
                        e -> buildSolution("Compilar (Debug)", List.of("build", "-c", "Debug")))
                .item("dotnetBuildRelease", "Compilar (Release)",
                        e -> buildSolution("Compilar (Release)", List.of("build", "-c", "Release")))
                .item("dotnetPublishRelease", "Publicar...",
                        e -> openPublishDialog())
                .item("dotnetPackRelease", "Empacotar NuGet (Release)",
                        e -> buildSolution("Empacotar NuGet (Release)", List.of("pack", "-c", "Release")))
                .separator()
                .item("dotnetRebuildMenu", "Recompilar",
                        e -> buildSolution("Recompilar", List.of("build", "--no-incremental")))
                .item("dotnetCleanMenu", "Limpar",
                        e -> buildSolution("Limpar", List.of("clean"))));

        menu.into("window")
                .add(
                        MenuNode.item("dotnetNuget", "Gerenciar NuGet")
                                .tooltip("Gerenciar pacotes NuGet do projeto")
                                .onClick(e -> openNuGetManager())
                )
                .add(
                        MenuNode.item("dotnetTests", "Testes .NET")
                                .tooltip("Descobrir e executar testes do projeto")
                                .onClick(e -> openTestExplorer())
                )
                .add(
                        MenuNode.item("dotnetAttachProcess", "Attach ao processo .NET...")
                                .tooltip("Selecionar um processo em execução e anexar o netcoredbg")
                                .onClick(e -> openAttachProcessPicker())
                )
                .add(
                        MenuNode.item("dotnetRestore", "Restaurar pacotes (dotnet restore)")
                                .tooltip("Executa dotnet restore no projeto")
                                .onClick(e -> restorePackages())
                )
                .add(
                        MenuNode.item("dotnetRestartLsp", "Reiniciar OmniSharp")
                                .tooltip("Reinicia o language server C#")
                                .onClick(e -> restartLanguageServer())
                );
    }

    private void openNuGetManager() {
        openNuGetManager(null);
    }

    private void openNuGetManager(Path target) {
        if (nugetPanel == null) {
            nugetPanel = new NuGetManagerPanel(() -> projectPath, this::ensureSdkService);
        }
        if (target != null) {
            nugetPanel.focusProject(target);
        } else {
            nugetPanel.refreshCurrentTab();
        }
        openCenterTab(NUGET_TAB_ID, "NuGet", nugetPanel, true);
        switchToCenterTab(NUGET_TAB_ID);
    }

    private void openTestExplorer() {
        runOnUiThread(() -> {
            ensureTestPanel();
            if (testToolPanelId != null && !testToolPanelId.isBlank()) {
                requestOpenToolPanel(testToolPanelId);
            }
            testPanel.refresh();
        });
    }

    private void openAttachProcessPicker() {
        runOnUiThread(() -> {
            DotnetProcessPickerPanel picker = new DotnetProcessPickerPanel();
            Long pid = createModernComponentDialogBuilder(Long.class)
                    .title("Attach ao processo .NET")
                    .draggable(true)
                    .showIcon(false)
                    .accentColor(new Color(59, 130, 246))
                    .confirmText("Attach")
                    .cancelText("Cancelar")
                    .component(picker)
                    .result(ctx -> picker.selectedPid())
                    .show();
            if (pid == null) {
                return;
            }
            attachTestProcess(pid);
        });
    }

    private void attachTestProcess(long pid) {
        runOnUiThread(() -> {
            debugActive.set(true);
            refreshHotReloadButton();
            RunProcessHandle handle = runSupport.attachToProcess(pid);
            if (!handle.isAlive()) {
                debugActive.set(false);
                refreshHotReloadButton();
            }
            OutputPanelHandle panel = requestOutputPanel("dotnet");
            panel.clear();
            panel.show();
            Thread output = new Thread(() -> {
                try (InputStream in = handle.getOutput()) {
                    in.transferTo(panel.getOutputStream());
                } catch (Exception e) {
                    log.debug("Falha ao encaminhar saída do attach: {}", e.getMessage());
                }
            }, "dotnet-attach-output");
            output.setDaemon(true);
            output.start();
        });
    }

    private void ensureTestPanel() {
        if (testPanel == null) {
            testPanel = new DotnetTestExplorerPanel(() -> projectPath, this::ensureSdkService,
                    () -> ensurePluginSettings().getDefaultConfiguration(), this::attachTestProcess);
            testToolPanelId = registerToolPanel(DockRegion.BOTTOM, "Testes", ToolIconType.INFO, testPanel);
        }
    }

    private void requestBackgroundTestDiscovery() {
        runOnUiThread(() -> {
            ensureTestPanel();
            testPanel.discoverIfNeeded();
        });
    }

    private void runTestFromLens(String fullyQualifiedName) {
        runOnUiThread(() -> {
            ensureTestPanel();
            if (testToolPanelId != null && !testToolPanelId.isBlank()) {
                requestOpenToolPanel(testToolPanelId);
            }
            testPanel.runTest(fullyQualifiedName);
        });
    }

    private void openProjectConfig() {
        if (projectConfigPanel == null) {
            projectConfigPanel = new DotnetProjectConfigPanel(() -> projectPath);
        } else {
            projectConfigPanel.reload();
        }
        openCenterTab(PROJECT_CONFIG_TAB_ID, "Projeto .NET", projectConfigPanel, true);
        switchToCenterTab(PROJECT_CONFIG_TAB_ID);
    }

    private void restartLanguageServer() {
        languageSetupExecutor().execute(() -> {
            LspService service = lspService;
            if (service != null) {
                service.stop();
            }
            startLanguageServicesAsync();
        });
    }

    private void restorePackages() {
        Path project = projectPath;
        if (project == null) {
            return;
        }
        languageSetupExecutor().execute(() -> {
            DotnetSdkService sdk = ensureSdkService();
            if (sdk == null) {
                SwingUtilities.invokeLater(() -> setStatusBarText("dotnet não encontrado para restaurar pacotes."));
                return;
            }
            SwingUtilities.invokeLater(() -> showProgress("dotnetRestore", "dotnet restore..."));
            Process process = null;
            try {
                process = new ProcessBuilder(
                        sdk.ensureDotnet(project, progressListener()).toString(), "restore")
                        .directory(project.toFile())
                        .redirectErrorStream(true)
                        .start();
                Process started = process;
                Thread drain = new Thread(() -> drainQuietly(started.getInputStream()), "dotnet-restore-drain");
                drain.setDaemon(true);
                drain.start();
                if (process.waitFor(RESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    int code = process.exitValue();
                    SwingUtilities.invokeLater(() -> setStatusBarText(
                            code == 0 ? "Pacotes restaurados." : "dotnet restore falhou (código " + code + ")."));
                } else {
                    process.destroyForcibly();
                    SwingUtilities.invokeLater(() -> setStatusBarText(
                            "dotnet restore excedeu o tempo limite e foi abortado."));
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> setStatusBarText("Falha no restore: " + e.getMessage()));
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                SwingUtilities.invokeLater(() -> hideProgress("dotnetRestore"));
            }
        });
    }

    @Override
    public Collection<RunConfigurationData> getStaticRunConfigurations() {
        return runSupport.staticRunConfigurations();
    }

    @Override
    public List<RunConfigurationContribution> getRunConfigurationContributions() {
        return List.of(
                new DotnetRunConfigurationContribution(() -> projectPath, DotnetRunSupport.TYPE_RUN),
                new DotnetRunConfigurationContribution(() -> projectPath, DotnetRunSupport.TYPE_BUILD),
                new DotnetRunConfigurationContribution(() -> projectPath, DotnetRunSupport.TYPE_TEST));
    }

    @Override
    public RunProcessHandle launch(RunConfigurationData data, RunExecutionContext context) throws Exception {
        RunProcessHandle handle = runSupport.launch(data, context);
        trackRunProcess(data, handle);
        return handle;
    }

    private Path chooseRunnableProject(List<Path> projects) {
        if (projects == null || projects.isEmpty()) {
            return null;
        }
        if (projects.size() == 1) {
            return projects.get(0);
        }
        Path[] result = new Path[1];
        Runnable prompt = () -> {
            RunProjectChooserPanel panel = new RunProjectChooserPanel(projects);
            result[0] = createModernComponentDialogBuilder(Path.class)
                    .title("Selecionar projeto para executar")
                    .draggable(true)
                    .showIcon(false)
                    .accentColor(new Color(59, 130, 246))
                    .confirmText("Executar")
                    .cancelText("Cancelar")
                    .enterConfirms(true)
                    .component(panel)
                    .result(ctx -> panel.getSelected())
                    .show();
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                prompt.run();
            } else {
                SwingUtilities.invokeAndWait(prompt);
            }
        } catch (Exception e) {
            log.debug("Falha ao escolher projeto de execução: {}", e.getMessage());
            return null;
        }
        return result[0];
    }

    private void trackRunProcess(RunConfigurationData data, RunProcessHandle handle) {
        boolean runnable = DotnetRunSupport.isRunType(data) || DotnetRunSupport.isCurrentFileType(data);
        if (!runnable || handle == null || !handle.isAlive()) {
            return;
        }
        runActive.set(true);
        long ticket = runWatchTicket.incrementAndGet();
        runOnUiThread(this::refreshHotReloadButton);
        Thread watcher = new Thread(() -> {
            try {
                while (handle.isAlive()) {
                    Thread.sleep(400);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (runWatchTicket.get() == ticket) {
                runActive.set(false);
                runOnUiThread(this::refreshHotReloadButton);
            }
        }, "dotnet-run-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    @Override
    public RunProcessHandle launchDebug(RunConfigurationData data, RunExecutionContext context) throws Exception {
        debugActive.set(true);
        runOnUiThread(this::refreshHotReloadButton);
        RunProcessHandle handle = runSupport.launchDebug(data, context);
        runOnUiThread(this::refreshHotReloadButton);
        return handle;
    }

    @Override
    public void onHotReload(RunConfigurationData runConfiguration) throws Exception {
        if (runConfiguration != null) {
            selectedRunConfig = runConfiguration;
        }
        if (!hotReloadBusy.compareAndSet(false, true)) {
            runOnUiThread(() -> setStatusBarText("Hot Reload ja esta em execucao."));
            return;
        }
        if (!runSupport.isDebugging()) {
            hotReloadBusy.set(false);
            runOnUiThread(() -> {
                requestSetHotReloadButtonEnabled(false);
                debugToolbar.finishHotReloadBusy(false);
                setStatusBarText("Hot Reload disponivel apenas durante debug .NET.");
            });
            return;
        }
        beginHotReloadUi();
        boolean sent = runSupport.applyHotReload(activeFile, currentTextOf(activeFile));
        if (!sent) {
            finishHotReloadUi();
            runOnUiThread(() ->
                setStatusBarText("Hot Reload nao foi enviado: sessao de debug indisponivel."));
        }
    }

    @Override
    public void onKeyboardEvent(KeyboardEvent event) {
        if (event == null) {
            return;
        }
        if (event.keyCode() == KeyEvent.VK_F2 && event.modifiersEx() == 0) {
            showRenameForActiveEditor();
            return;
        }
        if (!debugActive.get()) {
            return;
        }
        int mods = event.modifiersEx();
        int shift = KeyEvent.SHIFT_DOWN_MASK;
        int ctrlShift = KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK;
        switch (event.keyCode()) {
            case KeyEvent.VK_F5 -> {
                if (mods == shift) {
                    onDebugCommand("stop");
                } else if (mods == ctrlShift) {
                    onDebugCommand("restart");
                } else if (mods == 0) {
                    onDebugCommand("continue");
                }
            }
            case KeyEvent.VK_F6 -> {
                if (mods == 0) {
                    onDebugCommand("pause");
                }
            }
            case KeyEvent.VK_F10 -> {
                if (mods == 0) {
                    onDebugCommand("next");
                }
            }
            case KeyEvent.VK_F11 -> {
                if (mods == shift) {
                    onDebugCommand("stepOut");
                } else if (mods == 0) {
                    onDebugCommand("stepIn");
                }
            }
            default -> {
            }
        }
    }

    private void showRenameForActiveEditor() {
        Path file = activeFile;
        if (file == null || !isCSharpLike(file)) {
            return;
        }
        IdeEditorContext context = editorRegistry.editorContext(normalizePath(file));
        if (context != null) {
            showRenameSymbolDialog(context);
        }
    }

    private void setupDebugPanel() {
        runSupport.bindDebugView(new DotnetDebugView() {
            @Override
            public void onDebugStopped(Path file, int line, DebugExceptionInfo exceptionInfo) {
                boolean newStopLocation = markDebugStopped(file, line);
                requestShowDebugVariablesPanel();
                if (!newStopLocation) {
                    showDebugExceptionPopup(exceptionInfo);
                    refreshDebugPanelsAfterStop();
                    return;
                }
                debugHighlightLine(file, line, exceptionInfo);
                breakPointsSteppedFiles.add(file);
            }

            @Override
            public void onDebugCleared() {
                runOnUiThread(DotnetIdeAdapter.this::debugContinued);
            }

            @Override
            public void onDebugFinished() {
                debugFinished();
                Set<Path> breakPointsSteppedFilesSnapshot = new HashSet<>(breakPointsSteppedFiles);
                breakPointsSteppedFilesSnapshot.forEach(e -> {
                   requestRepaintCodeEditorBreakpointLine(e);
                });
            }
        });
        if (debugPanelRegistered) {
            return;
        }
        debugPanelRegistered = true;
        debugVariablesPanel.bindChildrenProvider(runSupport::debugVariables);
        debugValuePopup.bindChildrenProvider(runSupport::debugVariables);
        debugCallStackPanel.bindSelectFrame(this::onDebugFrameSelected);
        debugWatchPanel.bindEvaluator(runSupport::evaluateDebug);
        debugToolbar.bindCommandSink(this::onDebugCommand);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Variáveis", debugVariablesPanel);
        tabs.addTab("Watch", debugWatchPanel);
        debugTabs = tabs;
        tabs.addTab("Pilha de chamadas", debugCallStackPanel);

        JPanel debugRoot = new JPanel(new BorderLayout());
        debugRoot.add(debugToolbar, BorderLayout.NORTH);
        debugRoot.add(tabs, BorderLayout.CENTER);
        debugToolPanelId = registerToolPanel(DockRegion.BOTTOM, "Debug", ToolIconType.DEBUG, debugRoot);
    }

    private void requestShowDebugVariablesPanel() {
        runOnUiThread(() -> {
            JTabbedPane tabs = debugTabs;
            if (tabs != null) {
                int index = tabs.indexOfComponent(debugVariablesPanel);
                if (index >= 0) {
                    tabs.setSelectedIndex(index);
                }
            }
            String id = debugToolPanelId;
            if (id != null && !id.isBlank()) {
                requestOpenToolPanel(id);
            }
        });
    }

    private boolean markDebugStopped(Path file, int line) {
        Path normalized = file == null ? null : normalizePath(file);
        if (Objects.equals(lastDebugStopFile, normalized) && lastDebugStopLine == line) {
            return false;
        }
        lastDebugStopFile = normalized;
        lastDebugStopLine = line;
        return true;
    }

    private void onDebugCommand(String command) {
        if ("stop".equals(command)) {
            try {
                runSupport.stop(selectedRunConfig);
            } catch (Exception e) {
                log.debug("Falha ao parar debug: {}", e.getMessage());
            }
            return;
        }
        if ("hotReload".equals(command)) {
            try {
                onHotReload(selectedRunConfig);
            } catch (Exception e) {
                finishHotReloadUi();
                setStatusBarText("Falha ao iniciar Hot Reload: " + e.getMessage());
            }
            return;
        }
        if (isResumeLikeDebugCommand(command)) {
            debugContinued();
        }
        runSupport.sendDebugCommand(command);
    }

    private static boolean isResumeLikeDebugCommand(String command) {
        return "continue".equals(command)
                || "next".equals(command)
                || "stepIn".equals(command)
                || "stepOut".equals(command)
                || "restart".equals(command);
    }

    private void debugHighlightLine(Path file, int line, DebugExceptionInfo exceptionInfo) {
        int editorLine = Math.max(0, line - 1);
        runOnUiThread(() -> {
            requestOpenFile(file);
            IdeEditorContext context = getEditor(file, true);
            if (context != null) {
                clearDebugLine();
                context.setLineColor(editorLine, DEBUG_LINE_COLOR);
                context.setCaretPosition(editorLine, 0);
                debugLineContext = context;
                debugLineNumber = editorLine;
            }
            showDebugExceptionPopup(exceptionInfo);
        });
        refreshDebugPanelsAfterStop();
    }

    private void showDebugExceptionPopup(DebugExceptionInfo exceptionInfo) {
        if (exceptionInfo != null && exceptionInfo.hasContent()) {
            debugExceptionPopup.show(exceptionInfo);
        }
    }

    private void refreshDebugPanelsAfterStop() {
        long ticket = debugRefreshTicket.incrementAndGet();
        debugVariablesPanel.setLoading();
        refreshDebugPanels(ticket, 0, true);
    }

    private void refreshDebugPanels() {
        refreshDebugPanels(debugRefreshTicket.get(), 0, false);
    }

    private void refreshDebugPanels(long ticket, int attempt, boolean retryEmptyScopes) {
        debugQueryExecutor.execute(() -> {
            List<DebugScope> scopes = runSupport.debugScopes();
            List<DebugFrame> frames = runSupport.debugCallStack();
            if (retryEmptyScopes && scopes.isEmpty() && debugActive.get()
                    && ticket == debugRefreshTicket.get()) {
                debugVariablesPanel.setLoading();
                debugCallStackPanel.setFrames(frames);
                debugRefreshDelayExecutor.schedule(
                        () -> refreshDebugPanels(ticket, attempt + 1, true),
                        Math.min(1000, 120L + (attempt * 120L)),
                        TimeUnit.MILLISECONDS);
                return;
            }
            if (retryEmptyScopes && ticket != debugRefreshTicket.get()) {
                return;
            }
            debugVariablesPanel.setScopes(scopes);
            debugCallStackPanel.setFrames(frames);
            debugWatchPanel.refresh();
        });
    }

    private void onDebugFrameSelected(int frameId) {
        if (!debugActive.get()) {
            return;
        }
        debugQueryExecutor.execute(() -> {
            if (runSupport.selectDebugFrame(frameId)) {
                debugVariablesPanel.setScopes(runSupport.debugScopes());
                debugWatchPanel.refresh();
            }
        });
    }

    private void debugFinished() {
        debugActive.set(false);
        debugRefreshTicket.incrementAndGet();
        lastDebugStopFile = null;
        lastDebugStopLine = -1;
        finishHotReloadUi();
        runOnUiThread(this::clearDebugLine);
        debugValuePopup.hide();
        debugExceptionPopup.hide();
        debugVariablesPanel.clearVariables();
        debugCallStackPanel.clear();
        debugWatchPanel.clearValues();
        refreshHotReloadButton();
    }

    private void debugContinued() {
        debugRefreshTicket.incrementAndGet();
        lastDebugStopFile = null;
        lastDebugStopLine = -1;
        clearDebugLine();
        debugValuePopup.hide();
        debugExceptionPopup.hide();
        debugVariablesPanel.clearVariables();
        debugCallStackPanel.clear();
        debugWatchPanel.clearValues();
        refreshHotReloadButton();
    }

    private void handleHotReloadResult(DotnetHotReloadResult result) {
        finishHotReloadUi();
        if (result == null) {
            return;
        }
        switch (result.status()) {
            case APPLIED -> setStatusBarText("Hot Reload aplicado.");
            case NO_CHANGES -> setStatusBarText("Hot Reload: sem alteracoes.");
            case BLOCKED -> showHotReloadDialog("Hot Reload nao aplicado", result.message());
            case ERROR -> showHotReloadDialog("Falha no Hot Reload", result.message());
        }
    }

    private void showHotReloadDialog(String title, String message) {
        createModernDialogBuilder()
                .title(title)
                .draggable(true)
                .message(message == null || message.isBlank() ? "Nao foi possivel aplicar Hot Reload." : message)
                .accentColor(new Color(220, 53, 69))
                .option("OK", 0, new Color(59, 130, 246), Color.WHITE)
                .type(ModernDialog.Type.ERROR)
                .show();
    }

    private void beginHotReloadUi() {
        long ticket = hotReloadUiTicket.incrementAndGet();
        runOnUiThread(() -> {
            debugToolbar.startHotReloadBusy();
            requestSetHotReloadButtonEnabled(false);
            showProgress(HOT_RELOAD_PROGRESS_ID, "Aplicando Hot Reload...");
        });
        debugRefreshDelayExecutor.schedule(() -> {
            if (!hotReloadBusy.get() || hotReloadUiTicket.get() != ticket) {
                return;
            }
            runOnUiThread(() -> {
                if (!hotReloadBusy.compareAndSet(true, false)) {
                    return;
                }
                hotReloadUiTicket.incrementAndGet();
                debugToolbar.finishHotReloadBusy(isHotReloadButtonEnabledNow());
                refreshHotReloadButton();
                hideProgress(HOT_RELOAD_PROGRESS_ID);
                setStatusBarText("Hot Reload: tempo esgotado aguardando resposta.");
            });
        }, 90, TimeUnit.SECONDS);
    }

    private void finishHotReloadUi() {
        hotReloadUiTicket.incrementAndGet();
        hotReloadBusy.set(false);
        runOnUiThread(() -> {
            hideProgress(HOT_RELOAD_PROGRESS_ID);
            debugToolbar.finishHotReloadBusy(isHotReloadButtonEnabledNow());
            refreshHotReloadButton();
        });
    }

    private void refreshHotReloadButton() {
        boolean visible = isHotReloadButtonVisible();
        boolean enabled = isHotReloadButtonEnabledNow();
        requestSetHotReloadButtonVisible(visible);
        requestSetHotReloadButtonEnabled(enabled);
        debugToolbar.setHotReloadEnabled(enabled);
    }

    private boolean isHotReloadButtonEnabledNow() {
        return isHotReloadButtonVisible()
                && debugActive.get()
                && runSupport.isDebugging()
                && !hotReloadBusy.get();
    }

    private boolean isHotReloadButtonVisible() {
        if (!runActive.get() && !debugActive.get()) {
            return false;
        }
        Path project = projectPath;
        if (project == null || !TargetFramework.canRunOnHost(project)) {
            return false;
        }
        RunConfigurationData data = selectedRunConfig;
        return data == null || DotnetRunSupport.isRunType(data) || DotnetRunSupport.isCurrentFileType(data);
    }

    private void clearDebugLine() {
        IdeEditorContext context = debugLineContext;
        int line = debugLineNumber;
        debugLineContext = null;
        debugLineNumber = -1;
        if (context != null && line >= 0) {
            context.removeLineColor(line);
        }
    }

    @Override
    public void stop(RunConfigurationData data) throws Exception {
        runSupport.stop(data);
        runWatchTicket.incrementAndGet();
        runActive.set(false);
        debugFinished();
    }

    @Override
    public void onRunConfigurationChanged(RunConfigurationData data) {
        this.selectedRunConfig = data;

        if (DotnetRunSupport.isCurrentFileType(data)) {
            refreshRunButtonsForCurrentFile();
            return;
        }
        if (!DotnetRunSupport.isDotnetType(data)) {
            return;
        }
        boolean canRun = projectPath != null && TargetFramework.canRunOnHost(projectPath);
        boolean debuggable = DotnetRunSupport.isRunType(data) && canRun;
        SwingUtilities.invokeLater(() -> {

            requestSetRunButtonEnabled(true);
            requestSetDebugButtonEnabled(debuggable);
            refreshHotReloadButton();
        });
    }

    private void refreshRunButtonsForCurrentFile() {
        if (!DotnetRunSupport.isCurrentFileType(selectedRunConfig)) {
            return;
        }
        Path file = activeFile;
        boolean ok = file != null && isCSharpLike(file)
                && DotnetRunSupport.isEntryPointFile(currentTextOf(file))
                && projectPath != null && TargetFramework.canRunOnHost(projectPath);
        SwingUtilities.invokeLater(() -> {
            requestSetRunButtonEnabled(ok);
            requestSetDebugButtonEnabled(ok);
            refreshHotReloadButton();
        });
    }

    private void setActiveFile(Path file) {
        this.activeFile = file == null ? null : normalizePath(file);
        refreshRunButtonsForCurrentFile();
    }

    private String currentTextOf(Path file) {
        if (file == null) {
            return null;
        }
        IdeEditorContext context = editorRegistry.editorContext(normalizePath(file));
        if (context != null) {
            try {
                return context.getText();
            } catch (Exception ignored) {
            }
        }
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ProjectTreeNode resolveProjectTreeReorganization(ProjectTreeNode currentRoot) {
        return DotnetProjectConventions.applyTreeLayout(currentRoot, currentTreeLayout());
    }

    @Override
    public ProjectTreeNode resolveProjectTreePartialReorganization(ProjectTreeNode partialNode,
                                                                   ProjectTreeNode currentRoot) {
        Path changed = partialNode == null ? null : partialNode.getPath();
        if (DotnetProjectConventions.isInsideHiddenArtifact(projectPath, changed)) {
            return null;
        }
        if (currentTreeLayout() == TreeLayout.VISUAL_STUDIO) {
            return DotnetProjectConventions.buildFilesystemTree(projectPath);
        }
        return DotnetProjectConventions.hideRootBuildArtifacts(partialNode);
    }

    private TreeLayout currentTreeLayout() {
        DotnetPluginSettings settings = ensurePluginSettings();
        return settings != null ? settings.getTreeLayout() : TreeLayout.DEFAULT;
    }

    @Override
    public List<ProjectTreeIgnoreRule> resolveProjectTreeIgnoredFolders(Path projectPath) {
        return List.of(
                ProjectTreeIgnoreRule.any("bin"),
                ProjectTreeIgnoreRule.any("obj"),
                ProjectTreeIgnoreRule.any(".vs")
        );
    }

    @Override
    public void contributeProjectTreeMenu(IdeMenuBuilder menu, List<Path> selectedPaths) {
        if (menu == null || selectedPaths == null || selectedPaths.size() != 1) return;
        Path selected = selectedPaths.get(0);
        if (selected == null) return;

        Path buildTarget = resolveMenuBuildTarget(selected);

        if (Files.isDirectory(selected)) {
            menu.into("tree.new").item("C# Class / Interface...", newCSharpItemIcon(), e -> openNewCSharpItem(selected));
        } else if (buildTarget != null) {
            contributeBuildTargetNewMenu(menu, buildTarget);
        }

        if (buildTarget == null) {
            return;
        }

        boolean solution = isSolution(buildTarget);
        String suffix = solution ? " Solução" : " Projeto";
        menu.separator();
        if (solution) {
            menu.item("Abrir arquivo da solução", e -> requestOpenFile(buildTarget));
        }
        menu.item(solution ? "Gerenciar pacotes NuGet da Solução" : "Gerenciar pacotes NuGet",
                e -> openNuGetManager(buildTarget));
        menu.item("Adicionar referência de projeto...",
                e -> {
                    if (solution) {
                        openSolutionReferenceManager(buildTarget);
                    } else {
                        openProjectReferenceManager(buildTarget);
                    }
                });
        menu.separator();
        menu.item("Compilar" + suffix,
                e -> runDotnetOnTarget(buildTarget, "Compilar", List.of("build")));
        menu.item("Recompilar" + suffix,
                e -> runDotnetOnTarget(buildTarget, "Recompilar", List.of("build", "--no-incremental")));
        menu.item("Limpar" + suffix,
                e -> runDotnetOnTarget(buildTarget, "Limpar", List.of("clean")));
    }

    private void contributeBuildTargetNewMenu(IdeMenuBuilder menu, Path buildTarget) {
        boolean solution = isSolution(buildTarget);
        Path projectDir = buildTarget.getParent();
        menu.submenu("New", newCSharpItemIcon(), sub -> {
            if (solution) {
                sub.item("Projeto .NET...", newProjectIcon(), e -> openNewSolutionProject(buildTarget));
            } else if (projectDir != null) {
                sub.item("Classe / Interface C#...", newCSharpItemIcon(), e -> openNewCSharpItem(projectDir));
                sub.item("Arquivo...", e -> openNewPlainFile(projectDir));
                sub.item("Pasta...", e -> openNewFolder(projectDir));
                sub.separator();
                sub.item("Pacote NuGet...", e -> openNuGetManager(buildTarget));
            }
        });
    }

    private void openNewPlainFile(Path dir) {
        runOnUiThread(() -> {
            JTextField field = new JTextField(24);
            String value = createModernInputDialogBuilder()
                    .title("Novo arquivo")
                    .message("Nome do arquivo (com extensão)")
                    .input(field)
                    .confirmText("Criar")
                    .cancelText("Cancelar")
                    .draggable(true)
                    .enterConfirms(true)
                    .show();
            String name = value == null ? "" : value.trim();
            if (name.isEmpty()) {
                return;
            }
            Path file = dir.resolve(name);
            if (Files.exists(file)) {
                setStatusBarText("Já existe " + file.getFileName());
                requestOpenFile(file);
                return;
            }
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, "", StandardCharsets.UTF_8);
                requestProjectTreeViewRefresh();
                requestOpenFile(file);
                setStatusBarText("Criado " + file.getFileName());
            } catch (Exception e) {
                setStatusBarText("Falha ao criar arquivo: " + e.getMessage());
            }
        });
    }

    private void openNewFolder(Path dir) {
        runOnUiThread(() -> {
            JTextField field = new JTextField(24);
            String value = createModernInputDialogBuilder()
                    .title("Nova pasta")
                    .message("Nome da pasta")
                    .input(field)
                    .confirmText("Criar")
                    .cancelText("Cancelar")
                    .draggable(true)
                    .enterConfirms(true)
                    .show();
            String name = value == null ? "" : value.trim();
            if (name.isEmpty()) {
                return;
            }
            Path folder = dir.resolve(name);
            if (Files.exists(folder)) {
                setStatusBarText("Já existe " + folder.getFileName());
                return;
            }
            try {
                Files.createDirectories(folder);
                requestProjectTreeViewRefresh();
                setStatusBarText("Criada pasta " + folder.getFileName());
            } catch (Exception e) {
                setStatusBarText("Falha ao criar pasta: " + e.getMessage());
            }
        });
    }

    private Path resolveMenuBuildTarget(Path selected) {
        if (selected == null) {
            return null;
        }
        if (isBuildTarget(selected)) {
            return selected;
        }
        if (currentTreeLayout() == TreeLayout.VISUAL_STUDIO && Files.isDirectory(selected)) {
            return DotnetProjectConventions.findSolutionOrProjectFile(selected);
        }
        return null;
    }

    private Icon newCSharpItemIcon() {
        return ImageUtils.getIconByResource(DotnetIdeAdapter.class, "imgs/csharpNew.svg")
                .map(icon -> ImageUtils.resizeIcon(icon, 16, 16))
                .orElse(null);
    }

    private Icon newProjectIcon() {
        return ImageUtils.getIconByResource(DotnetIdeAdapter.class, "imgs/dotnet/csProj.svg")
                .map(icon -> ImageUtils.resizeIcon(icon, 16, 16))
                .orElse(null);
    }

    private void openNewSolutionProject(Path solution) {
        Path solutionDir = solution.getParent() != null ? solution.getParent() : projectPath;
        if (solutionDir == null) {
            setStatusBarText("Pasta da solução indisponível.");
            return;
        }
        runOnUiThread(() -> {
            NewSolutionProjectPanel panel = new NewSolutionProjectPanel(solutionDir);
            NewSolutionProjectPanel.Spec spec = createModernComponentDialogBuilder(NewSolutionProjectPanel.Spec.class)
                    .title("Novo projeto .NET na solução")
                    .draggable(true)
                    .showIcon(false)
                    .accentColor(new Color(59, 130, 246))
                    .confirmText("Criar")
                    .cancelText("Cancelar")
                    .enterConfirms(true)
                    .component(panel)
                    .result(ctx -> panel.getSpec())
                    .show();
            if (spec != null) {
                createSolutionProject(solution, spec);
            }
        });
    }

    private void createSolutionProject(Path solution, NewSolutionProjectPanel.Spec spec) {
        Thread thread = new Thread(() -> {
            OutputPanelHandle panel = requestOutputPanel("dotnet");
            SwingUtilities.invokeLater(() -> {
                panel.clear();
                panel.show();
            });
            OutputStream out = panel.getOutputStream();
            Path csproj;
            try {
                csproj = spec.scaffold();
                writeLine(out, "Projeto criado em " + csproj);
            } catch (Exception e) {
                writeLine(out, "[erro] Falha ao criar projeto: " + e.getMessage());
                SwingUtilities.invokeLater(() -> setStatusBarText("Falha ao criar projeto: " + e.getMessage()));
                return;
            }
            DotnetSdkService sdk = ensureSdkService();
            Path dotnet;
            try {
                dotnet = sdk == null ? null : sdk.ensureDotnet(solution, progressListener());
            } catch (Exception e) {
                dotnet = null;
            }
            int code = -1;
            if (dotnet != null) {
                code = ProjectReferenceService.addProjectToSolution(dotnet, solution, csproj, out);
            } else {
                writeLine(out, "[erro] dotnet não encontrado para adicionar o projeto à solução.");
            }
            int result = code;
            Path created = csproj;
            SwingUtilities.invokeLater(() -> {
                requestProjectTreeViewRefresh();
                requestOpenFile(created);
                if (result == 0) {
                    setStatusBarText("Projeto adicionado à solução.");
                } else {
                    setStatusBarText("Projeto criado, mas falhou ao adicionar à solução.");
                }
            });
        }, "dotnet-solution-new-project");
        thread.setDaemon(true);
        thread.start();
    }

    private void openSolutionReferenceManager(Path solution) {
        List<Path> projects = TargetFramework.findProjectFilesInSolution(solution).stream()
                .map(p -> p.toAbsolutePath().normalize())
                .distinct()
                .toList();
        if (projects.size() < 2) {
            setStatusBarText("A solução precisa de pelo menos dois projetos para configurar referências.");
            return;
        }
        runOnUiThread(() -> {
            SolutionReferenceDialog panel = new SolutionReferenceDialog(projects);
            Boolean ok = createModernComponentDialogBuilder(Boolean.class)
                    .title("Gerenciar referências de projeto da solução")
                    .draggable(true)
                    .showIcon(false)
                    .accentColor(new Color(59, 130, 246))
                    .confirmText("Aplicar")
                    .cancelText("Cancelar")
                    .component(panel)
                    .result(ctx -> Boolean.TRUE)
                    .show();
            if (ok != null) {
                Path source = panel.getSourceProject();
                if (source == null) {
                    return;
                }
                Set<Path> before = new HashSet<>(ProjectReferenceService.listProjectReferences(source));
                applyProjectReferences(source, panel.getCandidates(), before, panel.getSelected());
            }
        });
    }

    private void openNewCSharpItem(Path dir) {
        runOnUiThread(() -> {
            NewCSharpItemPanel panel = new NewCSharpItemPanel();
            NewCSharpItemPanel.Result result = createModernComponentDialogBuilder(NewCSharpItemPanel.Result.class)
                    .title("Novo item C#")
                    .draggable(true)
                    .showIcon(false)
                    .accentColor(new Color(59, 130, 246))
                    .confirmText("Criar")
                    .cancelText("Cancelar")
                    .enterConfirms(true)
                    .component(panel)
                    .result(ctx -> panel.getResult())
                    .show();
            if (result != null) {
                createCSharpFile(dir, result.name(), result.kind());
            }
        });
    }

    private void createCSharpFile(Path dir, String name, NewCSharpItemPanel.Kind kind) {
        String typeName = effectiveTypeName(name, kind);
        Path file = dir.resolve(typeName + ".cs");
        if (Files.exists(file)) {
            setStatusBarText("Já existe " + file.getFileName());
            requestOpenFile(file);
            return;
        }
        String content = renderCSharpTemplate(deriveNamespace(dir), typeName, kind);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            requestProjectTreeViewRefresh();
            requestOpenFile(file);
            setStatusBarText("Criado " + file.getFileName());
        } catch (Exception e) {
            setStatusBarText("Falha ao criar arquivo: " + e.getMessage());
        }
    }

    private static String effectiveTypeName(String name, NewCSharpItemPanel.Kind kind) {
        if (kind == NewCSharpItemPanel.Kind.ATTRIBUTE && !name.endsWith("Attribute")) {
            return name + "Attribute";
        }
        return name;
    }

    private static String renderCSharpTemplate(String namespaceName, String name, NewCSharpItemPanel.Kind kind) {
        if (kind == NewCSharpItemPanel.Kind.ATTRIBUTE) {
            return "using System;\n\n"
                    + "namespace " + namespaceName + "\n{\n"
                    + "    [AttributeUsage(AttributeTargets.All, AllowMultiple = false, Inherited = true)]\n"
                    + "    public sealed class " + name + " : Attribute\n"
                    + "    {\n    }\n}\n";
        }
        String member = switch (kind) {
            case INTERFACE -> "public interface " + name;
            case RECORD -> "public record " + name;
            case STRUCT -> "public struct " + name;
            case ENUM -> "public enum " + name;
            default -> "public class " + name;
        };
        return "namespace " + namespaceName + "\n{\n    " + member + "\n    {\n    }\n}\n";
    }

    private String deriveNamespace(Path dir) {
        Path csproj = owningProjectFile(dir);
        String root = projectFileNamespace(csproj);
        Path projectDir = csproj != null && csproj.getParent() != null
                ? csproj.getParent().toAbsolutePath().normalize()
                : (projectPath != null ? projectPath.toAbsolutePath().normalize() : null);
        if (projectDir == null || dir == null) {
            return root;
        }
        try {
            Path target = dir.toAbsolutePath().normalize();
            if (!target.startsWith(projectDir)) {
                return root;
            }
            Path relative = projectDir.relativize(target);
            StringBuilder ns = new StringBuilder(root);
            for (Path part : relative) {
                String p = part.toString();
                if (p.isEmpty() || ".".equals(p) || "..".equals(p)) {
                    continue;
                }
                ns.append('.').append(sanitizeNamespacePart(p));
            }
            return ns.toString();
        } catch (Exception e) {
            return root;
        }
    }

    private Path owningProjectFile(Path dir) {
        Path csproj = TargetFramework.findProjectFileForSource(dir, projectPath);
        if (csproj != null) {
            return csproj;
        }
        return TargetFramework.findPrimaryProjectFile(projectPath);
    }

    private String projectFileNamespace(Path csproj) {
        String base;
        if (csproj != null && csproj.getFileName() != null) {
            base = csproj.getFileName().toString().replaceFirst("(?i)\\.(csproj|vbproj|fsproj)$", "");
        } else if (projectPath != null && projectPath.getFileName() != null) {
            base = projectPath.getFileName().toString();
        } else {
            base = "App";
        }
        return sanitizeNamespacePart(base);
    }

    private static String sanitizeNamespacePart(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        if (sb.isEmpty()) {
            return "App";
        }
        if (Character.isDigit(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    private static boolean isBuildTarget(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sln") || name.endsWith(".slnx") || name.endsWith(".csproj")
                || name.endsWith(".vbproj") || name.endsWith(".fsproj");
    }

    private static boolean isSolution(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sln") || name.endsWith(".slnx");
    }

    private Path resolveBuildTarget() {
        Path root = projectPath;
        if (root == null) {
            return null;
        }
        try (var stream = Files.list(root)) {
            Path solution = stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(DotnetIdeAdapter::isSolution)
                    .findFirst()
                    .orElse(null);
            if (solution != null) {
                return solution;
            }
        } catch (Exception ignored) {
        }
        return TargetFramework.findPrimaryProjectFile(root);
    }

    private void buildSolution(String title, List<String> verbAndArgs) {
        Path target = resolveBuildTarget();
        if (target == null) {
            SwingUtilities.invokeLater(() -> setStatusBarText("Nenhuma solução/projeto .NET aberto."));
            return;
        }
        runDotnetOnTarget(target, title, verbAndArgs);
    }

    private void openPublishDialog() {
        Path target = resolveBuildTarget();
        if (target == null) {
            SwingUtilities.invokeLater(() -> setStatusBarText("Nenhuma solução/projeto .NET aberto."));
            return;
        }
        List<String> tfms = TargetFramework.resolveTfms(target);
        boolean netFrameworkOnly = TargetFramework.isNetFrameworkOnly(target);
        Path projectDir = target.getParent() != null ? target.getParent() : projectPath;
        runOnUiThread(() -> {
            DotnetPublishPanel panel = new DotnetPublishPanel(tfms, netFrameworkOnly, projectDir);
            DotnetPublishPanel.PublishOptions options = createModernComponentDialogBuilder(
                    DotnetPublishPanel.PublishOptions.class)
                    .title("Publicar projeto .NET")
                    .draggable(true)
                    .showIcon(false)
                    .accentColor(new Color(59, 130, 246))
                    .confirmText("Publicar")
                    .cancelText("Cancelar")
                    .component(panel)
                    .result(ctx -> panel.getOptions())
                    .show();
            if (options != null) {
                runDotnetOnTarget(target, "Publicar (" + options.configuration() + ")",
                        buildPublishArgs(options));
            }
        });
    }

    private List<String> buildPublishArgs(DotnetPublishPanel.PublishOptions options) {
        List<String> args = new ArrayList<>();
        args.add("publish");
        args.add("-c");
        args.add(options.configuration());
        if (options.framework() != null && !options.framework().isBlank()) {
            args.add("-f");
            args.add(options.framework());
        }
        if (options.selfContained()) {
            args.add("--self-contained");
            args.add("true");
            if (options.runtime() != null) {
                args.add("-r");
                args.add(options.runtime());
            }
            if (options.singleFile()) {
                args.add("-p:PublishSingleFile=true");
            }
            if (options.trimmed()) {
                args.add("-p:PublishTrimmed=true");
            }
        }
        if (options.output() != null && !options.output().isBlank()) {
            args.add("-o");
            args.add(options.output());
        }
        return args;
    }

    private void openProjectReferenceManager(Path csproj) {
        Path root = projectPath != null ? projectPath : csproj;
        Path targetNorm = csproj.toAbsolutePath().normalize();
        List<Path> candidates = TargetFramework.findProjectFiles(root).stream()
                .map(p -> p.toAbsolutePath().normalize())
                .distinct()
                .filter(p -> !p.equals(targetNorm))
                .toList();
        if (candidates.isEmpty()) {
            setStatusBarText("Nenhum outro projeto encontrado para referenciar.");
            return;
        }
        Set<Path> referenced = new HashSet<>(ProjectReferenceService.listProjectReferences(csproj));
        runOnUiThread(() -> {
            ProjectReferenceDialog panel = new ProjectReferenceDialog(csproj, candidates, referenced);
            Boolean ok = createModernComponentDialogBuilder(Boolean.class)
                    .title("Gerenciar referências de projeto")
                    .draggable(true)
                    .showIcon(false)
                    .accentColor(new Color(59, 130, 246))
                    .confirmText("Aplicar")
                    .cancelText("Cancelar")
                    .component(panel)
                    .result(ctx -> Boolean.TRUE)
                    .show();
            if (ok != null) {
                applyProjectReferences(csproj, candidates, referenced, panel.getSelected());
            }
        });
    }

    private void applyProjectReferences(Path csproj, List<Path> candidates, Set<Path> before, Set<Path> after) {
        Thread thread = new Thread(() -> {
            DotnetSdkService sdk = ensureSdkService();
            Path dotnet;
            try {
                dotnet = sdk == null ? null : sdk.ensureDotnet(csproj, progressListener());
            } catch (Exception e) {
                dotnet = null;
            }
            if (dotnet == null) {
                SwingUtilities.invokeLater(() -> setStatusBarText("dotnet não encontrado para referência de projeto."));
                return;
            }
            OutputPanelHandle panel = requestOutputPanel("dotnet");
            SwingUtilities.invokeLater(() -> {
                panel.clear();
                panel.show();
            });
            OutputStream out = panel.getOutputStream();
            int changes = 0;
            for (Path candidate : candidates) {
                boolean was = before.contains(candidate);
                boolean now = after.contains(candidate);
                if (now && !was) {
                    ProjectReferenceService.addProjectReference(dotnet, csproj, candidate, out);
                    changes++;
                } else if (!now && was) {
                    ProjectReferenceService.removeProjectReference(dotnet, csproj, candidate, out);
                    changes++;
                }
            }
            int total = changes;
            SwingUtilities.invokeLater(() -> {
                requestProjectTreeViewRefresh();
                setStatusBarText(total == 0
                        ? "Nenhuma alteração de referência."
                        : "Referências de projeto atualizadas (" + total + ").");
            });
        }, "dotnet-project-reference");
        thread.setDaemon(true);
        thread.start();
    }

    private void runDotnetOnTarget(Path target, String title, List<String> verbAndArgs) {
        Thread thread = new Thread(() -> {
            DotnetSdkService sdk = ensureSdkService();
            java.util.Optional<Path> dotnet;
            try {
                dotnet = sdk == null ? java.util.Optional.empty()
                        : java.util.Optional.of(sdk.ensureDotnet(target, progressListener()));
            } catch (Exception e) {
                dotnet = java.util.Optional.empty();
            }
            if (dotnet.isEmpty()) {
                SwingUtilities.invokeLater(() -> setStatusBarText("dotnet não encontrado para " + title + "."));
                return;
            }
            OutputPanelHandle panel = requestOutputPanel("dotnet");
            SwingUtilities.invokeLater(() -> {
                panel.clear();
                panel.show();
            });
            List<String> command = new ArrayList<>();
            command.add(dotnet.get().toString());
            command.addAll(verbAndArgs);
            command.add(target.toString());
            OutputStream out = panel.getOutputStream();
            try {
                writeLine(out, "> " + String.join(" ", command));
                Path workDir = target.getParent() != null ? target.getParent() : projectPath;
                ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
                if (workDir != null) {
                    builder.directory(workDir.toFile());
                }
                builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
                Process process = builder.start();
                try (var in = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                }
                int code = process.waitFor();
                writeLine(out, System.lineSeparator() + "[" + title + "] finalizado com código " + code);
                SwingUtilities.invokeLater(() -> setStatusBarText(
                        title + (code == 0 ? " concluído." : " falhou (código " + code + ").")));
            } catch (Exception e) {
                writeLine(out, System.lineSeparator() + "[erro] " + e.getMessage());
            }
        }, "dotnet-tree-build");
        thread.setDaemon(true);
        thread.start();
    }

    private static void writeLine(OutputStream out, String text) {
        try {
            out.write((text + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        }
    }

    @Override
    public Collection<String> getIgnoredSearchExtensions(Collection<String> defaults) {
        List<String> ignored = new ArrayList<>(defaults == null ? List.of() : defaults);
        for (String extension : List.of("dll", "exe", "pdb", "nupkg", "cache")) {
            if (!ignored.contains(extension)) {
                ignored.add(extension);
            }
        }
        return ignored;
    }
}
