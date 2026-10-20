package dtm.ide.coverage;

import dtm.ide.api.project.editor.IdeEditorContext;
import java.awt.Color;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Coverage is invalidated on editing, rather than displaying results against different source. */
public final class CoverageSupport {
    private volatile CoverageReport report = new CoverageReport(Map.of());
    private final Map<Path, IdeEditorContext> editors = new ConcurrentHashMap<>();

    public void attach(IdeEditorContext editor) {
        editors.put(editor.filePath().toAbsolutePath().normalize(), editor);
        apply(editor);
    }
    public void close(Path path) {
        var editor = editors.remove(path.toAbsolutePath().normalize());
        if (editor != null) {
            var layer = editor.getGutterLayer(DotnetCoverageLayer.class);
            if (layer != null) editor.removeGutterLayer(layer);
        }
    }
    public void publish(CoverageReport value) {
        report = value;
        editors.values().forEach(this::apply);
    }
    public void edited(IdeEditorContext editor) {
        var layer = editor.getGutterLayer(DotnetCoverageLayer.class);
        if (layer != null) { layer.clearLineColors(); editor.repaintGutter(); }
        if (!report.files().containsKey(editor.filePath().toAbsolutePath().normalize())) return;
        var remaining = new java.util.HashMap<>(report.files());
        remaining.remove(editor.filePath().toAbsolutePath().normalize());
        var hashes = new java.util.HashMap<>(report.sourceHashes());
        hashes.remove(editor.filePath().toAbsolutePath().normalize());
        report = new CoverageReport(remaining, hashes);
    }
    public void clear() {
        publish(new CoverageReport(Map.of()));
        editors.values().forEach(editor -> {
            var layer = editor.getGutterLayer(DotnetCoverageLayer.class);
            if (layer != null) editor.removeGutterLayer(layer);
        });
        editors.clear();
    }
    private void apply(IdeEditorContext editor) {
        var layer = editor.getGutterLayer(DotnetCoverageLayer.class);
        var lines = report.files().getOrDefault(editor.filePath().toAbsolutePath().normalize(), Map.of());
        String hash = report.sourceHashes().get(editor.filePath().toAbsolutePath().normalize());
        if (hash == null || !hash.equals(CoverageReport.hash(editor.getText()))) lines = Map.of();
        if (layer == null && !lines.isEmpty()) {
            layer = new DotnetCoverageLayer();
            if (!editor.addGutterLayer(layer)) return;
        }
        if (layer == null) return;
        layer.clearLineColors();
        var target = layer;
        lines.forEach((line, hits) -> target.setLineColor(line - 1,
                hits > 0 ? new Color(65, 170, 90, 190) : new Color(220, 70, 70, 190)));
        editor.repaintGutter();
    }
}
