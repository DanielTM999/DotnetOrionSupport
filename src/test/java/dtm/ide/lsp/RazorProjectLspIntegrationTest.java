package dtm.ide.lsp;

import dtm.ide.api.extension.Resource;
import dtm.ide.sdk.DotnetSdkService;
import dtm.request_actions.http.download.core.DownloadObserver;
import dtm.request_actions.http.download.core.client.DownloadObserverClient;
import dtm.request_actions.http.download.core.client.DownloadObserverStreamClient;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RazorProjectLspIntegrationTest {

    @Test
    void realRazorProjectProvidesAutocompleteAndDiagnosticsForCshtml(@TempDir(cleanup = CleanupMode.ALWAYS) Path resourceRoot) throws Exception {
        String projectPath = System.getProperty("dotnet.razor.project", "");
        // Match the canonical paths emitted by MSBuild, Coverlet and Roslyn on Windows.
        Path project = (projectPath.isBlank() ? createProject(resourceRoot.resolve("Razor project")) : Path.of(projectPath)).toRealPath();
        String cachedResources = System.getProperty("dotnet.resources", "");
        Path toolchain = cachedResources.isBlank() ? resourceRoot : Path.of(cachedResources);
        Path cshtml = project.resolve("Pages").resolve("Index.cshtml");
        DotnetSdkService sdk = new DotnetSdkService(new TestResource(toolchain), new HttpDownloadObserver());

        assertTrue(Files.isDirectory(project), "Projeto Razor de teste nao encontrado: " + project);
        assertTrue(Files.isRegularFile(cshtml), "Arquivo Index.cshtml nao encontrado: " + cshtml);

        Path dotnet = sdk.ensureDotnet(project, DotnetSdkService.DownloadProgressListener.NOOP);
        Process build = new ProcessBuilder(dotnet.toString(), "build", dtm.ide.run.TargetFramework.findPrimaryProjectFile(project).toString(), "--nologo")
                .directory(project.toFile()).redirectErrorStream(true).start();
        Thread drain = Thread.ofVirtual().start(() -> { try { build.getInputStream().transferTo(System.out); } catch (Exception ignored) {} });
        assertTrue(build.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "Razor project build timed out");
        drain.join(3000);
        org.junit.jupiter.api.Assertions.assertEquals(0, build.exitValue(), "Razor fixture must build");

        sdk.ensureRoslynRuntime(DotnetSdkService.DownloadProgressListener.NOOP);
        sdk.ensureRoslyn(DotnetSdkService.DownloadProgressListener.NOOP);
        sdk.ensureRoslynRazor(DotnetSdkService.DownloadProgressListener.NOOP);

        RoslynLspService service = new RoslynLspService(new TestResource(toolchain), sdk);
        try {
            service.bindProject(project);
            service.start();
            assertTrue(service.awaitReady(90_000), "Roslyn LS nao ficou pronto: " + service.getLastError());

            String original = Files.readString(cshtml);
            service.diagnose(cshtml, original);
            Thread.sleep(6_000);
            String completionText = original + "\n@{\n    var now = DateTime.N\n}\n";
            TextPosition completionPosition = positionAfter(completionText, "DateTime.N");
            List<AutoCompleteItem> completions = waitForCompletions(service, cshtml, completionText, completionPosition, "N");
            assertFalse(completions.isEmpty(), "Esperava autocomplete Razor/C# em Index.cshtml");
            assertTrue(hasCompletion(completions, "Now"), "Esperava DateTime.Now entre os autocompletes Razor/C#");

            String brokenText = original + "\n@{\n    var broken = DateTime.\n}\n";
            Collection<Diagnostic> diagnostics = waitForDiagnostics(service, cshtml, brokenText);
            assertTrue(diagnostics.stream().anyMatch(RazorProjectLspIntegrationTest::isError), "Esperava diagnostico de erro C# para bloco Razor invalido");

            String importText = original + "\n@{\n    var rx = new Regex\n}\n";
            TextPosition importPosition = positionAfter(importText, "new Regex");
            List<AutoCompleteItem> importCompletions = waitForCompletionMatch(service, cshtml, importText, importPosition, "Regex", RazorProjectLspIntegrationTest::isRegexImportCompletion);
            assertTrue(importCompletions.stream().anyMatch(RazorProjectLspIntegrationTest::isRegexImportCompletion), "Esperava completion de Regex com auto-using de System.Text.RegularExpressions");

            Path component = project.resolve("Components/Counter.razor");
            String componentText = Files.readString(component) + "\n@code { private void Update() { var now = DateTime.N; } }\n";
            var componentPosition = positionAfter(componentText, "DateTime.N");
            var componentCompletions = waitForCompletions(service, component, componentText, componentPosition, "N");
            assertTrue(hasCompletion(componentCompletions, "Now"), "Expected semantic completion in a Blazor component");
        } finally {
            service.stop();
        }
    }

    private static Path createProject(Path project) throws Exception {
        Files.createDirectories(project.resolve("Pages"));
        Files.createDirectories(project.resolve("Components"));
        Files.writeString(project.resolve("Razor.csproj"), "<Project Sdk='Microsoft.NET.Sdk.Web'><PropertyGroup><TargetFramework>net8.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings></PropertyGroup></Project>");
        Files.writeString(project.resolve("Program.cs"), "var builder = WebApplication.CreateBuilder(args); builder.Services.AddRazorPages(); var app = builder.Build(); app.MapRazorPages(); app.Run();");
        Files.writeString(project.resolve("Pages/Index.cshtml"), "@page\n@using System\n<h1>Hello</h1>\n");
        Files.writeString(project.resolve("Components/Counter.razor"), "@using System\n<h1>Counter</h1>\n");
        return project;
    }

    private static boolean isRegexImportCompletion(AutoCompleteItem item) {
        if (item == null || !"Regex".equals(item.label()) || !item.hasAdditionalTextEdits()) {
            return false;
        }
        return item.additionalTextEdits().stream()
                .anyMatch(edit -> edit.newText() != null
                        && edit.newText().contains("System.Text.RegularExpressions"));
    }

    private static List<AutoCompleteItem> waitForCompletions(RoslynLspService service, Path file, String text,
                                                             TextPosition position, String prefix) throws Exception {
        return waitForCompletionMatch(service, file, text, position, prefix,
                item -> "Now".equals(item.label()));
    }

    private static List<AutoCompleteItem> waitForCompletionMatch(RoslynLspService service, Path file, String text,
                                                                 TextPosition position, String prefix,
                                                                 Predicate<AutoCompleteItem> match)
            throws Exception {
        List<AutoCompleteItem> last = List.of();
        for (int i = 0; i < 60; i++) {
            last = service.completeForEditor(file, text, position.line(), position.col(), prefix);
            if ("Regex".equals(prefix)) {
                List<AutoCompleteItem> resolved = new java.util.ArrayList<>();
                for (var item : last) resolved.add("Regex".equals(item.label())
                        ? service.resolveCompletion(item).get(10, java.util.concurrent.TimeUnit.SECONDS) : item);
                last = resolved;
            }
            if (last.stream().anyMatch(match)) {
                return last;
            }
            Thread.sleep(1_000);
        }
        return last;
    }

    private static Collection<Diagnostic> waitForDiagnostics(RoslynLspService service, Path file, String text)
            throws Exception {
        Collection<Diagnostic> last = List.of();
        for (int i = 0; i < 60; i++) {
            last = service.diagnose(file, text);
            if (last.stream().anyMatch(RazorProjectLspIntegrationTest::isError)) {
                return last;
            }
            Thread.sleep(1_000);
        }
        return last;
    }

    private static boolean hasCompletion(List<AutoCompleteItem> items, String label) {
        return items.stream().anyMatch(item -> label.equals(item.label()));
    }

    private static boolean isError(Diagnostic diagnostic) {
        return diagnostic != null
                && diagnostic.severity() == DiagnosticSeverity.ERROR
                && diagnostic.message() != null
                && !diagnostic.message().isBlank();
    }

    private static TextPosition positionAfter(String text, String marker) {
        int offset = text.indexOf(marker);
        if (offset < 0) {
            throw new IllegalArgumentException("Marcador nao encontrado: " + marker);
        }
        offset += marker.length();
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new TextPosition(line, offset - lineStart);
    }

    private record TextPosition(int line, int col) {
    }

    private static final class HttpDownloadObserver implements DownloadObserver {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        @Override
        public void newDownloadGet(String uri, DownloadObserverClient observer) {
            newDownloadGet(URI.create(uri), observer);
        }

        @Override
        public void newDownloadGet(URI uri, DownloadObserverClient observer) {
            newDownloadGet(uri, Map.of(), observer);
        }

        @Override
        public void newDownloadGet(String uri, Map<String, String> headers, DownloadObserverClient observer) {
            newDownloadGet(URI.create(uri), headers, observer);
        }

        @Override
        public void newDownloadGet(URI uri, Map<String, String> headers, DownloadObserverClient observer) {
            if (observer != null) {
                observer.onError(new UnsupportedOperationException("Download em memoria nao usado neste teste."));
            }
        }

        @Override
        public void newDownloadGetStream(String uri, DownloadObserverStreamClient observer) {
            newDownloadGetStream(URI.create(uri), observer);
        }

        @Override
        public void newDownloadGetStream(URI uri, DownloadObserverStreamClient observer) {
            newDownloadGetStream(uri, Map.of(), observer);
        }

        @Override
        public void newDownloadGetStream(String uri, Map<String, String> headers, DownloadObserverStreamClient observer) {
            newDownloadGetStream(URI.create(uri), headers, observer);
        }

        @Override
        public void newDownloadGetStream(URI uri, Map<String, String> headers, DownloadObserverStreamClient observer) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
                headers.forEach(request::header);
                HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + " ao baixar " + uri);
                }
                byte[] body = response.body();
                observer.onStart(body.length, response.headers().map());
                observer.onProgress(body, body.length, body.length, response.headers().map());
                observer.onComplete(response.headers().map());
            } catch (Exception e) {
                observer.onError(e);
            }
        }
    }

    private record TestResource(Path root) implements Resource {
        @Override
        public Path getResourcePath() {
            return root;
        }

        @Override
        public Path getResourcePath(String s) {
            return s == null ? root : root.resolve(s);
        }

        @Override
        public Path getResourcePath(Path path) {
            return path == null ? root : root.resolve(path);
        }

        @Override
        public URL getResource(String s) {
            return null;
        }

        @Override
        public List<URL> getResources(Collection<String> collection) {
            return List.of();
        }

        @Override
        public InputStream getResourceAsStream(String s) {
            return null;
        }

        @Override
        public List<InputStream> getResourcesAsStreams(Collection<String> collection) {
            return List.of();
        }

        @Override
        public Path getSharedResourcePath() {
            return root;
        }

        @Override
        public URL getSharedResource(String s) {
            return null;
        }

        @Override
        public List<URL> getSharedResources(Collection<String> collection) {
            return List.of();
        }

        @Override
        public InputStream getSharedResourceAsStream(String s) {
            return null;
        }

        @Override
        public List<InputStream> getSharedResourcesAsStreams(Collection<String> collection) {
            return List.of();
        }
    }
}
