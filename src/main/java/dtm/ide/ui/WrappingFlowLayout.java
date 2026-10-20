package dtm.ide.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;

/** Flow toolbar whose preferred height includes wrapped rows in narrow dock panels. */
final class WrappingFlowLayout extends FlowLayout {
    WrappingFlowLayout() { super(FlowLayout.LEADING, 6, 4); }

    @Override public Dimension preferredLayoutSize(Container parent) {
        synchronized (parent.getTreeLock()) {
            var insets = parent.getInsets();
            int width = parent.getParent() == null ? parent.getWidth() : parent.getParent().getWidth();
            if (width <= 0) width = 800;
            int available = Math.max(1, width - insets.left - insets.right - 2 * getHgap());
            int rowWidth = 0, rowHeight = 0, totalHeight = 0, widest = 0;
            for (Component component : parent.getComponents()) {
                if (!component.isVisible()) continue;
                var size = component.getPreferredSize();
                int next = rowWidth == 0 ? size.width : rowWidth + getHgap() + size.width;
                if (rowWidth > 0 && next > available) {
                    totalHeight += rowHeight + getVgap(); widest = Math.max(widest, rowWidth);
                    rowWidth = size.width; rowHeight = size.height;
                } else { rowWidth = next; rowHeight = Math.max(rowHeight, size.height); }
            }
            return new Dimension(Math.max(widest, rowWidth) + insets.left + insets.right + 2 * getHgap(),
                    totalHeight + rowHeight + insets.top + insets.bottom + 2 * getVgap());
        }
    }
    @Override public Dimension minimumLayoutSize(Container parent) { return preferredLayoutSize(parent); }
}
