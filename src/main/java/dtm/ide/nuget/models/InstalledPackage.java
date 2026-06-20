package dtm.ide.nuget.models;

import java.nio.file.Path;

public record InstalledPackage(String id, String version, Mode mode, Path projectFile) {

    public enum Mode {

        PACKAGE_REFERENCE,

        PACKAGES_CONFIG
    }

    public String key() {
        return (id == null ? "" : id.toLowerCase()) + ":" + (version == null ? "" : version);
    }
}
