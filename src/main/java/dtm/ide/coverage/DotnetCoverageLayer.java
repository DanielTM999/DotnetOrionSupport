package dtm.ide.coverage;

import dtm.stools.component.panels.editor.code.gutter.layer.LineMarkerLayer;

public final class DotnetCoverageLayer extends LineMarkerLayer {
    public DotnetCoverageLayer() {
        setStripeWidth(3);
        setSide(Side.RIGHT);
    }
}
