package dtm.ide.iis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisLogSettingsTest {

    @Test
    void parsesLogSectionFromSiteConfig() {
        String xml = """
                <system.applicationHost>
                  <sites>
                    <site name="Default Web Site" id="1">
                      <application path="/">
                        <virtualDirectory path="/" physicalPath="C:\\inetpub\\wwwroot" />
                      </application>
                      <bindings>
                        <binding protocol="http" bindingInformation="*:80:" />
                      </bindings>
                      <logFile logFormat="W3C" directory="D:\\logs" period="Hourly" enabled="true" />
                    </site>
                  </sites>
                </system.applicationHost>
                """;

        IisService.LogSettings settings = AppCmd.parseLogSettings(xml);

        assertTrue(settings.enabled());
        assertEquals("D:\\logs", settings.directory());
        assertEquals("W3C", settings.format());
        assertEquals("Hourly", settings.period());
    }

    @Test
    void detectsDisabledLogging() {
        String xml = "<site name=\"X\" id=\"2\"><logFile enabled=\"false\" directory=\"C:\\l\" /></site>";

        IisService.LogSettings settings = AppCmd.parseLogSettings(xml);

        assertFalse(settings.enabled());
        assertEquals("C:\\l", settings.directory());
    }

    @Test
    void fallsBackToDefaultsWhenSectionIsAbsent() {
        IisService.LogSettings settings = AppCmd.parseLogSettings("<site name=\"X\" id=\"1\" />");

        assertEquals(IisService.LogSettings.defaults().directory(), settings.directory());
        assertEquals("W3C", settings.format());
    }

    @Test
    void resolvesLogFolderPerSiteId() {
        IisSite site = new IisSite("Default Web Site", "3", "Started", List.of(), "C:\\web", "DefaultAppPool");
        IisService.LogSettings settings = new IisService.LogSettings(true, "D:\\logs", "W3C", "Daily");

        Path folder = IisService.resolveLogFolder(site, settings);

        assertEquals(Path.of("D:\\logs", "W3SVC3"), folder);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void expandsEnvironmentVariablesInLogDirectory() {
        String expanded = IisService.expandEnvironment("%SystemDrive%\\inetpub\\logs\\LogFiles");

        assertFalse(expanded.contains("%SystemDrive%"), "a variável deve ser expandida: " + expanded);
        assertTrue(expanded.endsWith("\\inetpub\\logs\\LogFiles"));
    }

    @Test
    void keepsUnknownVariablesUntouched() {
        String value = IisService.expandEnvironment("%VARIAVEL_INEXISTENTE_XYZ%\\logs");

        assertEquals("%VARIAVEL_INEXISTENTE_XYZ%\\logs", value);
    }
}
