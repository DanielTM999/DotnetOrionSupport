package dtm.ide.build;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuildRequestTest {
    @Test
    void restoreAndMsbuildUsePropertiesInsteadOfInvalidCliSwitches() {
        Path target = Path.of("App.csproj");
        assertEquals(List.of("restore", "-property:TargetFramework=net8.0"),
                new BuildRequest(target, "restore", "Release", "net8.0", "").arguments());
        assertEquals(List.of("msbuild", "-target:Custom.Build", "-property:Configuration=Release", "-property:TargetFramework=net8.0"),
                new BuildRequest(target, "msbuild", "Release", "net8.0", "Custom.Build").arguments());
        assertThrows(IllegalArgumentException.class,
                () -> new BuildRequest(target, "msbuild", "Debug", "", "Build;Other"));
    }

    @Test
    void rebuildKeepsFrameworkAsSeparateArgument() {
        assertEquals(List.of("build", "--no-incremental", "--configuration", "Debug", "--framework", "net8.0"),
                new BuildRequest(Path.of("App.csproj"), "rebuild", null, "net8.0", null).arguments());
    }
}
