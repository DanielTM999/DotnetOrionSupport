package dtm.ide.aspnet;

import dtm.ide.editor.ConfigurationCompletionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AspNetIndexTest {
    @TempDir Path dir;
    @Test void indexesConventionalEntriesWithoutTreatingCommentsAsCode() throws Exception {
        Path program = dir.resolve("Program.cs");
        Files.writeString(program, """
            // app.MapGet("/fake", Handler);
            var s = "app.MapPost(\\"/fake\\", Handler)";
            app.MapGet("/weather", Handler);
            app.MapPost(route, Handler);
            builder.Services.AddScoped<IWeather, Weather>();
            builder.Configuration.GetSection("Weather");
            """);
        Path generated = Files.createDirectories(dir.resolve("obj")).resolve("Generated.cs");
        Files.writeString(generated, "app.MapGet(\"/generated\", Handler);");
        var result = new AspNetIndex().scan(dir, Map.of());
        assertEquals(4, result.entries().size());
        assertTrue(result.entries().stream().anyMatch(e -> e.name().equals("GET /weather")));
        assertTrue(result.entries().stream().anyMatch(e -> e.kind() == AspNetIndex.Kind.UNRESOLVED));
        assertTrue(result.entries().stream().anyMatch(e -> e.name().equals("IWeather,Weather")));
        var updated = new AspNetIndex().scan(dir, Map.of(program, "app.MapGet(\"/unsaved\", Handler);"));
        assertEquals("GET /unsaved", updated.entries().getFirst().name());
    }
    @Test void configurationCompletionUsesKeysWithoutExposingValues() throws Exception {
        var entries = new AspNetIndex().configuration(dir.resolve("appsettings.json"), """
            { // configuration
              "ConnectionStrings": {"Main": "secret"},
              "Logging": {"LogLevel": {"Default": "Information",}},
            }
            """);
        assertTrue(entries.stream().noneMatch(e -> e.toString().contains("secret")));
        String source = "builder.Configuration.GetConnectionString(\"Ma";
        var items = new ConfigurationCompletionProvider().suggestions(source, source.length(), entries);
        assertEquals("Main", items.getFirst().insertText());
        source = "builder.Configuration[\"Logging:LogLevel:De";
        items = new ConfigurationCompletionProvider().suggestions(source, source.length(), entries);
        assertEquals("Default", items.getFirst().insertText());
    }

    @Test void composesConventionalControllerRoutesAndFlagsDynamicRoutes() {
        var entries = new AspNetIndex().sourceEntries(dir.resolve("WeatherController.cs"), """
            [Route("api/[controller]")]
            public class WeatherController : ControllerBase {
                [HttpGet("{id}")] public string Find(int id) => "ok";
                [HttpPost(RouteConstants.Create)] public void Create() { }
            }
            """);
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("GET /api/Weather/{id}")), entries.toString());
        assertTrue(entries.stream().anyMatch(e -> e.kind() == AspNetIndex.Kind.UNRESOLVED));
    }

    @Test void repeatedJsonPropertyNamesNavigateToTheirOwnLines() throws Exception {
        var entries = new AspNetIndex().configuration(dir.resolve("appsettings.json"), """
            {
              "First": {"Default": "one"},
              "Second": {"Default": "two"}
            }
            """);
        assertEquals(1, entries.stream().filter(e -> e.name().equals("First:Default")).findFirst().orElseThrow().line());
        assertEquals(2, entries.stream().filter(e -> e.name().equals("Second:Default")).findFirst().orElseThrow().line());
    }
}
