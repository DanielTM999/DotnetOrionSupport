package dtm.ide.licenses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeLicenseCheckTest {
    @TempDir Path dir;

    @Test void rejectsMissingAndRestrictiveLicensesAndEmptyReports() throws Exception {
        Path report = dir.resolve("licenses.xml");
        for (String name : new String[]{"GPL-3.0-only", "EPL-2.0", "LicenseRef-Proprietary", ""}) {
            Files.writeString(report, report(name));
            assertThrows(IOException.class, () -> RuntimeLicenseCheck.checkReport(report));
        }
        Files.writeString(report, "<licenseSummary><dependencies/></licenseSummary>");
        assertThrows(IOException.class, () -> RuntimeLicenseCheck.checkReport(report));
    }

    @Test void acceptsPermissiveMetadata() throws Exception {
        Path report = dir.resolve("licenses.xml");
        Files.writeString(report, report("MIT"));
        var dependency = RuntimeLicenseCheck.checkReport(report).getFirst();
        assertEquals("demo:demo:1", dependency.artifact());
        assertEquals(java.util.List.of("MIT"), dependency.licenses());
    }

    private static String report(String name) {
        return "<licenseSummary><dependencies><dependency><groupId>demo</groupId><artifactId>demo</artifactId>"
                + "<version>1</version><licenses><license><name>" + name + "</name></license></licenses>"
                + "</dependency></dependencies></licenseSummary>";
    }
}
