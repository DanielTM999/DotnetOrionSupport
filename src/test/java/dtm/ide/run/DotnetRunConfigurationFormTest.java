package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DotnetRunConfigurationFormTest {
    @Test
    void preservesUnknownPropertiesAndRemovesClearedFields() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var form = new DotnetRunConfigurationForm(() -> null);
            var data = new RunConfigurationData();
            data.setType(DotnetRunSupport.TYPE_RUN);
            data.setProperties(new LinkedHashMap<>(Map.of("futureOption", "keep", "programArgs", "")));
            form.setData(data);
            var saved = form.getData();
            assertEquals("keep", saved.getProperties().get("futureOption"));
            assertFalse(saved.getProperties().containsKey("programArgs"));
        });
    }
}
