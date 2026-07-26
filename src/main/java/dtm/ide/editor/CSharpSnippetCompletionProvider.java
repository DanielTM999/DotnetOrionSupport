package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.List;
import java.util.Locale;

/** Common C# editor snippets available independently of the language server. */
public final class CSharpSnippetCompletionProvider {

    private static final List<AutoCompleteItem> SNIPPETS = List.of(
            AutoCompleteItem.snippet("cwl", "Console.WriteLine($0);", "Console.WriteLine"),
            AutoCompleteItem.snippet("cw", "Console.Write($0);", "Console.Write"),
            AutoCompleteItem.snippet("for", "for (int ${1:i} = 0; ${1:i} < ${2:count}; ${1:i}++)\n{\n    $0\n}", "for loop"),
            AutoCompleteItem.snippet("foreach", "foreach (var ${1:item} in ${2:collection})\n{\n    $0\n}", "foreach loop"),
            AutoCompleteItem.snippet("if", "if (${1:condition})\n{\n    $0\n}", "if statement"),
            AutoCompleteItem.snippet("try", "try\n{\n    $0\n}\ncatch (Exception ${1:ex})\n{\n    \n}", "try/catch"),
            AutoCompleteItem.snippet("prop", "public ${1:string} ${2:Name} { get; set; }$0", "auto property"),
            AutoCompleteItem.snippet("ctor", "public ${1:ClassName}(${2})\n{\n    $0\n}", "constructor")
    );

    public List<AutoCompleteItem> suggestions(String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return SNIPPETS.stream()
                .filter(item -> normalizedPrefix.isBlank()
                        || item.label().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
    }
}
