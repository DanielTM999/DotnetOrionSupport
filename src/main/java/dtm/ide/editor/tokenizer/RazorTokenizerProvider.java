package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class RazorTokenizerProvider implements TokenizerCodeEditorProvider {

    private static final Set<String> CODE_BLOCK_DIRECTIVES = Set.of("code", "functions", "section");
    private static final Set<String> RAZOR_DIRECTIVES = Set.of(
            "page", "model", "namespace", "using", "inject", "inherits", "implements", "typeparam",
            "attribute", "layout", "rendermode", "section", "code", "functions", "addTagHelper",
            "removeTagHelper", "tagHelperPrefix", "await", "if", "else", "for", "foreach", "while",
            "switch", "case", "do", "try", "catch", "finally", "lock");
    private static final Set<String> PAREN_DIRECTIVES = Set.of(
            "if", "for", "foreach", "while", "switch", "catch", "lock", "using");

    private final CSharpTokenizerProvider csharp = new CSharpTokenizerProvider();

    @Override
    public boolean supportsIncremental() {
        return false;
    }

    @Override
    public synchronized Collection<Token> tokenize(String text, TokenClassifierCodeEditorProvider classifier) {
        String src = text == null ? "" : text;
        List<Token> out = new ArrayList<>();
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);

            if (c == '@' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = indexOf(src, "*@", i + 2);
                end = end < 0 ? n : end + 2;
                out.add(token(src, i, end, TokenType.COMMENT));
                i = end;
                continue;
            }

            if (c == '<' && src.startsWith("<!--", i)) {
                int end = indexOf(src, "-->", i + 4);
                end = end < 0 ? n : end + 3;
                out.add(token(src, i, end, TokenType.COMMENT));
                i = end;
                continue;
            }

            if (c == '@' && i + 1 < n) {
                i = scanRazorTransition(src, i, out, classifier);
                continue;
            }

            if (c == ' ' || c == '\t' || c == '\f' || c == 0x0b) {
                int start = i;
                while (i < n && isInlineWhitespace(src.charAt(i))) {
                    i++;
                }
                out.add(token(src, start, i, TokenType.WHITESPACE));
                continue;
            }

            if (c == '\r' || c == '\n') {
                int start = i;
                i++;
                if (c == '\r' && i < n && src.charAt(i) == '\n') {
                    i++;
                }
                out.add(token(src, start, i, TokenType.NEWLINE));
                continue;
            }

            if (c == '"' || c == '\'') {
                int end = skipString(src, i, c);
                out.add(token(src, i, end, TokenType.STRING));
                i = end;
                continue;
            }

            if (c == '<') {
                i = scanTag(src, i, out, classifier);
                continue;
            }

            if (c == '>') {
                out.add(token(src, i, i + 1, TokenType.SYMBOL));
                i++;
                continue;
            }

            if (isDigit(c)) {
                int start = i;
                while (i < n && (isDigit(src.charAt(i)) || src.charAt(i) == '.' || src.charAt(i) == '_')) {
                    i++;
                }
                out.add(token(src, start, i, TokenType.NUMBER));
                continue;
            }

            if (isIdentStart(c)) {
                int start = i;
                while (i < n && isIdentPart(src.charAt(i))) {
                    i++;
                }
                String word = src.substring(start, i);
                out.add(token(src, start, i, CSharpKeywords.contains(word) ? TokenType.KEYWORD : TokenType.IDENTIFIER));
                continue;
            }

            out.add(token(src, i, i + 1, TokenType.SYMBOL));
            i++;
        }
        return out;
    }

    private int scanRazorTransition(String text, int at, List<Token> out, TokenClassifierCodeEditorProvider classifier) {
        int n = text.length();
        char next = text.charAt(at + 1);
        if (next == '@') {
            out.add(token(text, at, at + 2, TokenType.SYMBOL));
            return at + 2;
        }
        if (next == '{') {
            out.add(token(text, at, at + 1, TokenType.KEYWORD));
            int close = matchBalanced(text, at + 1, '{', '}');
            emitCsharp(out, classifier, text, at + 1, close);
            return close;
        }
        if (next == '(') {
            out.add(token(text, at, at + 1, TokenType.KEYWORD));
            int close = matchBalanced(text, at + 1, '(', ')');
            emitCsharp(out, classifier, text, at + 1, close);
            return close;
        }
        if (isIdentStart(next)) {
            int j = at + 1;
            while (j < n && isIdentPart(text.charAt(j))) {
                j++;
            }
            String word = text.substring(at + 1, j);
            out.add(token(text, at, j, RAZOR_DIRECTIVES.contains(word) ? TokenType.KEYWORD : CSharpTokenizerProvider.TOKEN_VARIABLE));
            if (CODE_BLOCK_DIRECTIVES.contains(word)) {
                int k = j;
                while (k < n && isInlineWhitespace(text.charAt(k))) {
                    k++;
                }
                if (k < n && text.charAt(k) == '{') {
                    if (k > j) {
                        out.add(token(text, j, k, TokenType.WHITESPACE));
                    }
                    int close = matchBalanced(text, k, '{', '}');
                    emitCsharp(out, classifier, text, k, close);
                    return close;
                }
            }
            if (PAREN_DIRECTIVES.contains(word)) {
                int k = j;
                while (k < n && isInlineWhitespace(text.charAt(k))) {
                    k++;
                }
                if (k < n && text.charAt(k) == '(') {
                    if (k > j) {
                        out.add(token(text, j, k, TokenType.WHITESPACE));
                    }
                    int close = matchBalanced(text, k, '(', ')');
                    emitCsharp(out, classifier, text, k, close);
                    return close;
                }
            }
            return j;
        }
        out.add(token(text, at, at + 1, TokenType.SYMBOL));
        return at + 1;
    }

    private int scanTag(String text, int at, List<Token> out, TokenClassifierCodeEditorProvider classifier) {
        int n = text.length();
        int i = at + 1;
        out.add(token(text, at, at + 1, TokenType.SYMBOL));
        if (i < n && text.charAt(i) == '/') {
            out.add(token(text, i, i + 1, TokenType.SYMBOL));
            i++;
        }
        int wsStart = i;
        while (i < n && isInlineWhitespace(text.charAt(i))) {
            i++;
        }
        if (i > wsStart) {
            out.add(token(text, wsStart, i, TokenType.WHITESPACE));
        }
        if (i < n && isTagNameStart(text.charAt(i))) {
            int nameStart = i;
            while (i < n && isTagNamePart(text.charAt(i))) {
                i++;
            }
            String name = text.substring(nameStart, i);
            out.add(token(text, nameStart, i, isComponentName(name)
                    ? CSharpTokenizerProvider.TOKEN_CLASS : CSharpTokenizerProvider.TOKEN_TYPE));
        }
        while (i < n) {
            char c = text.charAt(i);
            if (c == '>') {
                out.add(token(text, i, i + 1, TokenType.SYMBOL));
                return i + 1;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '>') {
                out.add(token(text, i, i + 2, TokenType.SYMBOL));
                return i + 2;
            }
            if (isInlineWhitespace(c) || c == '\r' || c == '\n') {
                int start = i;
                while (i < n && (isInlineWhitespace(text.charAt(i)) || text.charAt(i) == '\r' || text.charAt(i) == '\n')) {
                    i++;
                }
                out.add(token(text, start, i, containsNewline(text, start, i) ? TokenType.NEWLINE : TokenType.WHITESPACE));
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = skipString(text, i, c);
                if (i + 1 < end && text.charAt(i + 1) == '@') {
                    out.add(token(text, i, i + 1, TokenType.STRING));
                    int p = scanRazorTransition(text, i + 1, out, classifier);
                    if (p < end - 1) {
                        out.add(token(text, p, end - 1, TokenType.STRING));
                    }
                    out.add(token(text, end - 1, end, TokenType.STRING));
                } else {
                    out.add(token(text, i, end, TokenType.STRING));
                }
                i = end;
                continue;
            }
            if (c == '=') {
                out.add(token(text, i, i + 1, TokenType.SYMBOL));
                i++;
                continue;
            }
            if (c == '@' && i + 1 < n && (text.charAt(i + 1) == '(' || text.charAt(i + 1) == '{')) {
                i = scanRazorTransition(text, i, out, classifier);
                continue;
            }
            if (isAttributeNameStart(c)) {
                int start = i;
                while (i < n && isAttributeNamePart(text.charAt(i))) {
                    i++;
                }
                out.add(token(text, start, i, CSharpTokenizerProvider.TOKEN_VARIABLE));
                continue;
            }
            if (c == '@' && i + 1 < n) {
                i = scanRazorTransition(text, i, out, classifier);
                continue;
            }
            out.add(token(text, i, i + 1, TokenType.SYMBOL));
            i++;
        }
        return i;
    }

    private void emitCsharp(List<Token> out, TokenClassifierCodeEditorProvider classifier,
                            String text, int start, int end) {
        if (start >= end) {
            return;
        }
        String sub = text.substring(start, end);
        for (Token token : csharp.tokenize(sub, classifier)) {
            out.add(new Token(token.getStartOffset() + start, token.getEndOffset() + start,
                    token.getType(), token.getText()));
        }
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

    private static Token token(String text, int start, int end, String type) {
        return new Token(start, end, type, text.substring(start, end));
    }

    private static int indexOf(String text, String needle, int from) {
        return text.indexOf(needle, from);
    }

    private static boolean isInlineWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == 0x0b;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static boolean isTagNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isTagNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '.' || c == '_';
    }

    private static boolean isComponentName(String name) {
        return name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private static boolean isAttributeNameStart(char c) {
        return c == '@' || Character.isLetter(c) || c == '_' || c == ':';
    }

    private static boolean isAttributeNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '.' || c == '_' || c == '@';
    }

    private static boolean containsNewline(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                return true;
            }
        }
        return false;
    }

    private static final class CSharpKeywords {
        private static final Set<String> WORDS = Set.of(
                "var", "if", "else", "for", "foreach", "while", "do", "switch", "case", "return",
                "new", "using", "await", "async", "true", "false", "null", "class", "struct",
                "interface", "enum", "record", "public", "private", "protected", "internal",
                "static", "void", "int", "string", "bool", "double", "float", "decimal", "this",
                "base", "try", "catch", "finally", "throw", "in", "is", "as", "out", "ref");

        static boolean contains(String word) {
            return WORDS.contains(word);
        }
    }
}
