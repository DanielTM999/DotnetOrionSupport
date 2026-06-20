package dtm.ide.run;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

final class DebugVarIcon implements Icon {

    private static final int SIZE = 14;

    private final String glyph;
    private final Color color;

    private DebugVarIcon(String glyph, Color color) {
        this.glyph = glyph;
        this.color = color;
    }

    static DebugVarIcon scope() {
        return new DebugVarIcon("{ }", DebugTheme.accentColor());
    }

    static DebugVarIcon forVariable(DebugVar var) {
        String value = var == null ? "" : var.value();
        String type = var == null ? "" : var.type();
        boolean expandable = var != null && var.expandable();
        String v = value == null ? "" : value.trim();
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (t.contains("[") || v.startsWith("[") || t.contains("list") || t.contains("array")
                || t.contains("dictionary") || t.contains("collection")) {
            return new DebugVarIcon("[ ]", DebugTheme.objectColor());
        }
        if (expandable) {
            return new DebugVarIcon("{ }", DebugTheme.objectColor());
        }
        if (v.equalsIgnoreCase("null")) {
            return new DebugVarIcon("∅", DebugTheme.nullColor());
        }
        if (v.equals("true") || v.equals("false") || t.equals("bool") || t.equals("boolean")) {
            return new DebugVarIcon("◑", DebugTheme.boolColor());
        }
        if (!v.isEmpty() && (v.charAt(0) == '"' || t.equals("string") || t.equals("char"))) {
            return new DebugVarIcon("\"\"", DebugTheme.stringColor());
        }
        return new DebugVarIcon("#", DebugTheme.numberColor());
    }

    @Override
    public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        RoundRectangle2D shape = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, SIZE - 1f, SIZE - 1f, 5f, 5f);
        g2.setColor(DebugTheme.blend(DebugTheme.contentBg(), color, 0.22F));
        g2.fill(shape);
        g2.setColor(color);
        Font font = DebugTheme.monoFont().deriveFont(Font.BOLD, glyph.length() > 1 ? 8f : 10f);
        g2.setFont(font);
        int tw = g2.getFontMetrics().stringWidth(glyph);
        int ascent = g2.getFontMetrics().getAscent();
        int th = g2.getFontMetrics().getHeight();
        g2.drawString(glyph, x + (SIZE - tw) / 2f, y + ascent + (SIZE - th) / 2f);
        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }
}
