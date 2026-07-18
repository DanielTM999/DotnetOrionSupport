package dtm.ide;

import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.lsp.LspService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetIdeAdapterTest {

    @Test
    void decompiledEditorsRemainReadOnlyButStillApplySyntaxHighlight(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("metadata-cache").resolve("WebApplication.cs");
        DotnetIdeAdapter adapter = new DotnetIdeAdapter();
        setLspService(adapter, decompiledService(file));

        EditorProbe probe = new EditorProbe(file);
        adapter.configureEditor(probe.context());

        assertTrue(probe.readOnly, "decompiled editor should be read-only");
        assertFalse(probe.diagnosticsAutoRunEnabled, "decompiled editor should not run diagnostics");
        assertTrue(probe.syntaxHighlightEnabled, "decompiled editor should still enable syntax highlight");
        assertTrue(probe.syntaxHighlightApplied, "decompiled editor should apply syntax highlight");
    }

    private static void setLspService(DotnetIdeAdapter adapter, LspService service) throws Exception {
        Field field = DotnetIdeAdapter.class.getDeclaredField("lspService");
        field.setAccessible(true);
        field.set(adapter, service);
    }

    private static LspService decompiledService(Path decompiledPath) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("isDecompiled".equals(name)) {
                Path file = args != null && args.length > 0 && args[0] instanceof Path path ? path : null;
                return file != null && file.toAbsolutePath().normalize()
                        .equals(decompiledPath.toAbsolutePath().normalize());
            }
            if ("getState".equals(name)) {
                return LspService.State.READY;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (List.class.isAssignableFrom(method.getReturnType())) {
                return List.of();
            }
            if (Collection.class.isAssignableFrom(method.getReturnType())) {
                return List.of();
            }
            return null;
        };
        return (LspService) Proxy.newProxyInstance(
                LspService.class.getClassLoader(),
                new Class<?>[]{LspService.class},
                handler);
    }

    private static final class EditorProbe {
        private final Path file;
        private boolean readOnly;
        private boolean syntaxHighlightEnabled;
        private boolean syntaxHighlightApplied;
        private boolean diagnosticsAutoRunEnabled = true;

        private EditorProbe(Path file) {
            this.file = file;
        }

        private IdeEditorContext context() {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "filePath" -> {
                        return file;
                    }
                    case "getText" -> {
                        return "public sealed class WebApplication { }";
                    }
                    case "setReadOnly" -> {
                        readOnly = Boolean.TRUE.equals(args[0]);
                        return null;
                    }
                    case "isReadOnly" -> {
                        return readOnly;
                    }
                    case "setSyntaxHighlightEnabled" -> {
                        syntaxHighlightEnabled = Boolean.TRUE.equals(args[0]);
                        return null;
                    }
                    case "applySyntaxHighlight" -> {
                        syntaxHighlightApplied = true;
                        return null;
                    }
                    case "setDiagnosticsAutoRunEnabled" -> {
                        diagnosticsAutoRunEnabled = Boolean.TRUE.equals(args[0]);
                        return null;
                    }
                    default -> {
                        return defaultValue(method.getReturnType());
                    }
                }
            };
            return (IdeEditorContext) Proxy.newProxyInstance(
                    IdeEditorContext.class.getClassLoader(),
                    new Class<?>[]{IdeEditorContext.class},
                    handler);
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == void.class) {
                return null;
            }
            if (Collection.class.isAssignableFrom(type)) {
                return List.of();
            }
            return null;
        }
    }
}
