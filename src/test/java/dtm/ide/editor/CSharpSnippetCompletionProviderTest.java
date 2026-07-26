package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CSharpSnippetCompletionProviderTest {

    private final CSharpSnippetCompletionProvider provider = new CSharpSnippetCompletionProvider();

    @Test
    void providesConsoleWriteLineSnippet() {
        List<AutoCompleteItem> items = provider.suggestions("cwl");

        assertEquals(1, items.size());
        AutoCompleteItem item = items.getFirst();
        assertEquals("cwl", item.label());
        assertEquals("Console.WriteLine($0);", item.insertText());
        assertEquals(AutoCompleteItem.Kind.SNIPPET, item.kind());
    }

    @Test
    void filtersSnippetsByPrefix() {
        List<AutoCompleteItem> items = provider.suggestions("fo");

        assertEquals(2, items.size());
        assertTrue(items.stream().allMatch(item -> item.label().startsWith("fo")));
    }
}
