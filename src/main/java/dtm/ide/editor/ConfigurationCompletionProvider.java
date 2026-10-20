package dtm.ide.editor;

import dtm.ide.aspnet.AspNetIndex;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import java.util.List;
import java.util.regex.Pattern;

public final class ConfigurationCompletionProvider {
    private static final Pattern CONTEXT = Pattern.compile(
            "(?:[Cc]onfiguration\\s*\\[|\\.GetSection\\s*\\(|\\.GetConnectionString\\s*\\(|\\.GetValue(?:<[^>]+>)?\\s*\\()\\s*\"([^\"\\r\\n]*)$");

    public List<AutoCompleteItem> suggestions(String source, int caret, List<AspNetIndex.Entry> entries) {
        if (source == null || caret < 0 || caret > source.length()) return List.of();
        var matcher = CONTEXT.matcher(source.substring(0, caret));
        if (!matcher.find() || !CSharpLexical.codeAt(source, matcher.start())) return List.of();
        boolean connection = matcher.group().contains("GetConnectionString");
        String prefix = matcher.group(1);
        // The editor replaces only the current word, so preserve a previously typed colon path.
        int wordStart = prefix.length();
        while (wordStart > 0 && Character.isJavaIdentifierPart(prefix.charAt(wordStart - 1))) wordStart--;
        int replace = wordStart;
        return entries.stream().filter(entry -> entry.kind() == AspNetIndex.Kind.CONFIGURATION)
                .map(AspNetIndex.Entry::name).filter(key -> !connection || key.startsWith("ConnectionStrings:"))
                .map(key -> connection ? key.substring("ConnectionStrings:".length()) : key)
                .filter(key -> key.startsWith(prefix)).distinct().sorted()
                .map(key -> new AutoCompleteItem(key.substring(replace), key, "appsettings", null, null,
                        AutoCompleteItem.Kind.TEXT, List.of())).toList();
    }
}
