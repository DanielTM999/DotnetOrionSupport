package dtm.ide.iis;

public record IisApplication(String name,
                             String siteName,
                             String path,
                             String applicationPool,
                             String physicalPath,
                             String enabledProtocols) {

    public boolean isRoot() {
        return path == null || path.isBlank() || "/".equals(path);
    }

    public String displayPath() {
        return isRoot() ? "/" : path;
    }
}
