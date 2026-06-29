package dtm.ide.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DotnetTestExplorerPanelTest {

    @Test
    void extractsVstestDebugProcessId() {
        assertEquals(12345L, DotnetTestExplorerPanel.debugProcessId(
                "Process Id: 12345, Name: testhost"));
        assertEquals(81L, DotnetTestExplorerPanel.debugProcessId("PID = 81"));
        assertNull(DotnetTestExplorerPanel.debugProcessId("Test run started"));
    }
}
