package dtm.ide.iis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisFeaturesTest {

    @Test
    void readsEnabledFromSectionRoot() {
        String xml = """
                <system.webServer>
                  <security>
                    <authentication>
                      <anonymousAuthentication enabled="false" userName="IUSR" />
                    </authentication>
                  </security>
                </system.webServer>
                """;

        assertFalse(IisFeatures.parseEnabled(xml, true));
    }

    @Test
    void readsEnabledWhenAttributeIsOnRootElement() {
        String xml = "<directoryBrowse enabled=\"true\" showFlags=\"Date, Time, Size\" />";

        assertTrue(IisFeatures.parseEnabled(xml, false));
    }

    @Test
    void fallsBackWhenAttributeIsAbsent() {
        assertTrue(IisFeatures.parseEnabled("<directoryBrowse />", true));
        assertFalse(IisFeatures.parseEnabled("<directoryBrowse />", false));
        assertTrue(IisFeatures.parseEnabled("saída inválida", true));
        assertFalse(IisFeatures.parseEnabled(null, false));
    }

    @Test
    void readsDefaultDocumentsInOrder() {
        String xml = """
                <defaultDocument enabled="true">
                  <files>
                    <add value="Default.htm" />
                    <add value="index.html" />
                    <add value="index.html" />
                  </files>
                </defaultDocument>
                """;

        List<String> documents = IisFeatures.parseDocuments(xml);

        assertEquals(List.of("Default.htm", "index.html"), documents);
    }

    @Test
    void returnsEmptyDocumentsForInvalidOutput() {
        assertTrue(IisFeatures.parseDocuments("").isEmpty());
        assertTrue(IisFeatures.parseDocuments("sem xml aqui").isEmpty());
    }

    @Test
    void defaultsAreUsable() {
        IisFeatures.Settings defaults = IisFeatures.Settings.defaults();

        assertTrue(defaults.anonymousAuthentication());
        assertFalse(defaults.windowsAuthentication());
        assertTrue(defaults.defaultDocument());
        assertTrue(defaults.documents().contains("index.html"));
    }
}
