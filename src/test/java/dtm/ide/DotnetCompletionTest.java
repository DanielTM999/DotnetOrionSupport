package dtm.ide;

import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.ide.concurrent.EditorQueryCache;
import dtm.ide.lsp.LspService;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DotnetCompletionTest {
    @Test void localSuggestionsArriveBeforeSlowSemanticCompletion() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var service = (LspService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{LspService.class}, (proxy, method, args) -> {
            if (method.getName().equals("isRunning")) return true;
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("completeForEditor")) {
                entered.countDown(); release.await(3, TimeUnit.SECONDS);
                return List.of(AutoCompleteItem.snippet("cwRemote", "cwRemote()", "remote"));
            }
            if (method.getReturnType() == boolean.class) return false;
            return null;
        });
        var adapter = new DotnetIdeAdapter();
        var field = DotnetIdeAdapter.class.getDeclaredField("lspService"); field.setAccessible(true); field.set(adapter, service);
        var context = new IdeCompletionContext("cw", Path.of("Program.cs"), 2, 0, 2, "cw", "cw", 0, IdeCompletionTriggerKind.EXPLICIT);
        try {
            var initial = adapter.getCompletionSuggestionsAsync(context);
            assertTrue(initial.isDone(), "local suggestions must not await the language server");
            assertTrue(initial.join().stream().anyMatch(item -> item.label().equals("cwl")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            List<AutoCompleteItem> merged = List.of();
            while (System.nanoTime() < deadline) {
                merged = adapter.getCompletionSuggestionsAsync(context).join();
                if (merged.stream().anyMatch(item -> item.label().equals("cwRemote"))) break;
                Thread.sleep(10);
            }
            assertTrue(merged.stream().anyMatch(item -> item.label().equals("cwRemote")));
        } finally {
            release.countDown();
            var cache = DotnetIdeAdapter.class.getDeclaredField("completionQueries"); cache.setAccessible(true);
            ((EditorQueryCache<?>) cache.get(adapter)).close();
        }
    }

    @Test void msbuildCompletionUsesTheXmlFileInsteadOfAnUnrelatedCsharpEditor() {
        var adapter = new DotnetIdeAdapter();
        String source = "<Project><ItemGroup><PackageR";
        var context = new IdeCompletionContext(source, Path.of("Demo.csproj"), source.length(), 0, source.length(),
                source, "PackageR", source.length() - 8, IdeCompletionTriggerKind.EXPLICIT);
        var suggestions = adapter.getCompletionSuggestionsAsync(context).join();
        assertTrue(suggestions.stream().anyMatch(item -> item.label().equals("PackageReference")));
    }
}
