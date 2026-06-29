package dtm.ide;

import dtm.ide.api.project.tree.ProjectTreeNode;
import dtm.ide.settings.TreeLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetProjectReferenceTest {

    @Test
    void readsAssemblyPackageAndProjectReferences(@TempDir Path directory) throws Exception {
        Path library = directory.resolve("Library").resolve("Library.csproj");
        Files.createDirectories(library.getParent());
        Files.writeString(library, "<Project />");
        Path app = directory.resolve("App.csproj");
        Files.writeString(app, """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <Reference Include="Example.Api, Version=2.4.0.0">
                      <HintPath>lib\\Example.Api.dll</HintPath>
                    </Reference>
                    <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
                    <PackageReference Include="Serilog">
                      <Version>4.2.0</Version>
                    </PackageReference>
                    <ProjectReference Include="Library\\Library.csproj" />
                  </ItemGroup>
                </Project>
                """);

        List<DotnetProjectReference> references = DotnetProjectReference.read(app);

        assertEquals(List.of(
                        "Example.Api-2.4.0.0",
                        "Newtonsoft.Json-13.0.3",
                        "Serilog-4.2.0",
                        "Library"),
                references.stream().map(DotnetProjectReference::label).toList());
        assertEquals(library.toAbsolutePath().normalize(), references.get(3).target());
    }

    @Test
    void createsAReferencesNodeWhoseFolderDoesNotExist(@TempDir Path directory) throws Exception {
        Path project = directory.resolve("App.csproj");
        Files.writeString(project, """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <PackageReference Include="Dapper" Version="2.1.66" />
                  </ItemGroup>
                </Project>
                """);

        ProjectTreeNode filesystemTree = DotnetProjectConventions.buildFilesystemTree(directory);
        ProjectTreeNode tree = DotnetProjectConventions.applyTreeLayout(filesystemTree, TreeLayout.VISUAL_STUDIO);
        ProjectTreeNode references = tree.getChildren().stream()
                .filter(node -> "References".equals(node.getLabel()))
                .findFirst()
                .orElseThrow();

        assertFalse(Files.exists(references.getPath()));
        assertTrue(references.isVirtual());
        assertEquals(List.of("Dapper-2.1.66"),
                references.getChildren().stream().map(ProjectTreeNode::getLabel).toList());
        assertTrue(references.getChildren().getFirst().isVirtual());
        assertNotNull(references.getIcon());
        assertNotNull(references.getChildren().getFirst().getIcon());
    }

    @Test
    void addsReferencesToEveryProjectInASolution(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("Workspace.sln"), "");
        createProject(directory.resolve("App"), "App.csproj", "Dapper", "2.1.66");
        createProject(directory.resolve("Library"), "Library.csproj", "Serilog", "4.2.0");

        ProjectTreeNode filesystemTree = DotnetProjectConventions.buildFilesystemTree(directory);
        ProjectTreeNode tree = DotnetProjectConventions.applyTreeLayout(filesystemTree, TreeLayout.VISUAL_STUDIO);

        assertEquals(2, tree.getChildren().size());
        for (ProjectTreeNode project : tree.getChildren()) {
            ProjectTreeNode references = project.getChildren().stream()
                    .filter(node -> "References".equals(node.getLabel()))
                    .findFirst()
                    .orElseThrow();
            assertFalse(Files.exists(references.getPath()));
            assertTrue(references.isVirtual());
            assertEquals(1, references.getChildren().size());
            assertTrue(references.getChildren().getFirst().isVirtual());
            assertNotNull(project.getIcon());
        }
    }

    private static void createProject(Path directory, String fileName, String packageId, String version)
            throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <PackageReference Include="%s" Version="%s" />
                  </ItemGroup>
                </Project>
                """.formatted(packageId, version));
    }
}
