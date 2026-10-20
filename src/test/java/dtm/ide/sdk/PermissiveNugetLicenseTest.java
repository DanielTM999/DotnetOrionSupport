package dtm.ide.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PermissiveNugetLicenseTest {
    @TempDir Path dir;
    @Test void permitsReviewedExpressionsAndRejectsMissingOrRestrictiveLicenses() throws Exception {
        Path file = dir.resolve("component.nupkg");
        for (String license : new String[]{"MIT", "Apache-2.0", "GPL-3.0-only", "", "LicenseRef-Proprietary"}) {
            try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
                zip.putNextEntry(new ZipEntry("component.nuspec"));
                zip.write(("<package><metadata><license type='expression'>" + license + "</license></metadata></package>").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (license.equals("MIT") || license.equals("Apache-2.0")) assertDoesNotThrow(() -> PermissiveNugetLicense.verify(file));
            else assertThrows(java.io.IOException.class, () -> PermissiveNugetLicense.verify(file), license);
        }
    }
}
