package dtm.ide.ui;

import dtm.ide.project.WorkspaceTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTargetSelectionTest {
    @TempDir Path dir;
    @Test void preferredFrameworkSurvivesAsyncLoadingAndSelectionChangesArePublishedWithoutBuilding() throws Exception {
        Path project = dir.resolve("Demo.csproj");
        Files.writeString(project, "<Project Sdk='Microsoft.NET.Sdk'><PropertyGroup><TargetFrameworks>net8.0;net10.0</TargetFrameworks></PropertyGroup></Project>");
        var latest = new AtomicReference<WorkspaceTarget>();
        var loaded = new CountDownLatch(1);
        var reference = new AtomicReference<DotnetBuildToolsPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = new DotnetBuildToolsPanel(() -> List.of(project), request -> fail("Selection must not execute a build"));
            panel.setSelection(new WorkspaceTarget(project, "Release", "net10.0"));
            panel.setSelectionChangedListener(value -> { latest.set(value); loaded.countDown(); });
            reference.set(panel); panel.refresh();
        });
        try {
            assertTrue(loaded.await(5, TimeUnit.SECONDS));
            assertEquals(new WorkspaceTarget(project, "Release", "net10.0"), latest.get());
            SwingUtilities.invokeAndWait(() -> {
                var form = (JPanel) ((java.awt.BorderLayout) reference.get().getLayout()).getLayoutComponent(java.awt.BorderLayout.NORTH);
                for (var component : form.getComponents()) if (component instanceof JComboBox<?> combo
                        && combo.getItemCount() == 2 && "Debug".equals(combo.getItemAt(0))) combo.setSelectedItem("Debug");
            });
            assertEquals(new WorkspaceTarget(project, "Debug", "net10.0"), latest.get());
        } finally { SwingUtilities.invokeAndWait(() -> reference.get().close()); }
    }
}
