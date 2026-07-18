package dtm.ide.lsp;

import dtm.ide.sdk.DotnetSdkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void roslynLaunchCommandIncludesRazorExtensionWhenAvailable(@TempDir Path dir) {
        Path dotnet = dir.resolve("dotnet");
        Path server = dir.resolve("Microsoft.CodeAnalysis.LanguageServer.dll");
        Path extension = dir.resolve("Microsoft.VisualStudioCode.RazorExtension.dll");
        Path sourceGenerator = dir.resolve("Microsoft.CodeAnalysis.Razor.Compiler.dll");
        Path targets = dir.resolve("Microsoft.NET.Sdk.Razor.DesignTime.targets");
        RoslynLspService service = new RoslynLspService(null, new FakeSdk(dotnet, extension, sourceGenerator, targets));

        List<String> command = service.buildLaunchCommand(server, dir);

        assertTrue(command.contains(dotnet.toAbsolutePath().toString()));
        assertTrue(command.contains(server.toAbsolutePath().toString()));
        assertTrue(command.contains("--razorSourceGenerator"));
        assertTrue(command.contains(sourceGenerator.toString()));
        assertTrue(command.contains("--razorDesignTimePath"));
        assertTrue(command.contains(targets.toString()));
        assertTrue(command.contains("--extension"));
        assertTrue(command.contains(extension.toString()));
        assertFalse(command.contains("--csharpDesignTimePath"));
        assertFalse(command.stream().anyMatch(arg -> arg.toLowerCase().contains("devkit")));
        assertFalse(command.contains("--devKitDependencyPath"));
    }

    @Test
    void razorDocumentsUseAspNetCoreRazorLanguageId() {
        RoslynLspService service = new RoslynLspService(null, new FakeSdk(Path.of("dotnet"),
                Path.of("Microsoft.VisualStudioCode.RazorExtension.dll"),
                Path.of("Microsoft.CodeAnalysis.Razor.Compiler.dll"),
                Path.of("Microsoft.NET.Sdk.Razor.DesignTime.targets")));

        assertEquals("aspnetcorerazor", service.languageIdForUri("file:///C:/App/Pages/Index.cshtml"));
        assertEquals("aspnetcorerazor", service.languageIdForUri("file:///C:/App/Components/App.razor"));
        assertEquals("csharp", service.languageIdForUri("file:///C:/App/Program.cs"));
    }

    @Test
    void roslynDoesNotRequestDocumentOnlyFeaturesForRazorDocuments() {
        RoslynLspService service = new RoslynLspService(null, new FakeSdk(Path.of("dotnet"),
                Path.of("Microsoft.VisualStudioCode.RazorExtension.dll"),
                Path.of("Microsoft.CodeAnalysis.Razor.Compiler.dll"),
                Path.of("Microsoft.NET.Sdk.Razor.DesignTime.targets")));

        assertNull(service.hover(Path.of("Components/App.razor"), "@code { }", 0, 1));
        assertTrue(service.documentHighlights(Path.of("Views/Home/Index.cshtml"), "@DateTime.Now", 0, 2).isEmpty());
        assertTrue(service.documentSymbols(Path.of("Components/App.razor"), "@code { }").isEmpty());
        assertTrue(service.inlayHints(Path.of("Views/Home/Index.cshtml"), "@DateTime.Now", 0, 0).isEmpty());
    }

    private static final class FakeSdk extends DotnetSdkService {
        private final Path dotnet;
        private final Path razorExtension;
        private final Path razorSourceGenerator;
        private final Path designTimeTargets;

        private FakeSdk(Path dotnet, Path razorExtension, Path razorSourceGenerator, Path designTimeTargets) {
            super(null, null);
            this.dotnet = dotnet;
            this.razorExtension = razorExtension;
            this.razorSourceGenerator = razorSourceGenerator;
            this.designTimeTargets = designTimeTargets;
        }

        @Override
        public Optional<Path> getDotnetPath(String sdkVersion) {
            return Optional.of(dotnet);
        }

        @Override
        public Optional<Path> getDotnetPath(Path projectRoot) {
            return Optional.of(dotnet);
        }

        @Override
        public Optional<Path> getRazorExtensionPath() {
            return Optional.of(razorExtension);
        }

        @Override
        public Optional<Path> getRazorSourceGeneratorPath() {
            return Optional.of(razorSourceGenerator);
        }

        @Override
        public Optional<Path> getRazorDesignTimeTargets() {
            return Optional.of(designTimeTargets);
        }

        @Override
        public boolean isRoslynRazorReady() {
            return true;
        }
    }
}
