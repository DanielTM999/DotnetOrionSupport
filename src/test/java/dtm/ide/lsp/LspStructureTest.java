package dtm.ide.lsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LspStructureTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectionRangesFollowParentsWithoutDuplicatingIdenticalRanges() throws Exception {
        var result = mapper.readTree("""
                [{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":6}},
                "parent":{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":6}},
                "parent":{"range":{"start":{"line":0,"character":0},"end":{"line":5,"character":0}}}}}]
                """);
        var ranges = AbstractLspService.parseSelectionRanges(result);
        assertEquals(2, ranges.size());
        assertEquals(1, ranges.getFirst().start().line());
        assertEquals(5, ranges.getLast().end().line());
        assertTrue(AbstractLspService.parseSelectionRanges(mapper.nullNode()).isEmpty());
    }

    @Test
    void typeHierarchyPreservesServerDataAndIgnoresInvalidItems() {
        var result = mapper.createArrayNode();
        var item = result.addObject();
        item.put("name", "Customer");
        item.put("kind", 5);
        item.put("uri", Path.of("Customer.cs").toAbsolutePath().toUri().toString());
        var range = item.putObject("range");
        range.putObject("start").put("line", 0).put("character", 0);
        range.putObject("end").put("line", 4).put("character", 1);
        item.set("selectionRange", range.deepCopy());
        item.putObject("data").put("symbolId", "42");
        result.addObject().put("name", "invalid");
        var parsed = AbstractLspService.parseTypeHierarchy(result);
        assertEquals(1, parsed.size());
        assertEquals("Customer", parsed.getFirst().name());
        assertEquals(item, parsed.getFirst().data());
        assertNotSame(item, parsed.getFirst().data());
    }
}
