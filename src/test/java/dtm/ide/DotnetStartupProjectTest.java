package dtm.ide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetStartupProjectTest {

    @Test
    void storesAndMatchesStartupProjectRegardlessOfPathShape(@TempDir Path root) throws Exception {
        Path projectDir = Files.createDirectories(root.resolve("Alpha"));
        Path projectFile = Files.writeString(projectDir.resolve("Alpha.csproj"), "<Project/>");

        Path denormalizedRoot = root.resolve("Alpha").resolve("..");
        Path denormalizedProject = root.resolve(".").resolve("Alpha").resolve("Alpha.csproj");

        DotnetStartupProject.set(root, projectFile);

        assertEquals(projectFile.toAbsolutePath().normalize(), DotnetStartupProject.get(denormalizedRoot));
        assertTrue(DotnetStartupProject.is(root, denormalizedProject));

        DotnetStartupProject.clear(root);
        assertNull(DotnetStartupProject.get(root));
        assertFalse(DotnetStartupProject.is(root, projectFile));
    }

    @Test
    void ignoresStartupProjectWhoseFileNoLongerExists(@TempDir Path root) throws Exception {
        Path projectFile = Files.writeString(root.resolve("Ghost.csproj"), "<Project/>");
        DotnetStartupProject.set(root, projectFile);
        Files.delete(projectFile);

        assertNull(DotnetStartupProject.get(root));

        DotnetStartupProject.clear(root);
    }
}
