package dtm.ide;

import java.nio.file.Files;
import java.nio.file.Path;

final class FileStamp {

    private static final String MISSING = "missing";

    private FileStamp() {
    }

    static String of(Path file) {
        if (file == null) {
            return MISSING;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
        } catch (Exception e) {
            return MISSING;
        }
    }

    static String of(Path... files) {
        StringBuilder builder = new StringBuilder();
        for (Path file : files) {
            builder.append(of(file)).append('|');
        }
        return builder.toString();
    }
}
