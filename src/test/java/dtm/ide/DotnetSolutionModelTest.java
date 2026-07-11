package dtm.ide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetSolutionModelTest {

    @Test
    void parsesSlnWithSolutionFolderAndNestedProject(@TempDir Path dir) throws Exception {
        writeProject(dir.resolve("src/App/App.csproj"));
        writeProject(dir.resolve("NetCore/Core/Core.csproj"));
        Files.writeString(dir.resolve("README.md"), "# readme");

        Path sln = dir.resolve("MySolution.sln");
        Files.writeString(sln, """
                Microsoft Visual Studio Solution File, Format Version 12.00
                Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "App", "src\\App\\App.csproj", "{11111111-1111-1111-1111-111111111111}"
                EndProject
                Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "Core", "NetCore\\Core\\Core.csproj", "{22222222-2222-2222-2222-222222222222}"
                EndProject
                Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "NetCore", "NetCore", "{33333333-3333-3333-3333-333333333333}"
                \tProjectSection(SolutionItems) = preProject
                \t\tREADME.md = README.md
                \tEndProjectSection
                EndProject
                Global
                \tGlobalSection(NestedProjects) = preSolution
                \t\t{22222222-2222-2222-2222-222222222222} = {33333333-3333-3333-3333-333333333333}
                \tEndGlobalSection
                EndGlobal
                """);

        DotnetSolutionModel model = DotnetSolutionModel.parse(sln);
        assertNotNull(model);
        assertTrue(model.hasProjects());

        List<DotnetSolutionModel.Entry> roots = model.roots();
        assertEquals(2, roots.size());

        DotnetSolutionModel.Entry app = roots.get(0);
        assertEquals("App", app.name);
        assertTrue(!app.folder);

        DotnetSolutionModel.Entry netCore = roots.get(1);
        assertEquals("NetCore", netCore.name);
        assertTrue(netCore.folder);
        assertEquals(1, netCore.children.size());
        assertEquals("Core", netCore.children.get(0).name);
        assertEquals(1, netCore.files.size());
        assertEquals(dir.resolve("README.md").normalize(), netCore.files.get(0));
    }

    @Test
    void parsesSlnxWithFolderProjectAndFile(@TempDir Path dir) throws Exception {
        writeProject(dir.resolve("src/App/App.csproj"));
        writeProject(dir.resolve("NetCore/Core/Core.csproj"));
        Files.writeString(dir.resolve("README.md"), "# readme");

        Path slnx = dir.resolve("MySolution.slnx");
        Files.writeString(slnx, """
                <Solution>
                  <Folder Name="/NetCore/">
                    <Project Path="NetCore/Core/Core.csproj" />
                    <File Path="README.md" />
                  </Folder>
                  <Project Path="src/App/App.csproj" />
                </Solution>
                """);

        DotnetSolutionModel model = DotnetSolutionModel.parse(slnx);
        assertNotNull(model);
        assertTrue(model.hasProjects());

        List<DotnetSolutionModel.Entry> roots = model.roots();
        assertEquals(2, roots.size());

        DotnetSolutionModel.Entry netCore = roots.get(0);
        assertTrue(netCore.folder);
        assertEquals("NetCore", netCore.name);
        assertEquals(1, netCore.children.size());
        assertEquals(dir.resolve("NetCore/Core/Core.csproj").normalize(), netCore.children.get(0).projectFile);
        assertEquals(1, netCore.files.size());

        DotnetSolutionModel.Entry app = roots.get(1);
        assertTrue(!app.folder);
        assertEquals(dir.resolve("src/App/App.csproj").normalize(), app.projectFile);
    }

    private static void writeProject(Path csproj) throws Exception {
        Files.createDirectories(csproj.getParent());
        Files.writeString(csproj, "<Project Sdk=\"Microsoft.NET.Sdk\"></Project>");
    }
}
