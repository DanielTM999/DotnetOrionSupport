package dtm.ide.coverage;

import dtm.ide.util.XmlDocuments;
import org.w3c.dom.Element;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CoberturaReportReader {
    public CoverageReport read(Path file, Path workspace) throws IOException {
        var document = XmlDocuments.readCoverage(file);
        Path root = workspace.toAbsolutePath().normalize();
        List<Path> sources = new ArrayList<>();
        sources.add(root);
        var sourceNodes = document.getElementsByTagName("source");
        for (int i = 0; i < sourceNodes.getLength(); i++) {
            String value = sourceNodes.item(i).getTextContent().strip();
            if (!value.isEmpty()) {
                Path source = root.resolve(value.replace('\\', '/')).normalize();
                // Coverlet may use the drive/filesystem root as <source> when only one document is covered.
                // Constrain the final resolved file, rather than rejecting such source prefixes.
                sources.add(source);
            }
        }
        Map<Path, Map<Integer, Long>> files = new LinkedHashMap<>();
        Map<Path, String> hashes = new LinkedHashMap<>();
        var classes = document.getElementsByTagName("class");
        for (int i = 0; i < classes.getLength(); i++) {
            var type = (Element) classes.item(i);
            String filename = type.getAttribute("filename").replace('\\', '/');
            if (filename.isBlank()) continue;
            Path target = resolve(filename, sources, root);
            if (target == null) continue;
            if (!hashes.containsKey(target)) hashes.put(target, CoverageReport.hash(Files.readString(target)));
            var lines = files.computeIfAbsent(target, ignored -> new LinkedHashMap<>());
            var entries = type.getElementsByTagName("line");
            for (int j = 0; j < entries.getLength(); j++) {
                var entry = (Element) entries.item(j);
                try {
                    int number = Integer.parseInt(entry.getAttribute("number"));
                    long hits = Long.parseLong(entry.getAttribute("hits"));
                    if (number <= 0 || hits < 0) throw new IOException("Invalid coverage line in " + file);
                    lines.merge(number, hits, Math::max);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid coverage line in " + file, e);
                }
            }
        }
        return new CoverageReport(files, hashes);
    }

    private static Path resolve(String filename, List<Path> sources, Path root) {
        for (Path source : sources) {
            Path candidate = source.resolve(filename).toAbsolutePath().normalize();
            if (candidate.startsWith(root) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    public CoverageReport merge(List<CoverageReport> reports) {
        Map<Path, Map<Integer, Long>> merged = new LinkedHashMap<>();
        Map<Path, String> hashes = new LinkedHashMap<>();
        for (var report : reports) report.files().forEach((path, lines) -> {
            var target = merged.computeIfAbsent(path, ignored -> new LinkedHashMap<>());
            lines.forEach((line, hits) -> target.merge(line, hits, Math::max));
            if (report.sourceHashes().containsKey(path)) hashes.put(path, report.sourceHashes().get(path));
        });
        return new CoverageReport(merged, hashes);
    }
}
