package dtm.ide.coverage;

import java.nio.file.Path;
import java.util.Map;

public record CoverageReport(Map<Path, Map<Integer, Long>> files, Map<Path, String> sourceHashes) {
    public CoverageReport(Map<Path, Map<Integer, Long>> files) { this(files, Map.of()); }
    public CoverageReport {
        files = files.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
        sourceHashes = Map.copyOf(sourceHashes);
    }
    public long lines() { return files.values().stream().mapToLong(Map::size).sum(); }
    public long covered() { return files.values().stream().flatMap(m -> m.values().stream()).filter(h -> h > 0).count(); }
    public double percent() { return lines() == 0 ? 0 : 100.0 * covered() / lines(); }

    public static String hash(String source) {
        try {
            source = java.util.Objects.requireNonNullElse(source, "").replace("\r\n", "\n").replace('\r', '\n');
            if (source.startsWith("\uFEFF")) source = source.substring(1);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
