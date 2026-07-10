package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.api.hierarchy.CallHierarchyCall;
import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.project.editor.DocumentHighlight;
import dtm.ide.api.project.editor.SemanticToken;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public interface LspService {

    String APPLY_CODE_ACTION_COMMAND = "dotnet/applyCodeAction";

    enum State {
        NOT_STARTED, STARTING, READY, STOPPED, ERROR
    }

    interface LoadProgressListener {
        void onProgress(int percent, boolean finished);
    }

    record WorkspaceSymbol(String name, String container, int kind, Location location) {
    }

    void bindProject(Path projectPath);

    State getState();

    String getLastError();

    boolean isRunning();

    boolean isSemanticTokensReady();

    boolean isTypeDefinitionReady();

    default boolean supportsRazor() {
        return false;
    }

    void addDiagnosticsPublishedListener(Consumer<String> listener);

    void addLoadProgressListener(LoadProgressListener listener);

    void start();

    void stop();

    List<AutoCompleteItem> complete(Path filePath, String text, int line, int character);

    List<AutoCompleteItem> completeForEditor(Path filePath, String text, int line, int character, String prefix);

    HoverInfo hover(Path filePath, String text, int line, int character);

    SignatureHelp signatureHelp(Path filePath, String text, int line, int character);

    HoverInfo diagnosticHover(Path filePath, int line, int character);

    List<Location> definitions(Path filePath, String text, int line, int character);

    List<Location> implementations(Path filePath, String text, int line, int character);

    List<Location> typeDefinitions(Path filePath, String text, int line, int character);

    void clearMetadataCache();

    List<Location> references(Path filePath, String text, int line, int character);

    List<DocumentHighlight> documentHighlights(Path filePath, String text, int line, int character);

    List<DocumentSymbol> documentSymbols(Path filePath, String text);

    List<WorkspaceSymbol> workspaceSymbols(String query);

    List<SemanticToken> semanticTokens(Path filePath, String text);

    List<InlayHint> inlayHints(Path filePath, String text, int firstLine, int lastLine);

    List<TextEdit> rename(Path filePath, String text, int line, int character, String newName);

    DotnetWorkspaceEdit renameWorkspace(Path filePath, String text, int line, int character, String newName);

    boolean prepareRename(Path filePath, String text, int line, int character);

    String format(Path filePath, String text, int tabSize, boolean insertSpaces);

    String formatRange(Path filePath, String fullText, int startOffset, int endOffset, int tabSize, boolean insertSpaces);

    boolean isOnTypeFormattingSupported();

    boolean isOnTypeTrigger(char ch);

    List<TextEdit> onTypeFormatting(Path filePath, String text, int line, int character,
                                    String ch, int tabSize, boolean insertSpaces);

    Collection<Diagnostic> diagnose(Path filePath, String text);

    Diagnostic diagnosticAt(Path filePath, int line, int character);

    List<CodeAction> codeActions(Path filePath, String text, Range range, List<Diagnostic> diagnostics);

    DotnetWorkspaceEdit resolveCodeActionEdit(JsonNode rawAction);

    boolean isCallHierarchySupported();

    List<CallHierarchyItem> prepareCallHierarchy(Path filePath, String text, int line, int character);

    List<CallHierarchyCall> incomingCalls(CallHierarchyItem item);

    List<CallHierarchyCall> outgoingCalls(CallHierarchyItem item);

    void setApplyEditSink(Consumer<DotnetWorkspaceEdit> sink);
}
