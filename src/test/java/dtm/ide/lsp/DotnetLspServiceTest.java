package dtm.ide.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetLspServiceTest {

    @Test
    void loadTargetUsesTheOnlySolutionAtWorkspaceRoot(@TempDir Path root) throws Exception {
        Path solution = Files.createFile(root.resolve("App.sln"));
        Files.createDirectories(root.resolve("src"));

        assertEquals(solution, AbstractLspService.resolveLoadTarget(root));
    }

    @Test
    void loadTargetKeepsWorkspaceRootForSeveralSolutions(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("App.sln"));
        Files.createFile(root.resolve("Tools.sln"));

        assertEquals(root, AbstractLspService.resolveLoadTarget(root));
    }

    @Test
    void loadTargetKeepsMultiProjectDirectoryWithoutSolution(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/App"));
        Files.createDirectories(root.resolve("src/Library"));
        Files.createFile(root.resolve("src/App/App.csproj"));
        Files.createFile(root.resolve("src/Library/Library.csproj"));

        assertEquals(root, AbstractLspService.resolveLoadTarget(root));
    }

    @Test
    void progressPercentStartsAtZeroWithoutTotals() {
        assertEquals(0, AbstractLspService.progressPercent(0, 0, 0, 0));
    }

    @Test
    void progressPercentUsesCurrentTotal() {
        assertEquals(33, AbstractLspService.progressPercent(3, 2, 3, 0));
        assertEquals(66, AbstractLspService.progressPercent(3, 1, 3, 33));
    }

    @Test
    void progressPercentNeverReportsOneHundredBeforeFinished() {
        assertEquals(99, AbstractLspService.progressPercent(3, 0, 3, 0));
    }

    @Test
    void progressPercentDoesNotMoveBackwards() {
        assertEquals(66, AbstractLspService.progressPercent(3, 2, 3, 66));
    }

    @Test
    void syntheticProgressAdvancesGraduallyUntilCap() {
        assertEquals(5, AbstractLspService.syntheticProgressPercent(0));
        assertEquals(28, AbstractLspService.syntheticProgressPercent(25));
        assertEquals(61, AbstractLspService.syntheticProgressPercent(60));
        assertEquals(90, AbstractLspService.syntheticProgressPercent(90));
        assertEquals(90, AbstractLspService.syntheticProgressPercent(100));
    }

    @Test
    void loadFinishedRecognizesOmnisharpTerminalStates() {
        assertTrue(AbstractLspService.isLoadFinished(3, 0, ""));
        assertTrue(AbstractLspService.isLoadFinished(3, 2, "Ready"));
        assertTrue(AbstractLspService.isLoadFinished(3, 2, "Idle"));
        assertFalse(AbstractLspService.isLoadFinished(3, 2, "BackgroundDiagnosticStatus"));
    }
}
