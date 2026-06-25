package dtm.ide.settings;

import java.util.Locale;

public enum TreeLayout {

    DEFAULT,
    VISUAL_STUDIO;

    public static TreeLayout fromKey(String key) {
        if (key == null || key.isBlank()) {
            return DEFAULT;
        }
        try {
            return TreeLayout.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    public String key() {
        return name();
    }
}
