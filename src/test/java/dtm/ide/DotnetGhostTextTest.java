package dtm.ide;

import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.ghost.GhostTextSuggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DotnetGhostTextTest {

    @Test
    void ghostKeepsTheUsingOfTheChosenItem() {
        TextEdit using = TextEdit.insert(new Position(0, 0), "using System.Text;\n");
        AutoCompleteItem builder = new AutoCompleteItem("StringBuilder", "StringBuilder", null,
                null, null, AutoCompleteItem.Kind.CLASS, List.of(using));

        GhostTextSuggestion suggestion = DotnetIdeAdapter.ghostTextSuggestion(List.of(builder), "StringB");

        assertEquals("uilder", suggestion.text());
        assertEquals(List.of(using), suggestion.additionalTextEdits());
    }
}
