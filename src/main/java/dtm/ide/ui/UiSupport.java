package dtm.ide.ui;

import javax.swing.JScrollPane;

final class UiSupport {

    private static final String FLAT_STYLE = "FlatLaf.style";
    private static final String THIN_SCROLLBAR = "width: 9; thumbArc: 999; thumbInsets: 2,2,2,2";

    private UiSupport() {
    }

    static void thinScrollbars(JScrollPane pane) {
        pane.getVerticalScrollBar().putClientProperty(FLAT_STYLE, THIN_SCROLLBAR);
        pane.getHorizontalScrollBar().putClientProperty(FLAT_STYLE, THIN_SCROLLBAR);
        pane.getVerticalScrollBar().setOpaque(false);
        pane.getHorizontalScrollBar().setOpaque(false);
    }

    static void styleScroll(JScrollPane pane) {
        thinScrollbars(pane);
    }
}
