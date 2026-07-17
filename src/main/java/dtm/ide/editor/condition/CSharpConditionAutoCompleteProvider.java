package dtm.ide.editor.condition;

import dtm.ide.run.DebugCompletion;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteProvider;
import dtm.stools.component.panels.editor.code.autocomplete.CompletionContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class CSharpConditionAutoCompleteProvider implements AutoCompleteProvider {

    private static final List<String> KEYWORDS = List.of(
            "true", "false", "null", "this", "new", "is", "as", "not", "and", "or",
            "typeof", "nameof", "default", "string", "int", "long", "double", "decimal",
            "bool", "char", "byte", "float", "object"
    );
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int MAX_SOURCE_IDENTIFIERS = 500;

    private final Set<String> sourceIdentifiers;
    private final BiFunction<String, Integer, List<DebugCompletion>> debugCompletions;

    public CSharpConditionAutoCompleteProvider(
            String sourceText,
            BiFunction<String, Integer, List<DebugCompletion>> debugCompletions
    ) {
        this.sourceIdentifiers = extractIdentifiers(sourceText);
        this.debugCompletions = debugCompletions;
    }

    @Override
    public List<AutoCompleteItem> getSuggestions(CompletionContext context) {
        if (context == null) {
            return List.of();
        }
        String prefix = context.prefix() == null ? "" : context.prefix();
        Map<String, AutoCompleteItem> items = new LinkedHashMap<>();
        addDebugCompletions(context, prefix, items);
        addSourceIdentifiers(prefix, items);
        addKeywords(prefix, items);
        return new ArrayList<>(items.values());
    }

    @Override
    public boolean shouldAutoTrigger(CompletionContext context) {
        if (context == null) {
            return false;
        }
        String prefix = context.prefix();
        if (prefix != null && !prefix.isBlank()) {
            return true;
        }
        int offset = context.caretOffset();
        if (context.buffer() == null || offset <= 0 || offset > context.buffer().length()) {
            return false;
        }
        return ".".equals(context.buffer().substring(offset - 1, offset));
    }

    private void addDebugCompletions(CompletionContext context, String prefix, Map<String, AutoCompleteItem> items) {
        if (debugCompletions == null) {
            return;
        }
        try {
            String line = context.currentLine();
            if (line == null) {
                return;
            }
            List<DebugCompletion> completions = debugCompletions.apply(line, context.caretCol() + 1);
            if (completions == null) {
                return;
            }
            for (DebugCompletion completion : completions) {
                if (completion == null || completion.label() == null || completion.label().isBlank()) {
                    continue;
                }
                String insertText = completion.insertText() == null || completion.insertText().isBlank()
                        ? completion.label()
                        : completion.insertText();
                if (!prefix.isEmpty()
                        && !startsWithIgnoreCase(completion.label(), prefix)
                        && !startsWithIgnoreCase(insertText, prefix)) {
                    continue;
                }
                items.putIfAbsent(insertText, new AutoCompleteItem(insertText, completion.label(), completion.displayDetail()));
            }
        } catch (Exception e) {
            log.debug("Falha ao obter completions do debugger: {}", e.getMessage());
        }
    }

    private void addSourceIdentifiers(String prefix, Map<String, AutoCompleteItem> items) {
        for (String identifier : sourceIdentifiers) {
            if (identifier.equalsIgnoreCase(prefix)) {
                continue;
            }
            if (prefix.isEmpty() || startsWithIgnoreCase(identifier, prefix)) {
                items.putIfAbsent(identifier, new AutoCompleteItem(identifier, identifier));
            }
        }
    }

    private void addKeywords(String prefix, Map<String, AutoCompleteItem> items) {
        for (String keyword : KEYWORDS) {
            if (keyword.equalsIgnoreCase(prefix)) {
                continue;
            }
            if (prefix.isEmpty() || startsWithIgnoreCase(keyword, prefix)) {
                items.putIfAbsent(keyword, new AutoCompleteItem(keyword, keyword, "keyword"));
            }
        }
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value != null && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static Set<String> extractIdentifiers(String sourceText) {
        Set<String> identifiers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (sourceText == null || sourceText.isBlank()) {
            return identifiers;
        }
        Set<String> keywordSet = Set.copyOf(KEYWORDS);
        Matcher matcher = IDENTIFIER.matcher(sourceText);
        while (matcher.find() && identifiers.size() < MAX_SOURCE_IDENTIFIERS) {
            String identifier = matcher.group();
            if (identifier.length() < 2 || keywordSet.contains(identifier.toLowerCase(Locale.ROOT))) {
                continue;
            }
            identifiers.add(identifier);
        }
        return identifiers;
    }
}
