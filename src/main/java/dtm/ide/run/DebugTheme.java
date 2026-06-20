package dtm.ide.run;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.util.Locale;

final class DebugTheme {

    private DebugTheme() {
    }

    static Color panelBg() {
        return resolve("Panel.background", new Color(0x1E2127));
    }

    static Color contentBg() {
        return resolve("TextArea.background", panelBg());
    }

    static Color stripeBg() {
        return blend(contentBg(), textColor(), isDark(contentBg()) ? 0.035F : 0.03F);
    }

    static Color hoverBg() {
        return blend(contentBg(), accentColor(), isDark(contentBg()) ? 0.12F : 0.08F);
    }

    static Color headerBg() {
        return blend(panelBg(), textColor(), isDark(panelBg()) ? 0.06F : 0.04F);
    }

    static Color popupBg() {
        return blend(panelBg(), textColor(), isDark(panelBg()) ? 0.12F : 0.06F);
    }

    static Color borderColor() {
        return resolve("Component.borderColor", blend(panelBg(), textColor(), 0.20F));
    }

    static Color selectionBg() {
        return blend(contentBg(), accentColor(), 0.28F);
    }

    static Color textColor() {
        return resolve("Label.foreground", new Color(0xD7DEE8));
    }

    static Color mutedColor() {
        return blend(textColor(), panelBg(), 0.45F);
    }

    static Color accentColor() {
        return resolve("Component.accentColor", resolve("ProgressBar.foreground", new Color(0x4F9CF9)));
    }

    static Color nameColor() {
        return blend(accentColor(), textColor(), 0.35F);
    }

    static Color numberColor() {
        return isDark(contentBg()) ? new Color(0xB5CEA8) : new Color(0x098658);
    }

    static Color stringColor() {
        return isDark(contentBg()) ? new Color(0xCE9178) : new Color(0xA31515);
    }

    static Color boolColor() {
        return isDark(contentBg()) ? new Color(0x569CD6) : new Color(0x0000FF);
    }

    static Color nullColor() {
        return isDark(contentBg()) ? new Color(0xD16969) : new Color(0xB22222);
    }

    static Color objectColor() {
        return isDark(contentBg()) ? new Color(0x9CDCFE) : new Color(0x267F99);
    }

    static Color valueColor() {
        return isDark(contentBg()) ? new Color(0xDCDCAA) : new Color(0x795E26);
    }

    static Color valueColorFor(String value, String type) {
        String v = value == null ? "" : value.trim();
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (v.equalsIgnoreCase("null")) {
            return nullColor();
        }
        if (v.equals("true") || v.equals("false") || t.equals("bool") || t.equals("boolean")) {
            return boolColor();
        }
        if (!v.isEmpty() && (v.charAt(0) == '"' || v.charAt(0) == '\'' || t.equals("string") || t.equals("char"))) {
            return stringColor();
        }
        if (isNumeric(v)) {
            return numberColor();
        }
        if (v.startsWith("{") || v.startsWith("[") || v.startsWith("0x") || t.contains("[")) {
            return objectColor();
        }
        return valueColor();
    }

    private static boolean isNumeric(String v) {
        if (v.isEmpty()) {
            return false;
        }
        boolean digit = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isDigit(c)) {
                digit = true;
            } else if (c != '.' && c != '-' && c != '+' && c != 'e' && c != 'E'
                    && c != 'x' && c != 'f' && c != 'd' && c != 'm' && c != 'L' && c != 'u'
                    && !(c >= 'a' && c <= 'f') && !(c >= 'A' && c <= 'F')) {
                return false;
            }
        }
        return digit;
    }

    static Font uiFont() {
        Font font = UIManager.getFont("Label.font");
        return font != null ? font : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    static Font monoFont() {
        return new Font(Font.MONOSPACED, Font.PLAIN, 12);
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static Color resolve(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    static boolean isDark(Color color) {
        if (color == null) {
            return true;
        }
        double luminance = (0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue()) / 255.0;
        return luminance < 0.5;
    }

    static Color blend(Color base, Color overlay, float ratio) {
        float r = Math.clamp(ratio, 0F, 1F);
        return new Color(
                Math.round(base.getRed() * (1 - r) + overlay.getRed() * r),
                Math.round(base.getGreen() * (1 - r) + overlay.getGreen() * r),
                Math.round(base.getBlue() * (1 - r) + overlay.getBlue() * r));
    }
}
