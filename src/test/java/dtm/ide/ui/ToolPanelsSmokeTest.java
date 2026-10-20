package dtm.ide.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ToolPanelsSmokeTest {
    @Test void emptyPanelsRenderAndTestActionsRemainVisibleInANarrowDock() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tests = new DotnetTestExplorerPanel(() -> null, () -> null, () -> "Debug");
            var aspnet = new AspNetExplorerPanel(() -> null, Map::of, (file, line) -> {}, snapshot -> {});
            var build = new DotnetBuildToolsPanel(List::of, request -> {});
            try {
                render(tests, "tests"); render(aspnet, "aspnet"); render(build, "build");
                JPanel toolbar = (JPanel) ((BorderLayout) tests.getLayout()).getLayoutComponent(BorderLayout.NORTH);
                for (Component component : toolbar.getComponents()) if (component.isVisible()) {
                    assertTrue(component.getY() + component.getHeight() <= toolbar.getHeight(), "Toolbar action clipped: " + component);
                }
                tests.refresh(); // Missing toolchain must produce a state message, not an exception.
                tests.resetProject();
            } finally { aspnet.close(); build.close(); }
        });
    }
    private static void render(JPanel panel, String name) {
        panel.setSize(560, 560);
        layout(panel);
        var image = new BufferedImage(560, 560, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { panel.paint(graphics); } finally { graphics.dispose(); }
        try {
            Path folder = Files.createDirectories(Path.of("target/ui-smoke"));
            javax.imageio.ImageIO.write(image, "png", folder.resolve(name + ".png").toFile());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void layout(Container component) {
        component.doLayout();
        for (Component child : component.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
