package dtm.ide;

import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.context.IdeProjectContext;
import dtm.ide.api.extension.IdeAdapter;
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
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.api.project.tree.ProjectTreeIgnoreRule;
import dtm.ide.api.project.tree.ProjectTreeNode;
import dtm.ide.api.theme.EditorTheme;
import dtm.ide.editor.theme.DotnetEditorTheme;
import dtm.ide.lsp.DotnetLspService;
import dtm.ide.lsp.DotnetWorkspaceEdit;
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
import dtm.ide.run.DotnetRunSupport;
import dtm.ide.run.TargetFramework;
import dtm.ide.sdk.DotnetSdkService;
import dtm.ide.settings.DotnetPluginSettings;
import dtm.ide.settings.DotnetSettingsPage;
import dtm.ide.ui.DotnetProjectConfigPanel;
import dtm.ide.ui.NewCSharpItemPanel;
import dtm.ide.ui.NuGetManagerPanel;
import dtm.ide.ui.ProjectReferenceDialog;
import dtm.request_actions.http.download.core.DownloadObserver;
import dtm.stools.component.menu.bar.tree.MenuNode;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
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
    private volatile DotnetLspService lspService;
    private final DotnetRunSupport runSupport = new DotnetRunSupport();
    private volatile ExecutorService languageSetupExecutor;
    private volatile ExecutorService navigationExecutor;
    private volatile NuGetManagerPanel nugetPanel;
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
    private static final String NAV_PROGRESS_ID = "dotnetNavigate";

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
        startLanguageServicesAsync();
    }

    @Override
    public void onProjectClosed(IdeProjectContext context) {
        projectLifecycleTicket.incrementAndGet();
        shutdownProjectProcesses();
        debugFinished();
        wordCaretTicket.incrementAndGet();
        SwingUtilities.invokeLater(this::hideCodeActionLamp);
        DotnetLspService service = lspService;
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
    }

    @Override
    public void clearCaches() {
        DotnetLspService service = lspService;
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
        runSupport.bindDebugSessionStateListener(this::onDebugSessionStateChanged);
        if (projectPath != null) {
            boolean canRun = TargetFramework.canRunOnHost(projectPath);
            SwingUtilities.invokeLater(() -> {
                requestSetRunButtonEnabled(true);
                requestSetDebugButtonEnabled(canRun);
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
                boolean needDotnet = sdk.getDotnetPath(requiredSdkVersion).isEmpty();
                boolean needOmnisharp = sdk.getOmniSharpPath().isEmpty();

                if ((needDotnet || needOmnisharp)
                        && !confirmToolchainDownload(needDotnet ? requiredSdkVersion : null, needOmnisharp)) {
                    return;
                }

                if (needDotnet) {
                    sdk.ensureDotnet(requiredSdkVersion, progress);
                }

                if (needOmnisharp) {
                    sdk.ensureOmniSharp(progress);
                }
                if (!isProjectCurrent(ticket, project)) {
                    return;
                }
                ensureProjectRestored(sdk, project, requiredSdkVersion, ticket);
                if (!isProjectCurrent(ticket, project)) {
                    return;
                }
                DotnetLspService service = ensureLspService(sdk);
                service.bindProject(project);

                SwingUtilities.invokeLater(() -> showProgress(LSP_PROGRESS_ID, "Iniciando Intellisense (C#)..."));
                try {
                    service.start();
                } finally {
                    SwingUtilities.invokeLater(() -> hideProgress(LSP_PROGRESS_ID));
                }
                if (!isProjectCurrent(ticket, project)) {
                    service.stop();
                    return;
                }
                if (service.isRunning()) {
                    SwingUtilities.invokeLater(() -> {
                        createNotification(new dtm.ide.api.extension.NotificationContext("C#", "IntelliSense ativo."));
                    });

                    refreshOpenDiagnostics();
                } else {
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
            Process process = new ProcessBuilder(dotnet.toAbsolutePath().toString(), "restore")
                    .directory(project.toFile())
                    .redirectErrorStream(true)
                    .start();
            drainQuietly(process.getInputStream());
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Falha ao restaurar pacotes do projeto: {}", e.getMessage());
        }
    }

    private static boolean needsRestore(List<Path> projectFiles) {
        for (Path projectFile : projectFiles) {
            Path dir = projectFile.getParent();
            if (dir != null && !Files.isRegularFile(dir.resolve("obj").resolve("project.assets.json"))) {
                return true;
            }
        }
        return false;
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
        DotnetLspService service = lspService;
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

    private synchronized DotnetLspService ensureLspService(DotnetSdkService sdk) {
        if (lspService != null) {
            return lspService;
        }
        lspService = new DotnetLspService(getResource(), sdk);
        lspService.addDiagnosticsPublishedListener(uri -> {
            Path file = DotnetProjectConventions.pathFromUri(uri);
            if (file != null) {
                requestRefreshDiagnostics(file);
            }
        });
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

    private boolean confirmToolchainDownload(String dotnetSdkVersion, boolean needOmnisharp) {
        if (toolchainDeclined.get()) {
            return false;
        }
        List<String> missing = new ArrayList<>();
        if (dotnetSdkVersion != null && !dotnetSdkVersion.isBlank()) {
            missing.add(".NET SDK " + dotnetSdkVersion);
        }
        if (needOmnisharp) {
            missing.add("Intellisense " + DotnetSdkService.DEFAULT_OMNISHARP_VERSION + " (language server C#)");
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

    private void triggerDiagnostics(Path file, String text) {
        DotnetLspService service = lspService;
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

    private void refreshOpenDiagnostics() {
        for (Path file : editorRegistry.regularOpenCsPaths()) {
            IdeEditorContext context = editorRegistry.editorContext(normalizePath(file));
            if (context != null) {
                triggerDiagnostics(normalizePath(file), context.getText());
            }
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
        DotnetLspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.complete(context.filePath(), context.text(), context.caretLine(), context.caretCol());
    }

    @Override
    public HoverInfo getHover(IdeHoverContext context) {
        if (debugActive.get()) {
            return null;
        }
        DotnetLspService service = lspService;
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
    public void contributeEditorMenu(IdeMenuBuilder menu, IdeEditorContext editorContext) {
        if (menu == null || editorContext == null || editorContext.filePath() == null
                || !isCSharpLike(editorContext.filePath())) {
            return;
        }
        boolean enabled = debugActive.get();
        String expression = expressionAtEditorContext(editorContext);
        menu.separator()
                .item("Rename Symbol...", isRenameAvailable(editorContext),
                        e -> showRenameSymbolDialog(editorContext))
                .item("Evaluate Expression...", enabled, e -> showEvaluateDialog(editorContext, expression))
                .item("Add Watch", enabled && expression != null && !expression.isBlank(),
                        e -> addWatchExpression(expression));
    }

    @Override
    public List<Location> findDefinitions(IdeDefinitionContext context) {
        DotnetLspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.definitions(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public List<Location> findReferences(IdeDefinitionContext context) {
        DotnetLspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.references(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public List<DocumentSymbol> getDocumentSymbols(IdeDocumentSymbolContext context) {
        DotnetLspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.documentSymbols(context.filePath(), context.text());
    }

    @Override
    public List<TextEdit> computeRenameEdits(IdeRenameContext context) {
        DotnetLspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.rename(context.filePath(), context.text(), context.line(), context.col(), context.newName());
    }

    private boolean isRenameAvailable(IdeEditorContext context) {
        DotnetLspService service = lspService;
        return service != null && service.isRunning()
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
        DotnetLspService service = lspService;
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
        DotnetLspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.getFilePath())) {
            return Collections.emptyList();
        }
        return service.diagnose(context.getFilePath(), context.getText());
    }

    @Override
    public List<CodeAction> getCodeActions(IdeCodeActionContext context) {
        DotnetLspService service = lspService;
        if (service == null || context == null || context.filePath() == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.codeActions(context.filePath(), context.text(), context.range(), context.diagnostics());
    }

    @Override
    public String formatCode(FormatCodeContext context) {
        DotnetLspService service = lspService;
        if (service == null || context == null || context.file() == null || !isCSharpLike(context.file())) {
            return context == null ? null : context.text();
        }
        if (context.formatScope() == IdeFormatScope.SELECTION) {
            return context.text();
        }
        String fullText = context.fullText() == null ? context.text() : context.fullText();
        String formatted = service.format(context.file(), fullText, context.tabSize(), context.useSpacesForTab());
        return formatted == null ? fullText : formatted;
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
        DotnetLspService service = lspService;
        if (service == null || context == null || !isCSharpLike(context.filePath())) {
            return Collections.emptyList();
        }
        return service.prepareCallHierarchy(context.filePath(), context.text(), context.line(), context.col());
    }

    @Override
    public List<CallHierarchyCall> getIncomingCalls(CallHierarchyItem item) {
        DotnetLspService service = lspService;
        return service == null ? Collections.emptyList() : service.incomingCalls(item);
    }

    @Override
    public List<CallHierarchyCall> getOutgoingCalls(CallHierarchyItem item) {
        DotnetLspService service = lspService;
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
        DotnetLspService service = lspService;
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
        DotnetLspService service = lspService;
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
        int y = Math.max(0, Math.min(context.mouseY() - lampSize.height / 2,
                Math.max(0, editorHeight - lampSize.height)));
        return new Point(screenLocation.x + x, screenLocation.y + y);
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
        DotnetLspService service = lspService;
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
        navigateFromEditor(context);
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
        DotnetLspService service = lspService;
        if (service == null) {
            return;
        }
        navigationExecutor().execute(() -> {
            SwingUtilities.invokeLater(() ->
                    showProgress(NAV_PROGRESS_ID, "Abrindo definição (descompilando se necessário)..."));
            try {
                List<Location> targets = service.definitions(file, text, line, col);
                if (!targets.isEmpty()) {
                    navigateToDefinition(targets.get(0));
                }
            } catch (Exception e) {
                log.debug("Falha ao navegar para definição: {}", e.getMessage());
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
        DotnetLspService service = lspService;
        DotnetPluginSettings settings = pluginSettings;
        if (service == null || settings == null || !settings.isFormatOnSave()
                || path == null || !isCSharpLike(path)) {
            return content;
        }
        String formatted = service.format(path, content, 4, true);
        return formatted == null ? content : formatted;
    }

    @Override
    public List<PluginSettingsPage> getSettingsPages() {
        return List.of(new DotnetSettingsPage(ensurePluginSettings()));
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
                .item("dotnetPublishRelease", "Publicar (Release)",
                        e -> buildSolution("Publicar (Release)", List.of("publish", "-c", "Release")))
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
            DotnetLspService service = lspService;
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
            try {
                Process process = new ProcessBuilder(
                        sdk.ensureDotnet(project, progressListener()).toString(), "restore")
                        .directory(project.toFile())
                        .redirectErrorStream(true)
                        .start();
                int code = process.waitFor();
                SwingUtilities.invokeLater(() -> setStatusBarText(
                        code == 0 ? "Pacotes restaurados." : "dotnet restore falhou (código " + code + ")."));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> setStatusBarText("Falha no restore: " + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> hideProgress("dotnetRestore"));
            }
        });
    }

    @Override
    public Collection<RunConfigurationData> getStaticRunConfigurations() {
        return runSupport.staticRunConfigurations();
    }

    @Override
    public RunProcessHandle launch(RunConfigurationData data, RunExecutionContext context) throws Exception {
        return runSupport.launch(data, context);
    }

    @Override
    public RunProcessHandle launchDebug(RunConfigurationData data, RunExecutionContext context) throws Exception {
        debugActive.set(true);
        runOnUiThread(() -> {
            requestSetHotReloadButtonVisible(true);
            requestSetHotReloadButtonEnabled(false);
        });
        return runSupport.launchDebug(data, context);
    }

    @Override
    public void onHotReload(RunConfigurationData data) throws Exception {
        if (!debugActive.get() || !runSupport.isDebugging()) {
            setStatusBarText("Hot reload indisponivel: nenhuma sessao de debug ativa.");
            runOnUiThread(() -> requestSetHotReloadButtonEnabled(false));
            return;
        }
        runOnUiThread(() -> requestSetHotReloadButtonEnabled(false));
        showProgress("dotnetHotReload", "Aplicando hot reload...");
        try {
            DotnetRunSupport.HotReloadResult result = runSupport.hotReload(data);
            setStatusBarText(result.message());
            if (!result.success()) {
                createNotification(new dtm.ide.api.extension.NotificationContext(".NET Hot Reload", result.message()));
                if (confirmRestartAfterHotReloadFailure(result.message())) {
                    setStatusBarText("Reiniciando sessao de debug...");
                    onDebugCommand("restart");
                }
            }
        } finally {
            hideProgress("dotnetHotReload");
            runOnUiThread(() -> requestSetHotReloadButtonEnabled(debugActive.get() && runSupport.isDebugging()));
        }
    }

    private boolean confirmRestartAfterHotReloadFailure(String failureMessage) {
        String detail = failureMessage == null || failureMessage.isBlank()
                ? "Hot Reload falhou."
                : failureMessage;
        String message = detail + System.lineSeparator()
                + "Deseja reiniciar a sessao de debug agora?";
        final int[] result = {-1};
        Runnable show = () -> result[0] = createModernDialogBuilder()
                .title("Hot Reload falhou")
                .draggable(true)
                .message(message)
                .accentColor(new Color(220, 53, 69))
                .option("Reiniciar", 0, new Color(59, 130, 246), Color.WHITE)
                .option("Cancelar", 1, new Color(108, 117, 125), Color.WHITE)
                .type(ModernDialog.Type.QUESTION)
                .show();
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                show.run();
            } else {
                SwingUtilities.invokeAndWait(show);
            }
        } catch (Exception e) {
            log.debug("Falha ao exibir dialogo de hot reload: {}", e.getMessage());
            return false;
        }
        return result[0] == 0;
    }

    private void onDebugSessionStateChanged(boolean active) {
        runOnUiThread(() -> {
            requestSetHotReloadButtonVisible(active);
            requestSetHotReloadButtonEnabled(active);
        });
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
        runOnUiThread(this::clearDebugLine);
        debugValuePopup.hide();
        debugExceptionPopup.hide();
        debugVariablesPanel.clearVariables();
        debugCallStackPanel.clear();
        debugWatchPanel.clearValues();
        runOnUiThread(() -> {
            requestSetHotReloadButtonVisible(false);
            requestSetHotReloadButtonEnabled(false);
        });
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
        runOnUiThread(() -> {
            requestSetHotReloadButtonVisible(false);
            requestSetHotReloadButtonEnabled(false);
        });
        runSupport.stop(data);
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
        return DotnetProjectConventions.hideRootBuildArtifacts(currentRoot);
    }

    @Override
    public ProjectTreeNode resolveProjectTreePartialReorganization(ProjectTreeNode partialNode,
                                                                   ProjectTreeNode currentRoot) {
        Path changed = partialNode == null ? null : partialNode.getPath();
        if (DotnetProjectConventions.isInsideHiddenArtifact(projectPath, changed)) {
            return null;
        }
        return DotnetProjectConventions.hideRootBuildArtifacts(partialNode);
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

        if (Files.isDirectory(selected)) {
            menu.into("tree.new").item("C# Class / Interface...", newCSharpItemIcon(), e -> openNewCSharpItem(selected));
        }
        if (!isBuildTarget(selected)) {
            return;
        }

        boolean solution = isSolution(selected);
        String suffix = solution ? " Solução" : " Projeto";
        menu.separator();
        menu.item(solution ? "Gerenciar pacotes NuGet da Solução" : "Gerenciar pacotes NuGet",
                e -> openNuGetManager(selected));
        if (!solution) {
            menu.item("Adicionar referência de projeto...",
                    e -> openProjectReferenceManager(selected));
        }
        menu.separator();
        menu.item("Compilar" + suffix,
                e -> runDotnetOnTarget(selected, "Compilar", List.of("build")));
        menu.item("Recompilar" + suffix,
                e -> runDotnetOnTarget(selected, "Recompilar", List.of("build", "--no-incremental")));
        menu.item("Limpar" + suffix,
                e -> runDotnetOnTarget(selected, "Limpar", List.of("clean")));
    }

    private Icon newCSharpItemIcon() {
        return ImageUtils.getIconByResource(DotnetIdeAdapter.class, "imgs/csharpNew.svg")
                .map(icon -> ImageUtils.resizeIcon(icon, 16, 16))
                .orElse(null);
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
        Path file = dir.resolve(name + ".cs");
        if (Files.exists(file)) {
            setStatusBarText("Já existe " + file.getFileName());
            requestOpenFile(file);
            return;
        }
        String content = renderCSharpTemplate(deriveNamespace(dir), name, kind);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            requestProjectTreeViewRefresh();
            requestOpenFile(file);
            setStatusBarText("Criado " + file.getFileName());
        } catch (Exception e) {
            setStatusBarText("Falha ao criar arquivo: " + e.getMessage());
        }
    }

    private static String renderCSharpTemplate(String namespaceName, String name, NewCSharpItemPanel.Kind kind) {
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
        String root = projectRootNamespace();
        if (projectPath == null || dir == null) {
            return root;
        }
        try {
            Path relative = projectPath.relativize(dir.toAbsolutePath().normalize());
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

    private String projectRootNamespace() {
        Path csproj = TargetFramework.findPrimaryProjectFile(projectPath);
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
