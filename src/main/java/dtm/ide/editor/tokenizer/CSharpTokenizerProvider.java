package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CSharpTokenizerProvider implements TokenizerCodeEditorProvider {

    public static final String TOKEN_CLASS = "CLASS";
    public static final String TOKEN_VARIABLE = "VARIABLE";
    public static final String TOKEN_CONSTANT = "CONSTANT";
    public static final String TOKEN_FUNCTION = "FUNCTION";
    public static final String TOKEN_TYPE = "TYPE";
    public static final String TOKEN_PREPROCESSOR = "PREPROCESSOR";

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "as", "base", "break", "case", "catch", "checked", "class",
            "const", "continue", "default", "delegate", "do", "else", "enum",
            "event", "explicit", "extern", "false", "finally", "fixed", "for",
            "foreach", "goto", "if", "implicit", "in", "interface", "internal",
            "is", "lock", "namespace", "new", "null", "operator", "out", "override",
            "params", "private", "protected", "public", "readonly", "ref", "return",
            "sealed", "sizeof", "stackalloc", "static", "struct", "switch", "this",
            "throw", "true", "try", "typeof", "unchecked", "unsafe", "using",
            "virtual", "volatile", "while"
    );

    private static final Set<String> CONTEXTUAL_KEYWORDS = Set.of(
            "add", "alias", "and", "ascending", "async", "await", "by", "descending",
            "dynamic", "equals", "file", "from", "get", "global", "group", "init",
            "into", "join", "let", "managed", "nameof", "not", "notnull", "on", "or",
            "orderby", "partial", "record", "remove", "required", "scoped", "select",
            "set", "unmanaged", "value", "var", "when", "where", "with", "yield"
    );

    private static final Set<String> BUILTIN_TYPES = Set.of(
            "bool", "byte", "sbyte", "char", "decimal", "double", "float", "int",
            "uint", "long", "ulong", "short", "ushort", "object", "string", "void",
            "nint", "nuint"
    );

    private static final Set<String> KEYWORD_DECLARES_TYPE = Set.of(
            "class", "struct", "interface", "enum", "record", "delegate"
    );

    @Override
    public boolean supportsIncremental() {
        return false;
    }

    @Override
    public synchronized java.util.Collection<Token> tokenize(
            String text, TokenClassifierCodeEditorProvider classifier) {
        String src = text == null ? "" : text;
        List<LexToken> raw = lex(src);
        return classify(src, raw, classifier);
    }

    private static List<LexToken> lex(String text) {
        ArrayList<LexToken> tokens = new ArrayList<>(Math.max(16, text.length() / 4));
        int i = 0;
        int n = text.length();

        while (i < n) {
            char c = text.charAt(i);

            if (c == '\r' || c == '\n') {
                int start = i;
                i++;
                if (c == '\r' && i < n && text.charAt(i) == '\n') {
                    i++;
                }
                tokens.add(new LexToken(start, i, TokenType.NEWLINE, text));
                continue;
            }

            if (c == ' ' || c == '\t' || c == '\f' || c == 0x0b) {
                int start = i;
                while (i < n) {
                    char w = text.charAt(i);
                    if (w == ' ' || w == '\t' || w == '\f' || w == 0x0b) {
                        i++;
                    } else {
                        break;
                    }
                }
                tokens.add(new LexToken(start, i, TokenType.WHITESPACE, text));
                continue;
            }

            if (c == '/' && i + 1 < n) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    int start = i;
                    i += 2;
                    while (i < n && text.charAt(i) != '\r' && text.charAt(i) != '\n') {
                        i++;
                    }
                    tokens.add(new LexToken(start, i, TokenType.COMMENT, text));
                    continue;
                }
                if (next == '*') {
                    int start = i;
                    i += 2;
                    int end = text.indexOf("*/", i);
                    i = end < 0 ? n : end + 2;
                    tokens.add(new LexToken(start, i, TokenType.COMMENT, text));
                    continue;
                }
            }

            if (c == '#' && isLineStart(text, i)) {
                int start = i;
                i++;
                while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                    i++;
                }
                while (i < n && isIdentPart(text.charAt(i))) {
                    i++;
                }
                tokens.add(new LexToken(start, i, TOKEN_PREPROCESSOR, text));
                continue;
            }

            if (c == '"' && i + 2 < n && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') {
                int start = i;
                i += 3;
                int end = text.indexOf("\"\"\"", i);
                i = end < 0 ? n : end + 3;
                tokens.add(new LexToken(start, i, TokenType.STRING, text));
                continue;
            }

            if ((c == '$' || c == '@') && i + 1 < n) {
                int prefixEnd = readStringPrefix(text, i);
                if (prefixEnd > i && prefixEnd < n && text.charAt(prefixEnd) == '"') {
                    boolean verbatim = text.substring(i, prefixEnd).indexOf('@') >= 0;
                    int start = i;
                    i = scanString(text, prefixEnd, verbatim);
                    tokens.add(new LexToken(start, i, TokenType.STRING, text));
                    continue;
                }
            }

            if (c == '"') {
                int start = i;
                i = scanString(text, i, false);
                tokens.add(new LexToken(start, i, TokenType.STRING, text));
                continue;
            }

            if (c == '\'') {
                int start = i;
                i = scanChar(text, i);
                tokens.add(new LexToken(start, i, TokenType.STRING, text));
                continue;
            }

            if (isDigit(c) || (c == '.' && i + 1 < n && isDigit(text.charAt(i + 1)))) {
                int start = i;
                i = scanNumber(text, i);
                tokens.add(new LexToken(start, i, TokenType.NUMBER, text));
                continue;
            }

            if (isIdentStart(c) || (c == '@' && i + 1 < n && isIdentStart(text.charAt(i + 1)))) {
                int start = i;
                if (c == '@') {
                    i++;
                }
                while (i < n && isIdentPart(text.charAt(i))) {
                    i++;
                }
                String word = text.substring(start, i);
                String bare = word.startsWith("@") ? word.substring(1) : word;
                String type;
                if (KEYWORDS.contains(bare) || BUILTIN_TYPES.contains(bare)
                        || CONTEXTUAL_KEYWORDS.contains(bare)) {
                    type = TokenType.KEYWORD;
                } else {
                    type = TokenType.IDENTIFIER;
                }
                tokens.add(new LexToken(start, i, type, text));
                continue;
            }

            tokens.add(new LexToken(i, i + 1, TokenType.SYMBOL, text));
            i++;
        }

        return tokens;
    }

    private static int readStringPrefix(String text, int i) {
        int n = text.length();
        int j = i;

        while (j < n && (text.charAt(j) == '$' || text.charAt(j) == '@')) {
            j++;
            if (j - i > 2) {
                break;
            }
        }
        return j;
    }

    private static int scanString(String text, int quoteIndex, boolean verbatim) {
        int n = text.length();
        int i = quoteIndex + 1;
        while (i < n) {
            char c = text.charAt(i);
            if (verbatim) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                i++;
            } else {
                if (c == '\\' && i + 1 < n) {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    return i + 1;
                }
                if (c == '\r' || c == '\n') {
                    return i;
                }
                i++;
            }
        }
        return i;
    }

    private static int scanChar(String text, int quoteIndex) {
        int n = text.length();
        int i = quoteIndex + 1;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == '\'') {
                return i + 1;
            }
            if (c == '\r' || c == '\n') {
                return i;
            }
            i++;
        }
        return i;
    }

    private static int scanNumber(String text, int start) {
        int n = text.length();
        int i = start;
        if (text.charAt(i) == '0' && i + 1 < n
                && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X'
                || text.charAt(i + 1) == 'b' || text.charAt(i + 1) == 'B')) {
            i += 2;
            while (i < n && (isHex(text.charAt(i)) || text.charAt(i) == '_')) {
                i++;
            }
        } else {
            while (i < n && (isDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                i++;
            }
            if (i < n && text.charAt(i) == '.' && i + 1 < n && isDigit(text.charAt(i + 1))) {
                i++;
                while (i < n && (isDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                    i++;
                }
            }
            if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
                i++;
                if (i < n && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                    i++;
                }
                while (i < n && isDigit(text.charAt(i))) {
                    i++;
                }
            }
        }

        while (i < n && isNumberSuffix(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static List<Token> classify(String text, List<LexToken> raw,
                                        TokenClassifierCodeEditorProvider classifier) {
        Set<String> typeNames = collectTypeNames(raw);
        ArrayList<Token> result = new ArrayList<>(raw.size());

        for (int i = 0; i < raw.size(); i++) {
            LexToken token = raw.get(i);
            String type = token.type;
            if (TokenType.IDENTIFIER.equals(token.type)) {
                type = classifyIdentifier(raw, i, typeNames, classifier);
            }
            result.add(new Token(token.start, token.end, type, text.substring(token.start, token.end)));
        }
        return result;
    }

    private static Set<String> collectTypeNames(List<LexToken> raw) {
        Set<String> typeNames = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            LexToken token = raw.get(i);
            if (TokenType.KEYWORD.equals(token.type) && KEYWORD_DECLARES_TYPE.contains(token.text())) {
                int next = nextSignificant(raw, i);
                if (next >= 0 && TokenType.IDENTIFIER.equals(raw.get(next).type)) {
                    typeNames.add(raw.get(next).text());
                }
            }
        }
        return typeNames;
    }

    private static String classifyIdentifier(List<LexToken> raw, int index,
                                             Set<String> typeNames,
                                             TokenClassifierCodeEditorProvider classifier) {
        LexToken token = raw.get(index);
        String word = token.text();

        if (isAllCapsConstant(word)) {
            return TOKEN_CONSTANT;
        }
        if (typeNames.contains(word) || isTypeContext(raw, index)) {
            return TOKEN_CLASS;
        }
        if (isFunctionIdentifier(raw, index)) {
            return TOKEN_FUNCTION;
        }
        if (looksLikeTypeName(word)) {
            return TOKEN_CLASS;
        }

        String classified = classifier == null ? null : classifier.classify(word);
        if (classified != null && !classified.isBlank() && !TokenType.UNKNOWN.equals(classified)) {
            return classified;
        }
        return TOKEN_VARIABLE;
    }

    private static boolean isTypeContext(List<LexToken> raw, int index) {
        int prev = previousSignificant(raw, index);
        if (prev < 0) {
            return false;
        }
        LexToken previous = raw.get(prev);
        return TokenType.KEYWORD.equals(previous.type)
                && (KEYWORD_DECLARES_TYPE.contains(previous.text())
                || "new".equals(previous.text())
                || "typeof".equals(previous.text()));
    }

    private static boolean isFunctionIdentifier(List<LexToken> raw, int index) {
        int next = nextSignificant(raw, index);
        return next >= 0 && "(".equals(raw.get(next).text());
    }

    private static int nextSignificant(List<LexToken> raw, int index) {
        for (int i = index + 1; i < raw.size(); i++) {
            if (!isTrivia(raw.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int previousSignificant(List<LexToken> raw, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (!isTrivia(raw.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isTrivia(LexToken token) {
        return TokenType.WHITESPACE.equals(token.type)
                || TokenType.NEWLINE.equals(token.type)
                || TokenType.COMMENT.equals(token.type)
                || TOKEN_PREPROCESSOR.equals(token.type);
    }

    private static boolean isAllCapsConstant(String value) {
        boolean hasLetter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 'a' && c <= 'z') {
                return false;
            }
            if (c >= 'A' && c <= 'Z') {
                hasLetter = true;
            }
        }
        return hasLetter && value.indexOf('_') >= 0;
    }

    private static boolean looksLikeTypeName(String value) {
        return !value.isEmpty()
                && Character.isUpperCase(value.charAt(0))
                && !isAllCapsConstant(value);
    }

    private static boolean isLineStart(String text, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isNumberSuffix(char c) {
        return c == 'f' || c == 'F' || c == 'd' || c == 'D'
                || c == 'm' || c == 'M' || c == 'u' || c == 'U'
                || c == 'l' || c == 'L';
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private record LexToken(int start, int end, String type, String source) {
        String text() {
            return source.substring(start, end);
        }
    }
}
