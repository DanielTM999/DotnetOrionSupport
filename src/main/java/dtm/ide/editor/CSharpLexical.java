package dtm.ide.editor;

import java.util.ArrayList;
import java.util.List;

/** Small lexical reader for conservative local assistance, never a replacement for Roslyn. */
public final class CSharpLexical {
    public record Token(String text, int start, int end, boolean literal, boolean dynamic) {}
    private CSharpLexical() {}

    public static List<Token> tokens(String source) {
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i + 2); i = end < 0 ? source.length() : end; continue;
            }
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2); i = end < 0 ? source.length() : end + 2; continue;
            }
            int start = i;
            boolean dynamic = false, verbatim = false;
            int quote = i;
            while (quote < source.length() && (source.charAt(quote) == '$' || source.charAt(quote) == '@')) {
                dynamic |= source.charAt(quote) == '$'; verbatim |= source.charAt(quote) == '@'; quote++;
            }
            if (quote < source.length() && source.charAt(quote) == '"') {
                int count = 1;
                while (quote + count < source.length() && source.charAt(quote + count) == '"') count++;
                if (count >= 3) {
                    String delimiter = "\"".repeat(count);
                    int end = source.indexOf(delimiter, quote + count);
                    i = end < 0 ? source.length() : end + count;
                    tokens.add(new Token(source.substring(quote + count, end < 0 ? source.length() : end), start, i, true, dynamic));
                    continue;
                }
                StringBuilder value = new StringBuilder();
                i = quote + 1;
                int interpolationDepth = 0;
                char nestedQuote = 0;
                while (i < source.length()) {
                    char ch = source.charAt(i++);
                    if (dynamic && interpolationDepth > 0) {
                        value.append(ch);
                        if (nestedQuote != 0) {
                            if (ch == '\\' && i < source.length()) value.append(source.charAt(i++));
                            else if (ch == nestedQuote) nestedQuote = 0;
                        } else if (ch == '"' || ch == '\'') nestedQuote = ch;
                        else if (ch == '{') interpolationDepth++;
                        else if (ch == '}') interpolationDepth--;
                        continue;
                    }
                    if (dynamic && ch == '{') {
                        if (i < source.length() && source.charAt(i) == '{') { value.append("{{"); i++; }
                        else { interpolationDepth++; value.append(ch); }
                        continue;
                    }
                    if (ch == '"') {
                        if (verbatim && i < source.length() && source.charAt(i) == '"') { value.append('"'); i++; continue; }
                        break;
                    }
                    if (ch == '\\' && !verbatim && i < source.length()) {
                        char escaped = source.charAt(i++);
                        value.append(switch (escaped) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> escaped; });
                    } else value.append(ch);
                }
                tokens.add(new Token(value.toString(), start, i, true, dynamic));
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < source.length()) {
                    char ch = source.charAt(i++);
                    if (ch == '\\' && i < source.length()) i++;
                    else if (ch == '\'') break;
                }
                tokens.add(new Token(source.substring(start, i), start, i, true, false));
                continue;
            }
            if (Character.isJavaIdentifierStart(c) || c == '@') {
                i++;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
            } else i++;
            tokens.add(new Token(source.substring(start, i), start, i, false, false));
        }
        return List.copyOf(tokens);
    }

    public static boolean codeAt(String source, int offset) {
        if (offset < 0 || offset > source.length()) return false;
        // Include a sentinel: a caret inside a comment/string must not produce code assistance.
        String probe = source.substring(0, offset) + "__orion_caret__" + source.substring(offset);
        return tokens(probe).stream().anyMatch(t -> !t.literal() && t.start() <= offset && t.end() > offset);
    }
    public static int line(String source, int offset) {
        int line = 0;
        for (int i = 0; i < Math.min(offset, source.length()); i++) if (source.charAt(i) == '\n') line++;
        return line;
    }
}
