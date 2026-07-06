package dtm.ide.settings;

import java.util.Locale;

public enum LanguageServerMode {

    AUTO,
    OMNISHARP,
    ROSLYN;

    public static LanguageServerMode fromKey(String key) {
        if (key == null || key.isBlank()) {
            return AUTO;
        }
        try {
            return LanguageServerMode.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return AUTO;
        }
    }

    public String key() {
        return name();
    }
}
