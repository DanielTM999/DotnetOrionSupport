package dtm.ide.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetLspServiceTest {

    @Test
    void progressPercentStartsAtZeroWithoutTotals() {
        assertEquals(0, DotnetLspService.progressPercent(0, 0, 0, 0));
    }

    @Test
    void progressPercentUsesCurrentTotal() {
        assertEquals(33, DotnetLspService.progressPercent(3, 2, 3, 0));
        assertEquals(66, DotnetLspService.progressPercent(3, 1, 3, 33));
    }

    @Test
    void progressPercentNeverReportsOneHundredBeforeFinished() {
        assertEquals(99, DotnetLspService.progressPercent(3, 0, 3, 0));
    }

    @Test
    void progressPercentDoesNotMoveBackwards() {
        assertEquals(66, DotnetLspService.progressPercent(3, 2, 3, 66));
    }

    @Test
    void syntheticProgressAdvancesGraduallyUntilCap() {
        assertEquals(5, DotnetLspService.syntheticProgressPercent(0));
        assertEquals(28, DotnetLspService.syntheticProgressPercent(25));
        assertEquals(61, DotnetLspService.syntheticProgressPercent(60));
        assertEquals(90, DotnetLspService.syntheticProgressPercent(90));
        assertEquals(90, DotnetLspService.syntheticProgressPercent(100));
    }

    @Test
    void loadFinishedRecognizesOmnisharpTerminalStates() {
        assertTrue(DotnetLspService.isLoadFinished(3, 0, ""));
        assertTrue(DotnetLspService.isLoadFinished(3, 2, "Ready"));
        assertTrue(DotnetLspService.isLoadFinished(3, 2, "Idle"));
        assertFalse(DotnetLspService.isLoadFinished(3, 2, "BackgroundDiagnosticStatus"));
    }
}
