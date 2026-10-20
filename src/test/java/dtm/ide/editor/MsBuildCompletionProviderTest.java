package dtm.ide.editor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MsBuildCompletionProviderTest {
    @Test void suggestionsRespectParentAndIgnoreComments() {
        var provider = new MsBuildCompletionProvider();
        String source = "<Project><PropertyGroup><Tar";
        var suggestions = provider.suggestions(source, source.length());
        assertTrue(suggestions.stream().anyMatch(item -> item.label().equals("TargetFramework")));
        assertFalse(suggestions.stream().anyMatch(item -> item.label().equals("PackageReference")));
        source = "<Project><!-- <Tar";
        assertTrue(provider.suggestions(source, source.length()).isEmpty());
        source = "<Project><ItemGroup><PackageR";
        assertTrue(provider.suggestions(source, source.length()).getFirst().insertText().contains("Version="));
    }
}
