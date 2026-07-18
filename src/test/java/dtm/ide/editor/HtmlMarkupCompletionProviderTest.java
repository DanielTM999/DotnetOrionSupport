package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlMarkupCompletionProviderTest {

    private final HtmlMarkupCompletionProvider provider = new HtmlMarkupCompletionProvider();

    @Test
    void suggestsHtmlElementsAfterOpeningAngle() {
        String text = "<p>ok</p>\n<di";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertTrue(hasLabel(items, "div"), () -> "expected div in " + items);
        assertTrue(items.stream().allMatch(item -> item.label().startsWith("di")),
                () -> "only di* elements expected in " + items);
    }

    @Test
    void suggestsAllElementsRightAfterAngle() {
        String text = "<";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertTrue(hasLabel(items, "div"));
        assertTrue(hasLabel(items, "span"));
        assertTrue(hasLabel(items, "section"));
    }

    @Test
    void suggestsAttributesInsideOpenTag() {
        String text = "<a hr";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertTrue(hasLabel(items, "href"), () -> "expected href in " + items);
        assertEquals("href=\"\"", insertFor(items, "href"));
    }

    @Test
    void booleanAttributesInsertBareName() {
        String text = "<input dis";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertEquals("disabled", insertFor(items, "disabled"));
    }

    @Test
    void noCompletionInsideCsharpBlock() {
        String text = "@{\n    var x = \n}";
        int caret = text.indexOf("var x = ") + "var x = ".length();

        assertEquals(HtmlMarkupCompletionProvider.Kind.NONE, provider.analyze(text, caret).kind());
        assertTrue(provider.suggestions(text, caret).isEmpty());
    }

    @Test
    void explicitCompletionInContentPreservesCurrentWordWhenAccepted() {
        String text = "<p>teste";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length(), true);

        assertEquals("</p>", items.get(0).label());
        assertEquals("teste</p>", items.get(0).insertText());
    }

    @Test
    void typingCompletionInContentStaysSilent() {
        String text = "<p>teste";
        assertTrue(provider.suggestions(text, text.length(), false).isEmpty());
    }

    @Test
    void contentCloseWithoutWordPrefixInsertsOnlyCloseTag() {
        String text = "<p>teste ";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length(), true);

        assertEquals("</p>", items.get(0).label());
        assertEquals("</p>", items.get(0).insertText());
    }

    @Test
    void noContentCompletionWithoutOpenTag() {
        String text = "plain text here";
        assertTrue(provider.suggestions(text, text.length(), true).isEmpty());
    }

    @Test
    void noCompletionInsideAttributeValue() {
        String text = "<a href=\"http";
        assertEquals(HtmlMarkupCompletionProvider.Kind.NONE, provider.analyze(text, text.length()).kind());
    }

    @Test
    void noCompletionInsideComment() {
        String text = "<!-- <di";
        assertTrue(provider.suggestions(text, text.length()).isEmpty());
    }

    @Test
    void ignoresRazorAttributesStartingWithAt() {
        String text = "<button @oncl";
        assertEquals(HtmlMarkupCompletionProvider.Kind.NONE, provider.analyze(text, text.length()).kind());
    }

    @Test
    void closeTagIsFirstOptionWhenTagIsOpen() {
        String text = "<p>Hello<";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertFalse(items.isEmpty());
        assertEquals("/p", items.get(0).label());
        assertEquals("/p>", items.get(0).insertText());
    }

    @Test
    void closeTagAfterAngleWorksAcrossBodyLineBreaks() {
        String text = "<body>\n    teste\n<";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length(), true);

        assertFalse(items.isEmpty());
        assertEquals("/body", items.get(0).label());
        assertEquals("/body>", items.get(0).insertText());
    }

    @Test
    void closingSlashWorksAcrossBodyLineBreaks() {
        String text = "<body>\n    teste\n</";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length(), true);

        assertFalse(items.isEmpty());
        assertEquals("body", items.get(0).label());
        assertEquals("body>", items.get(0).insertText());
    }

    @Test
    void nearestUnclosedTagIsFirst() {
        String text = "<div><span><";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertEquals("/span", items.get(0).label());
        assertEquals("/div", items.get(1).label());
    }

    @Test
    void closingSlashCompletesOpenTag() {
        String text = "<section></";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertEquals(HtmlMarkupCompletionProvider.Kind.CLOSE_TAG,
                provider.analyze(text, text.length()).kind());
        assertEquals("section", items.get(0).label());
        assertEquals("section>", items.get(0).insertText());
    }

    @Test
    void closingSlashFiltersByPrefix() {
        String text = "<article><aside></a";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertTrue(items.stream().allMatch(item -> item.label().startsWith("a")));
        assertEquals("aside", items.get(0).label());
    }

    @Test
    void closedTagsDoNotSuggestClose() {
        String text = "<p>done</p>\n<";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertFalse(items.stream().anyMatch(item -> item.label().startsWith("/")));
    }

    @Test
    void voidElementsDoNotStayOpen() {
        String text = "<div><br><img src=\"x\"><";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertEquals("/div", items.get(0).label());
        assertFalse(items.stream().anyMatch(item -> item.label().equals("/br")));
        assertFalse(items.stream().anyMatch(item -> item.label().equals("/img")));
    }

    @Test
    void selfClosedTagsDoNotStayOpen() {
        String text = "<div><input /><";
        List<AutoCompleteItem> items = provider.suggestions(text, text.length());

        assertEquals("/div", items.get(0).label());
        assertFalse(items.stream().anyMatch(item -> item.label().equals("/input")));
    }

    private static boolean hasLabel(List<AutoCompleteItem> items, String label) {
        return items.stream().anyMatch(item -> label.equals(item.label()));
    }

    private static String insertFor(List<AutoCompleteItem> items, String label) {
        return items.stream()
                .filter(item -> label.equals(item.label()))
                .map(AutoCompleteItem::insertText)
                .findFirst()
                .orElse(null);
    }
}
