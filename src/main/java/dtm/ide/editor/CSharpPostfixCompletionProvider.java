package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CSharpPostfixCompletionProvider {
    private static final Map<String, String> TEMPLATES = Map.of(
            "var", "var ${1:value} = EXPR;$0",
            "if", "if (EXPR)\n{\n    $0\n}",
            "nn", "if (EXPR != null)\n{\n    $0\n}",
            "null", "if (EXPR == null)\n{\n    $0\n}",
            "not", "!(EXPR)$0",
            "foreach", "foreach (var ${1:item} in EXPR)\n{\n    $0\n}",
            "return", "return EXPR;$0",
            "cw", "System.Console.WriteLine(EXPR);$0",
            "par", "(EXPR)$0");

    public List<AutoCompleteItem> suggestions(String text, int caret) {
        if (text == null || caret <= 0 || caret > text.length() || !CSharpLexical.codeAt(text, caret)) return List.of();
        int word = caret;
        while (word > 0 && Character.isJavaIdentifierPart(text.charAt(word - 1))) word--;
        int dot = word - 1;
        if (dot <= 0 || text.charAt(dot) != '.') return List.of();
        int line = text.lastIndexOf('\n', dot) + 1;
        int start = dot, depth = 0;
        for (int i = dot - 1; i >= line; i--) {
            char c = text.charAt(i);
            if (c == ')' || c == ']') depth++;
            else if (c == '(' || c == '[') { if (depth == 0) break; depth--; }
            else if (depth == 0 && !(Character.isJavaIdentifierPart(c) || c == '.' || c == '@' || c == '?' || c == '!')) break;
            start = i;
        }
        if (depth != 0 || start == dot || !Character.isJavaIdentifierStart(text.charAt(start))) return List.of();
        String statementPrefix = text.substring(line, start).strip();
        if (!statementPrefix.isEmpty() && !statementPrefix.endsWith("{") && !statementPrefix.endsWith(";")) return List.of();
        String expr = text.substring(start, dot);
        String prefix = text.substring(word, caret);
        int expressionStart = start;
        var remove = TextEdit.delete(new Range(position(text, expressionStart), position(text, dot + 1)));
        String escaped = expr.replace("\\", "\\\\").replace("$", "\\$").replace("}", "\\}");
        List<AutoCompleteItem> out = new ArrayList<>();
        TEMPLATES.entrySet().stream().sorted(Map.Entry.comparingByKey()).filter(e -> e.getKey().startsWith(prefix)).forEach(e ->
                out.add(new AutoCompleteItem(e.getValue().replace("EXPR", escaped), e.getKey(), "C# postfix", expr,
                        null, AutoCompleteItem.Kind.SNIPPET, List.of(remove))));
        return List.copyOf(out);
    }
    private static Position position(String text, int offset) {
        return new Position(CSharpLexical.line(text, offset), offset - text.lastIndexOf('\n', offset - 1) - 1);
    }
}
