package dtm.ide.editor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CSharpPostfixCompletionProviderTest {
    private final CSharpPostfixCompletionProvider provider = new CSharpPostfixCompletionProvider();
    @Test void transformsCallAndPreservesPrefix() {
        String source = "    items.Where(x => x.Enabled).fore";
        var item = provider.suggestions(source, source.length()).getFirst();
        assertEquals("foreach", item.label());
        assertTrue(item.insertText().contains("items.Where(x => x.Enabled)"));
        assertEquals(4, item.additionalTextEdits().getFirst().range().start().col());
    }
    @Test void ignoresCommentsLiteralsAndNumericMembers() {
        for (String source : new String[]{"// items.fore", "/* items.fore", "\"items.fore", "123.var", "var s = @\"items.fore", "var a = items.var", "new Item().var"})
            assertTrue(provider.suggestions(source, source.length()).isEmpty(), source);
    }
    @Test void rawAndInterpolatedStringsAreNotSourceCode() {
        var tokens = CSharpLexical.tokens("var a = $\"\"\"hello {items.MapGet(\"/fake\")}\"\"\"; app.MapGet(\"/real\", Run);");
        assertEquals(1, tokens.stream().filter(t -> !t.literal() && t.text().equals("MapGet")).count());
    }
}
