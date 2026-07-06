package dtm.ide.wizard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetProjectScaffolderTest {

    private final DotnetProjectScaffolder scaffolder = new DotnetProjectScaffolder();

//    @Test
//    void scaffoldsRazorPagesProject(@TempDir Path dir) throws Exception {
//        Path projectDir = dir.resolve("MyPages");
//        Path csproj = scaffolder.createProjectOnly(DotnetTemplate.RAZOR_PAGES, projectDir, "MyPages", "net8.0");
//
//        assertTrue(Files.readString(csproj).contains("Microsoft.NET.Sdk.Web"));
//        assertTrue(Files.isRegularFile(projectDir.resolve("Pages/Index.cshtml")));
//        assertTrue(Files.isRegularFile(projectDir.resolve("Pages/Index.cshtml.cs")));
//        assertTrue(Files.isRegularFile(projectDir.resolve("Pages/_ViewImports.cshtml")));
//        assertTrue(Files.isRegularFile(projectDir.resolve("Pages/Shared/_Layout.cshtml")));
//        assertTrue(Files.readString(projectDir.resolve("Program.cs")).contains("MapRazorPages"));
//    }
//
//    @Test
//    void scaffoldsRazorClassLibraryProject(@TempDir Path dir) throws Exception {
//        Path projectDir = dir.resolve("MyComponents");
//        Path csproj = scaffolder.createProjectOnly(DotnetTemplate.RAZOR_CLASS_LIBRARY, projectDir, "MyComponents", "net8.0");
//
//        String csprojText = Files.readString(csproj);
//        assertTrue(csprojText.contains("Microsoft.NET.Sdk.Razor"));
//        assertTrue(csprojText.contains("Microsoft.AspNetCore.Components.Web"));
//        assertTrue(Files.isRegularFile(projectDir.resolve("Component1.razor")));
//        assertTrue(Files.isRegularFile(projectDir.resolve("Component1.razor.css")));
//        assertTrue(Files.isRegularFile(projectDir.resolve("_Imports.razor")));
//    }
//
//    @Test
//    void scaffoldsRazorPagesSolutionWithSln(@TempDir Path dir) throws Exception {
//        scaffolder.create(DotnetTemplate.RAZOR_PAGES, dir, "WebApp", "net8.0", true, true);
//
//        assertTrue(Files.isRegularFile(dir.resolve("WebApp.sln")));
//        assertTrue(Files.isRegularFile(dir.resolve("WebApp/WebApp.csproj")));
//        assertTrue(Files.isRegularFile(dir.resolve("WebApp/Pages/Index.cshtml")));
//        assertTrue(Files.isRegularFile(dir.resolve(".gitignore")));
//    }
}
