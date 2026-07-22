package dtm.ide.iis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisExpressLauncherTest {

    private static final String TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration>
              <configSections>
                <sectionGroup name="system.applicationHost">
                  <section name="sites" />
                </sectionGroup>
              </configSections>
              <system.applicationHost>
                <applicationPools>
                  <add name="Clr4IntegratedAppPool" managedRuntimeVersion="v4.0" managedPipelineMode="Integrated" />
                </applicationPools>
                <sites>
                  <site name="WebSite1" id="1">
                    <application path="/">
                      <virtualDirectory path="/" physicalPath="C:\\old" />
                    </application>
                    <bindings>
                      <binding protocol="http" bindingInformation="*:8080:localhost" />
                    </bindings>
                  </site>
                </sites>
              </system.applicationHost>
              <system.webServer>
                <globalModules />
                <modules />
              </system.webServer>
            </configuration>
            """;

    private Path template(Path directory) throws Exception {
        Path file = directory.resolve("applicationhost.config");
        Files.writeString(file, TEMPLATE);
        return file;
    }

    private String generate(Path directory, IisExpressLauncher.SiteSpec spec, Path module) throws Exception {
        Document document = IisExpressLauncher.buildConfig(template(directory), spec, module);
        Path output = directory.resolve("out.config");
        IisExpressLauncher.write(document, output);
        return Files.readString(output);
    }

    @Test
    void injectsSiteWithBindingsAndContentRoot(@TempDir Path directory) throws Exception {
        Path module = directory.resolve("aspnetcorev2.dll");
        Files.writeString(module, "");
        Path content = directory.resolve("publish");
        Files.createDirectories(content);

        String config = generate(directory, new IisExpressLauncher.SiteSpec("MyApi", content,
                List.of(new IisBinding("http", "*", "31234", "localhost")), true), module);

        assertTrue(config.contains("name=\"MyApi\""));
        assertTrue(config.contains("bindingInformation=\"*:31234:localhost\""));
        assertTrue(config.contains(content.toAbsolutePath().normalize().toString()));
        assertTrue(config.contains("applicationPool=\"" + IisExpressLauncher.DEFAULT_POOL + "\""));
    }

    @Test
    void registersAspNetCoreModuleWhenMissing(@TempDir Path directory) throws Exception {
        Path module = directory.resolve("aspnetcorev2.dll");
        Files.writeString(module, "");
        Path content = directory.resolve("publish");
        Files.createDirectories(content);

        String config = generate(directory, new IisExpressLauncher.SiteSpec("MyApi", content,
                List.of(new IisBinding("http", "*", "31234", "localhost")), true), module);

        assertTrue(config.contains("AspNetCoreModuleV2"));
        assertTrue(config.contains(module.toAbsolutePath().toString()));
        assertTrue(config.contains("name=\"aspNetCore\""));
    }

    @Test
    void failsWhenAspNetCoreModuleIsMissing(@TempDir Path directory) throws Exception {
        Path content = directory.resolve("publish");
        Files.createDirectories(content);
        IisExpressLauncher.SiteSpec spec = new IisExpressLauncher.SiteSpec("MyApi", content,
                List.of(new IisBinding("http", "*", "31234", "localhost")), true);
        Path templateFile = template(directory);

        assertThrows(IllegalStateException.class,
                () -> IisExpressLauncher.buildConfig(templateFile, spec, null));
    }

    @Test
    void replacesSiteWithSameNameInsteadOfDuplicating(@TempDir Path directory) throws Exception {
        Path content = directory.resolve("site");
        Files.createDirectories(content);

        String config = generate(directory, new IisExpressLauncher.SiteSpec("WebSite1", content,
                List.of(new IisBinding("http", "*", "9000", "localhost")), false), null);

        assertEquals(1, countOccurrences(config, "name=\"WebSite1\""));
        assertTrue(config.contains("bindingInformation=\"*:9000:localhost\""));
        assertTrue(!config.contains("C:\\old"));
    }

    @Test
    void classicProjectKeepsManagedPool(@TempDir Path directory) throws Exception {
        Path content = directory.resolve("legacy");
        Files.createDirectories(content);

        String config = generate(directory, new IisExpressLauncher.SiteSpec("Legacy", content,
                List.of(new IisBinding("http", "*", "8081", "localhost")), false), null);

        assertTrue(config.contains("applicationPool=\"Clr4IntegratedAppPool\""));
        assertTrue(!config.contains("AspNetCoreModuleV2"));
    }

    @Test
    void buildsCommandLineForSite(@TempDir Path directory) {
        Path config = directory.resolve("applicationhost.config");
        if (!IisExpressLauncher.available()) {
            return;
        }
        List<String> command = IisExpressLauncher.command(config, "MyApi");

        assertTrue(command.get(1).startsWith("/config:"));
        assertEquals("/site:MyApi", command.get(2));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
