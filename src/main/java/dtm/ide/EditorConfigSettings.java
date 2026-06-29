package dtm.ide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class EditorConfigSettings {

    private EditorConfigSettings() {
    }

    static FormatOptions resolve(Path sourceFile, int defaultTabSize, boolean defaultInsertSpaces) {
        int tabSize = Math.max(1, defaultTabSize);
        boolean insertSpaces = defaultInsertSpaces;
        if (sourceFile == null) {
            return new FormatOptions(tabSize, insertSpaces);
        }
        Path file = sourceFile.toAbsolutePath().normalize();
        List<Path> configs = editorConfigs(file.getParent());
        for (Path config : configs) {
            Parsed parsed = parse(config, file);
            if (parsed.indentStyle != null) {
                insertSpaces = !"tab".equals(parsed.indentStyle);
            }
            if (parsed.tabWidth != null) {
                tabSize = parsed.tabWidth;
            }
            if (parsed.indentSize != null) {
                tabSize = parsed.indentSize;
            }
        }
        return new FormatOptions(tabSize, insertSpaces);
    }

    private static List<Path> editorConfigs(Path directory) {
        List<Path> configs = new ArrayList<>();
        for (Path current = directory; current != null; current = current.getParent()) {
            Path config = current.resolve(".editorconfig");
            if (Files.isRegularFile(config)) {
                configs.add(config);
                if (declaresRoot(config)) {
                    break;
                }
            }
        }
        Collections.reverse(configs);
        return configs;
    }

    private static boolean declaresRoot(Path config) {
        try {
            boolean inSection = false;
            for (String raw : Files.readAllLines(config)) {
                String line = raw.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    inSection = true;
                } else if (!inSection && property(line, "root").equalsIgnoreCase("true")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static Parsed parse(Path config, Path sourceFile) {
        Parsed result = new Parsed();
        boolean applies = true;
        Path base = config.getParent();
        String relative = base == null ? sourceFile.getFileName().toString()
                : base.relativize(sourceFile).toString().replace('\\', '/');
        try {
            for (String raw : Files.readAllLines(config)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    applies = matches(line.substring(1, line.length() - 1).trim(), relative);
                    continue;
                }
                if (!applies) {
                    continue;
                }
                String style = property(line, "indent_style");
                if (!style.isEmpty() && (style.equalsIgnoreCase("space") || style.equalsIgnoreCase("tab"))) {
                    result.indentStyle = style.toLowerCase(Locale.ROOT);
                }
                Integer indent = positiveInt(property(line, "indent_size"));
                if (indent != null) {
                    result.indentSize = indent;
                }
                Integer width = positiveInt(property(line, "tab_width"));
                if (width != null) {
                    result.tabWidth = width;
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static String property(String line, String expected) {
        int separator = line.indexOf('=');
        if (separator < 0) {
            separator = line.indexOf(':');
        }
        if (separator <= 0 || !line.substring(0, separator).trim().equalsIgnoreCase(expected)) {
            return "";
        }
        return line.substring(separator + 1).trim();
    }

    private static Integer positiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean matches(String glob, String relative) {
        for (String expanded : expandBraces(glob)) {
            String target = expanded.contains("/") ? relative : Path.of(relative).getFileName().toString();
            if (Pattern.compile(globRegex(expanded), Pattern.CASE_INSENSITIVE).matcher(target).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> expandBraces(String glob) {
        int open = glob.indexOf('{');
        int close = open < 0 ? -1 : glob.indexOf('}', open + 1);
        if (open < 0 || close < 0) {
            return List.of(glob);
        }
        List<String> expanded = new ArrayList<>();
        for (String option : glob.substring(open + 1, close).split(",")) {
            expanded.add(glob.substring(0, open) + option.trim() + glob.substring(close + 1));
        }
        return expanded;
    }

    private static String globRegex(String glob) {
        String normalized = glob.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '*') {
                if (i + 1 < normalized.length() && normalized.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (ch == '?') {
                regex.append("[^/]");
            } else {
                if (".()[]$^+|".indexOf(ch) >= 0) {
                    regex.append('\\');
                }
                regex.append(ch);
            }
        }
        return regex.append('$').toString();
    }

    record FormatOptions(int tabSize, boolean insertSpaces) {
    }

    private static final class Parsed {
        private String indentStyle;
        private Integer indentSize;
        private Integer tabWidth;
    }
}
