package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HtmlMarkupCompletionProvider {

    public enum Kind { NONE, TAG_NAME, ATTRIBUTE_NAME, CLOSE_TAG, TEXT_CONTENT }

    public record Context(Kind kind, String prefix, String tagName, List<String> openTags) {
        static final Context NONE = new Context(Kind.NONE, "", "", List.of());
    }

    private static final Set<String> CODE_BLOCK_DIRECTIVES = Set.of("code", "functions", "section");
    private static final Set<String> PAREN_DIRECTIVES = Set.of(
            "if", "for", "foreach", "while", "switch", "catch", "lock", "using");

    private static final List<String> ELEMENTS = List.of(
            "a", "abbr", "address", "area", "article", "aside", "audio", "b", "base", "bdi", "bdo",
            "blockquote", "body", "br", "button", "canvas", "caption", "cite", "code", "col", "colgroup",
            "data", "datalist", "dd", "del", "details", "dfn", "dialog", "div", "dl", "dt", "em", "embed",
            "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
            "head", "header", "hgroup", "hr", "html", "i", "iframe", "img", "input", "ins", "kbd", "label",
            "legend", "li", "link", "main", "map", "mark", "menu", "meta", "meter", "nav", "noscript",
            "object", "ol", "optgroup", "option", "output", "p", "param", "picture", "pre", "progress",
            "q", "rp", "rt", "ruby", "s", "samp", "script", "section", "select", "small", "source", "span",
            "strong", "style", "sub", "summary", "sup", "svg", "table", "tbody", "td", "template",
            "textarea", "tfoot", "th", "thead", "time", "title", "tr", "track", "u", "ul", "var", "video",
            "wbr");

    private static final List<String> ATTRIBUTES = List.of(
            "id", "class", "style", "title", "hidden", "lang", "dir", "tabindex", "accesskey",
            "contenteditable", "draggable", "spellcheck", "translate", "role", "slot",
            "href", "target", "rel", "download", "src", "srcset", "sizes", "alt", "width", "height",
            "loading", "type", "name", "value", "placeholder", "required", "disabled", "readonly",
            "checked", "selected", "multiple", "min", "max", "step", "pattern", "maxlength", "minlength",
            "autocomplete", "autofocus", "novalidate", "for", "form", "action", "method", "enctype",
            "colspan", "rowspan", "scope", "media", "charset", "content", "http-equiv", "defer", "async",
            "onclick", "onchange", "oninput", "onsubmit", "onload", "onkeydown", "onkeyup",
            "onmouseover", "onmouseout", "onfocus", "onblur");

    private static final Set<String> BOOLEAN_ATTRIBUTES = Set.of(
            "hidden", "required", "disabled", "readonly", "checked", "selected", "multiple",
            "autofocus", "novalidate", "defer", "async", "download");

    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
            "source", "track", "wbr");

    public List<AutoCompleteItem> suggestions(String text, int caretOffset) {
        return suggestions(text, caretOffset, true);
    }

    public List<AutoCompleteItem> suggestions(String text, int caretOffset, boolean explicit) {
        Context context = analyze(text, caretOffset);
        return switch (context.kind()) {
            case TAG_NAME -> tagNameItems(context);
            case ATTRIBUTE_NAME -> attributeItems(context.prefix());
            case CLOSE_TAG -> closeTagItems(context);
            case TEXT_CONTENT -> explicit ? contentCloseItems(context, text, caretOffset) : List.of();
            case NONE -> List.of();
        };
    }

    public Context analyze(String text, int caretOffset) {
        if (text == null || text.isEmpty()) {
            return Context.NONE;
        }
        int n = text.length();
        int caret = Math.max(0, Math.min(caretOffset, n));
        Deque<String> stack = new ArrayDeque<>();
        int i = 0;
        while (i < caret) {
            char c = text.charAt(i);
            if (c == '@' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*@", i + 2);
                end = end < 0 ? n : end + 2;
                if (caret < end) {
                    return Context.NONE;
                }
                i = end;
                continue;
            }
            if (c == '<' && text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + 4);
                end = end < 0 ? n : end + 3;
                if (caret < end) {
                    return Context.NONE;
                }
                i = end;
                continue;
            }
            if (c == '@' && i + 1 < n) {
                int end = razorEnd(text, i);
                if (caret < end) {
                    return Context.NONE;
                }
                i = end;
                continue;
            }
            if (c == '<') {
                int close = tagClose(text, i);
                int insideEnd = close < 0 ? n : close;
                if (caret <= insideEnd) {
                    return analyzeTag(text, i, caret, stack);
                }
                updateStack(text, i, close, stack);
                i = close + 1;
                continue;
            }
            i++;
        }
        if (stack.isEmpty()) {
            return Context.NONE;
        }
        return new Context(Kind.TEXT_CONTENT, "", "", new ArrayList<>(stack));
    }

    private Context analyzeTag(String text, int tagStart, int caret, Deque<String> stack) {
        int n = text.length();
        int pos = tagStart + 1;
        List<String> openTags = new ArrayList<>(stack);
        if (pos < n && text.charAt(pos) == '!') {
            return Context.NONE;
        }
        if (pos < n && text.charAt(pos) == '/') {
            int nameStart = pos + 1;
            int q = nameStart;
            while (q < n && isTagNamePart(text.charAt(q))) {
                q++;
            }
            String prefix = text.substring(nameStart, Math.min(caret, q));
            return new Context(Kind.CLOSE_TAG, prefix, "", openTags);
        }
        int nameStart = pos;
        while (pos < n && isTagNamePart(text.charAt(pos))) {
            pos++;
        }
        int nameEnd = pos;
        String tagName = text.substring(nameStart, nameEnd);
        if (caret <= nameEnd) {
            String prefix = text.substring(nameStart, caret);
            return new Context(Kind.TAG_NAME, prefix, prefix, openTags);
        }
        int j = nameEnd;
        boolean inValue = false;
        char quote = 0;
        while (j < caret) {
            char c = text.charAt(j);
            if (inValue) {
                if (c == quote) {
                    inValue = false;
                }
            } else if (c == '"' || c == '\'') {
                inValue = true;
                quote = c;
            }
            j++;
        }
        if (inValue) {
            return Context.NONE;
        }
        int p = caret;
        while (p > nameEnd && isAttributeNamePart(text.charAt(p - 1))) {
            p--;
        }
        String prefix = text.substring(p, caret);
        if (prefix.startsWith("@")) {
            return Context.NONE;
        }
        return new Context(Kind.ATTRIBUTE_NAME, prefix, tagName, openTags);
    }

    private static void updateStack(String text, int tagStart, int close, Deque<String> stack) {
        int n = text.length();
        int limit = close < 0 ? n : close;
        int pos = tagStart + 1;
        if (pos >= limit) {
            return;
        }
        char first = text.charAt(pos);
        if (first == '!') {
            return;
        }
        boolean closing = first == '/';
        int nameStart = closing ? pos + 1 : pos;
        int q = nameStart;
        while (q < limit && isTagNamePart(text.charAt(q))) {
            q++;
        }
        String name = text.substring(nameStart, q).toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return;
        }
        if (closing) {
            if (stack.contains(name)) {
                while (!stack.isEmpty() && !stack.pop().equals(name)) {
                    // discard mismatched open tags until the matching one is removed
                }
            }
            return;
        }
        if (VOID_ELEMENTS.contains(name) || isSelfClosing(text, nameStart, limit)) {
            return;
        }
        stack.push(name);
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

    private List<AutoCompleteItem> tagNameItems(Context context) {
        String prefix = context.prefix();
        List<AutoCompleteItem> items = new ArrayList<>();
        if (prefix.isEmpty()) {
            for (String open : context.openTags()) {
                items.add(new AutoCompleteItem("/" + open + ">", "/" + open, "fechar </" + open + ">"));
            }
        }
        String needle = prefix.toLowerCase(Locale.ROOT);
        for (String element : ELEMENTS) {
            if (element.startsWith(needle)) {
                items.add(new AutoCompleteItem(element, element, "html element"));
            }
        }
        return items;
    }

    private List<AutoCompleteItem> closeTagItems(Context context) {
        String needle = context.prefix().toLowerCase(Locale.ROOT);
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String open : context.openTags()) {
            if (open.startsWith(needle)) {
                items.add(new AutoCompleteItem(open + ">", open, "fechar </" + open + ">"));
            }
        }
        return items;
    }

    private List<AutoCompleteItem> contentCloseItems(Context context, String text, int caretOffset) {
        String preservedPrefix = wordPrefixBefore(text, caretOffset);
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String open : context.openTags()) {
            String close = "</" + open + ">";
            items.add(new AutoCompleteItem(preservedPrefix + close, close, "fechar </" + open + ">"));
        }
        return items;
    }

    private List<AutoCompleteItem> attributeItems(String prefix) {
        String needle = prefix.toLowerCase(Locale.ROOT);
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String attribute : ATTRIBUTES) {
            if (!attribute.startsWith(needle)) {
                continue;
            }
            String insert = BOOLEAN_ATTRIBUTES.contains(attribute) ? attribute : attribute + "=\"\"";
            items.add(new AutoCompleteItem(insert, attribute, "html attribute"));
        }
        return items;
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

    private static boolean isTagNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '.' || c == '_';
    }

    private static boolean isAttributeNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '.' || c == '_' || c == '@';
    }

    private static String wordPrefixBefore(String text, int caretOffset) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int caret = Math.max(0, Math.min(caretOffset, text.length()));
        int start = caret;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                break;
            }
            start--;
        }
        return text.substring(start, caret);
    }
}
