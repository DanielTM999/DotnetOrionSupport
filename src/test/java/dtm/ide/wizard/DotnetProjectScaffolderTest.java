package dtm.ide.wizard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetProjectScaffolderTest {

    private final DotnetProjectScaffolder scaffolder = new DotnetProjectScaffolder();

    @Test
    void scaffoldsBlazorWebApp(@TempDir Path dir) throws Exception {
        Path projectDir = dir.resolve("MyBlazor");
        Path csproj = scaffolder.createProjectOnly(DotnetTemplate.BLAZOR, projectDir, "MyBlazor", "net8.0");

        assertTrue(Files.readString(csproj).contains("Microsoft.NET.Sdk.Web"));
        assertTrue(Files.isRegularFile(projectDir.resolve("Program.cs")));
        assertTrue(Files.isRegularFile(projectDir.resolve("Components/App.razor")));
        assertTrue(Files.isRegularFile(projectDir.resolve("Components/Routes.razor")));
        assertTrue(Files.isRegularFile(projectDir.resolve("Components/_Imports.razor")));
        assertTrue(Files.isRegularFile(projectDir.resolve("Components/Pages/Home.razor")));
        assertTrue(Files.readString(projectDir.resolve("Program.cs")).contains("MapRazorComponents<App>"));
    }

    @Test
    void scaffoldsMvcProjectWithCshtmlViews(@TempDir Path dir) throws Exception {
        Path projectDir = dir.resolve("MyMvc");
        Path csproj = scaffolder.createProjectOnly(DotnetTemplate.WEB_MVC, projectDir, "MyMvc", "net8.0");

        assertTrue(Files.readString(csproj).contains("Microsoft.NET.Sdk.Web"));
        assertTrue(Files.isRegularFile(projectDir.resolve("Program.cs")));
        assertTrue(Files.isRegularFile(projectDir.resolve("Controllers/HomeController.cs")));
        assertTrue(Files.isRegularFile(projectDir.resolve("Views/Home/Index.cshtml")));
        assertTrue(Files.isRegularFile(projectDir.resolve("Views/_ViewImports.cshtml")));
        assertTrue(Files.readString(projectDir.resolve("Program.cs")).contains("MapControllerRoute"));
    }

    @Test
    void scaffoldsBlazorSolutionWithSlnAndGitIgnore(@TempDir Path dir) throws Exception {
        scaffolder.create(DotnetTemplate.BLAZOR, dir, "WebApp", "net8.0", true, true);

        assertTrue(Files.isRegularFile(dir.resolve("WebApp.sln")));
        assertTrue(Files.isRegularFile(dir.resolve("WebApp/WebApp.csproj")));
        assertTrue(Files.isRegularFile(dir.resolve("WebApp/Components/Pages/Home.razor")));
        assertTrue(Files.isRegularFile(dir.resolve(".gitignore")));
    }
}
