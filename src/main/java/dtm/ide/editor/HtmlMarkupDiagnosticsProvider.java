package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HtmlMarkupDiagnosticsProvider {

    private static final String SOURCE = "dotnet-html";

    private static final Set<String> CODE_BLOCK_DIRECTIVES = Set.of("code", "functions", "section");
    private static final Set<String> PAREN_DIRECTIVES = Set.of(
            "if", "for", "foreach", "while", "switch", "catch", "lock", "using");

    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
            "source", "track", "wbr");

    private record OpenTag(String name, int offset) {
    }

    public List<Diagnostic> diagnose(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int n = text.length();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Deque<OpenTag> stack = new ArrayDeque<>();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '@' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*@", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '<' && text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + 4);
                i = end < 0 ? n : end + 3;
                continue;
            }
            if (c == '@' && i + 1 < n) {
                i = razorEnd(text, i);
                continue;
            }
            if (c == '<' && i + 1 < n && text.charAt(i + 1) == '/') {
                int nameStart = i + 2;
                if (nameStart >= n || !isTagNameStart(text.charAt(nameStart))) {
                    i++;
                    continue;
                }
                int q = nameStart;
                while (q < n && isTagNamePart(text.charAt(q))) {
                    q++;
                }
                String name = text.substring(nameStart, q).toLowerCase(Locale.ROOT);
                int close = tagClose(text, i);
                if (stackContains(stack, name)) {
                    while (!stack.isEmpty() && !stack.pop().name().equals(name)) {
                        // discard improperly nested tags until the match is closed
                    }
                } else {
                    diagnostics.add(warning(text, i, q, "Tag de fechamento </" + name + "> sem abertura correspondente."));
                }
                i = close < 0 ? q : close + 1;
                continue;
            }
            if (c == '<' && i + 1 < n && isTagNameStart(text.charAt(i + 1))) {
                int nameStart = i + 1;
                int q = nameStart;
                while (q < n && isTagNamePart(text.charAt(q))) {
                    q++;
                }
                String name = text.substring(nameStart, q).toLowerCase(Locale.ROOT);
                int close = tagClose(text, i);
                int limit = close < 0 ? n : close;
                if (!VOID_ELEMENTS.contains(name) && !isSelfClosing(text, nameStart, limit)) {
                    stack.push(new OpenTag(name, i));
                }
                i = close < 0 ? q : close + 1;
                continue;
            }
            i++;
        }
        while (!stack.isEmpty()) {
            OpenTag open = stack.pop();
            int end = Math.min(n, open.offset() + 1 + open.name().length());
            diagnostics.add(warning(text, open.offset(), end, "Tag <" + open.name() + "> não foi fechada."));
        }
        return diagnostics;
    }

    private static Diagnostic warning(String text, int startOffset, int endOffset, String message) {
        int[] start = lineCol(text, startOffset);
        int[] end = lineCol(text, endOffset);
        return new Diagnostic(start[0], start[1], end[0], end[1],
                DiagnosticSeverity.WARNING, message, SOURCE, null);
    }

    private static int[] lineCol(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[]{line, offset - lineStart};
    }

    private static boolean stackContains(Deque<OpenTag> stack, String name) {
        for (OpenTag open : stack) {
            if (open.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSelfClosing(String text, int from, int limit) {
        int i = limit - 1;
        while (i >= from) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i--;
                continue;
            }
            return c == '/';
        }
        return false;
    }

    private static int razorEnd(String text, int at) {
        int n = text.length();
        char next = at + 1 < n ? text.charAt(at + 1) : 0;
        if (next == '@') {
            return at + 2;
        }
        if (next == '{') {
            return matchBalanced(text, at + 1, '{', '}');
        }
        if (next == '(') {
            return matchBalanced(text, at + 1, '(', ')');
        }
        if (isIdentStart(next)) {
            int j = at + 1;
            while (j < n && isIdentPart(text.charAt(j))) {
                j++;
            }
            String word = text.substring(at + 1, j);
            int k = j;
            while (k < n && isInlineWhitespace(text.charAt(k))) {
                k++;
            }
            if (CODE_BLOCK_DIRECTIVES.contains(word) && k < n && text.charAt(k) == '{') {
                return matchBalanced(text, k, '{', '}');
            }
            if (PAREN_DIRECTIVES.contains(word) && k < n && text.charAt(k) == '(') {
                return matchBalanced(text, k, '(', ')');
            }
            return j;
        }
        return at + 1;
    }

    private static int tagClose(String text, int start) {
        int n = text.length();
        int i = start + 1;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipString(text, i, c);
                continue;
            }
            if (c == '>') {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static int matchBalanced(String text, int openIndex, char open, char close) {
        int n = text.length();
        int depth = 0;
        int i = openIndex;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int nl = text.indexOf('\n', i);
                i = nl < 0 ? n : nl;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int e = text.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else if (c == '"' || c == '\'') {
                i = skipString(text, i, c);
            } else if (c == open) {
                depth++;
                i++;
            } else if (c == close) {
                depth--;
                i++;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return n;
    }

    private static int skipString(String text, int quoteIndex, char quote) {
        int n = text.length();
        int i = quoteIndex + 1;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            if (c == '\r' || c == '\n') {
                return i;
            }
            i++;
        }
        return i;
    }

    private static boolean isInlineWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == 0x0b;
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static boolean isTagNameStart(char c) {
        return Character.isLetter(c);
    }

    private static boolean isTagNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '.' || c == '_';
    }
}
