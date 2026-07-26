package dtm.ide.lsp;

import dtm.ide.sdk.DotnetSdkService;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspAutocompleteTest {

    @Test
    void opensProjectAndReturnsCompletions(@TempDir Path root) throws Exception {
        Path project = createConsoleProject(root);
        Path program = project.resolve("Program.cs");
        String text = program.getFileName() == null ? "" : Files.readString(program);
        int caret = text.indexOf("Console.") + "Console.".length();

        FakeLspService service = new FakeLspService();
        try {
            service.bindProject(project);
            service.start();
            assertTrue(waitFor(service::isRunning, 15_000),
                    "language server não ficou pronto: " + service.getLastError());

            List<AutoCompleteItem> items = service.complete(program, text, 0, caret);
            assertFalse(items.isEmpty(), "esperava autocompletes do servidor");
            assertTrue(hasLabel(items, "WriteLine"), "esperava 'WriteLine' entre os autocompletes");
            assertEquals(AutoCompleteItem.Kind.METHOD, items.stream()
                    .filter(item -> "WriteLine".equals(item.label()))
                    .findFirst()
                    .orElseThrow()
                    .kind(), "o tipo LSP Method deve ser preservado no autocomplete");

            List<AutoCompleteItem> filtered = service.completeForEditor(program, text, 0, caret, "Wr");
            assertFalse(filtered.isEmpty(), "esperava autocompletes filtrados por prefixo");
            assertTrue(filtered.get(0).label().startsWith("Wr"),
                    "com prefixo 'Wr' o primeiro item deveria começar por 'Wr', veio: " + filtered.get(0).label());
            assertTrue(hasLabel(filtered, "WriteLine"), "esperava 'WriteLine' entre os autocompletes filtrados");
        } finally {
            service.stop();
        }
    }

    private static boolean hasLabel(List<AutoCompleteItem> items, String label) {
        return items.stream().anyMatch(item -> label.equals(item.label()));
    }

    private static Path createConsoleProject(Path root) throws Exception {
        Path project = root.resolve("App");
        Files.createDirectories(project);
        Files.writeString(project.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <OutputType>Exe</OutputType>
                    <TargetFramework>net8.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);
        Files.writeString(project.resolve("Program.cs"),
                "class Program { static void Main() { System.Console. } }");
        return project;
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static final class FakeLspService extends AbstractLspService {

        private FakeLspService() {
            super(null, new DotnetSdkService(null, null));
        }

        @Override
        protected String serverName() {
            return "FakeLsp";
        }

        @Override
        protected Optional<Path> resolveServerBinary() {
            return Optional.of(Path.of(javaExecutable()));
        }

        @Override
        protected List<String> buildLaunchCommand(Path binary, Path loadTarget) {
            return List.of(
                    binary.toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    FakeLspServerMain.class.getName());
        }

        private static String javaExecutable() {
            String home = System.getProperty("java.home");
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            return Path.of(home, "bin", windows ? "java.exe" : "java").toString();
        }
    }
}
