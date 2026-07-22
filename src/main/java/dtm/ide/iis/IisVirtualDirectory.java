package dtm.ide.iis;

public record IisVirtualDirectory(String name,
                                  String applicationName,
                                  String path,
                                  String physicalPath) {

    public boolean isRoot() {
        return path == null || path.isBlank() || "/".equals(path);
    }
}
