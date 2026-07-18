package dtm.ide;

import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.editor.tokenizer.CSharpTokenizerProvider;
import dtm.ide.editor.tokenizer.RazorTokenizerProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class DotnetEditorRegistry {

    private final Map<Path, TokenizerCodeEditorProvider> tokenizers = new ConcurrentHashMap<>();
    private final Map<Path, IdeEditorContext> editorContexts = new ConcurrentHashMap<>();
    private final Set<Path> editorPaths = ConcurrentHashMap.newKeySet();

    TokenizerCodeEditorProvider tokenizerFor(Path filePath) {
        if (filePath == null || !DotnetProjectConventions.isHighlightable(filePath)) {
            return null;
        }
        return tokenizers.computeIfAbsent(DotnetProjectConventions.normalizePath(filePath),
                p -> DotnetProjectConventions.isRazorLike(p)
                        ? new RazorTokenizerProvider()
                        : new CSharpTokenizerProvider());
    }

    void trackEditor(Path normalizedPath, IdeEditorContext context) {
        if (normalizedPath == null || !DotnetProjectConventions.isHighlightable(normalizedPath)) {
            return;
        }
        editorPaths.add(normalizedPath);
        if (context != null) {
            editorContexts.put(normalizedPath, context);
        }
    }

    void close(Path filePath) {
        Path normalized = DotnetProjectConventions.normalizePath(filePath);
        editorPaths.remove(normalized);
        editorContexts.remove(normalized);
    }

    void delete(Path oldPath) {
        Path normalized = DotnetProjectConventions.normalizePath(oldPath);
        tokenizers.remove(normalized);
        editorPaths.remove(normalized);
        editorContexts.remove(normalized);
    }

    void rename(Path oldPath, Path newPath) {
        Path oldNormalized = DotnetProjectConventions.normalizePath(oldPath);
        TokenizerCodeEditorProvider provider = tokenizers.remove(oldNormalized);
        boolean wasOpenEditor = editorPaths.remove(oldNormalized);
        IdeEditorContext editorContext = editorContexts.remove(oldNormalized);
        if (newPath != null && DotnetProjectConventions.isHighlightable(newPath)) {
            Path newNormalized = DotnetProjectConventions.normalizePath(newPath);
            if (provider != null) {
                tokenizers.put(newNormalized, provider);
            }
            if (wasOpenEditor) {
                editorPaths.add(newNormalized);
            }
            if (editorContext != null && DotnetProjectConventions.samePath(editorContext.filePath(), newPath)) {
                editorContexts.put(newNormalized, editorContext);
            }
        }
    }

    void clearProjectEditors() {
        tokenizers.clear();
        editorPaths.clear();
        editorContexts.clear();
    }

    boolean hasOpenEditors() {
        return !editorPaths.isEmpty();
    }

    boolean hasOpenRazorEditors() {
        return editorPaths.stream()
                .filter(Objects::nonNull)
                .anyMatch(DotnetProjectConventions::isRazorLike);
    }

    IdeEditorContext editorContext(Path normalizedPath) {
        return editorContexts.get(normalizedPath);
    }

    Path openHighlightablePathForText(String text) {
        if (text == null) {
            return null;
        }
        for (Map.Entry<Path, IdeEditorContext> entry : editorContexts.entrySet()) {
            IdeEditorContext context = entry.getValue();
            try {
                if (context != null
                        && DotnetProjectConventions.isHighlightable(entry.getKey())
                        && Objects.equals(context.getText(), text)) {
                    return entry.getKey();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    List<Path> regularOpenHighlightablePaths() {
        return editorPaths.stream()
                .filter(Objects::nonNull)
                .filter(DotnetProjectConventions::isHighlightable)
                .filter(Files::isRegularFile)
                .toList();
    }

    Path singleOpenHighlightablePath() {
        List<Path> paths = regularOpenHighlightablePaths();
        return paths.size() == 1 ? paths.getFirst() : null;
    }
}
