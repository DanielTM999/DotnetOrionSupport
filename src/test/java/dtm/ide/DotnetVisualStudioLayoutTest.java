package dtm.ide;

import dtm.ide.api.project.tree.ProjectTreeNode;
import dtm.ide.settings.TreeLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetVisualStudioLayoutTest {

    @Test
    void rootLevelProjectDoesNotDuplicateFilesAtSolutionLevel(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("MyApp.csproj"), "<Project Sdk=\"Microsoft.NET.Sdk.Web\"></Project>");
        Files.writeString(dir.resolve("Program.cs"), "class P {}");
        Files.writeString(dir.resolve("appsettings.json"), "{}");
        Files.writeString(dir.resolve(".gitignore"), "bin/");
        Files.writeString(dir.resolve("MyApp.sln"), """
                Microsoft Visual Studio Solution File, Format Version 12.00
                Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "MyApp", "MyApp.csproj", "{11111111-1111-1111-1111-111111111111}"
                EndProject
                Global
                EndGlobal
                """);

        ProjectTreeNode fsTree = DotnetProjectConventions.buildFilesystemTree(dir);
        ProjectTreeNode layout = DotnetProjectConventions.applyTreeLayout(fsTree, TreeLayout.VISUAL_STUDIO);
        assertNotNull(layout);

        List<ProjectTreeNode> topLevel = layout.getChildren();
        long programCount = topLevel.stream()
                .filter(node -> node.getPath() != null && node.getPath().getFileName() != null
                        && "Program.cs".equals(node.getPath().getFileName().toString()))
                .count();
        assertEquals(0, programCount, "Program.cs não deve aparecer duplicado no nível da solução");

        List<String> topLabels = topLevel.stream().map(ProjectTreeNode::getLabel).toList();
        assertEquals(1, topLevel.size(), "esperado apenas o nó do projeto no topo, veio: " + topLabels);

        ProjectTreeNode projectNode = topLevel.get(0);
        assertTrue(childLabels(projectNode).contains("Program.cs"),
                "Program.cs deve aparecer dentro do projeto");
    }

    @Test
    void foldersComeBeforeFilesInsideProjectFolders(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("MyWebApp.csproj"), "<Project Sdk=\"Microsoft.NET.Sdk.Web\"></Project>");
        Path pages = dir.resolve("Pages");
        Files.createDirectories(pages.resolve("Shared"));
        Files.writeString(pages.resolve("Index.cshtml"), "");
        Files.writeString(pages.resolve("Index.cshtml.cs"), "");
        Files.writeString(pages.resolve("_ViewImports.cshtml"), "");
        Files.writeString(pages.resolve("_ViewStart.cshtml"), "");

        ProjectTreeNode fsTree = DotnetProjectConventions.buildFilesystemTree(dir);
        ProjectTreeNode layout = DotnetProjectConventions.applyTreeLayout(fsTree, TreeLayout.VISUAL_STUDIO);
        assertNotNull(layout);

        ProjectTreeNode pagesNode = layout.getChildren().stream()
                .filter(node -> "Pages".equals(nodeName(node)))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of(
                        "Shared",
                        "_ViewImports.cshtml",
                        "_ViewStart.cshtml",
                        "Index.cshtml",
                        "Index.cshtml.cs"),
                childLabels(pagesNode));
    }

    private static List<String> childLabels(ProjectTreeNode node) {
        return node.getChildren().stream()
                .map(DotnetVisualStudioLayoutTest::nodeName)
                .toList();
    }

    private static String nodeName(ProjectTreeNode node) {
        return node.getPath() != null && node.getPath().getFileName() != null
                ? node.getPath().getFileName().toString()
                : node.getLabel();
    }
}
