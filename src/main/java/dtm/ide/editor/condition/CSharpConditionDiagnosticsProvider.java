package dtm.ide.editor.condition;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticsContext;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticsProvider;
import dtm.stools.component.panels.editor.code.prototype.TextBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class CSharpConditionDiagnosticsProvider implements DiagnosticsProvider {

    private static final String SOURCE = "dotnet-condition";

    private record OpenDelimiter(char symbol, int offset) {
    }

    @Override
    public List<Diagnostic> getDiagnostics(DiagnosticsContext context) {
        TextBuffer buffer = context == null ? null : context.buffer();
        if (buffer == null || buffer.isEmpty()) {
            return List.of();
        }

        String text = buffer.substring(0, buffer.length());
        if (text.isBlank()) {
            return List.of();
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        Deque<OpenDelimiter> delimiters = new ArrayDeque<>();
        boolean hasExpressionContent = false;
        int length = text.length();
        int i = 0;

        while (i < length) {
            char c = text.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (c == '/' && i + 1 < length && text.charAt(i + 1) == '/') {
                i = endOfLine(text, i);
                continue;
            }

            if (c == '/' && i + 1 < length && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) {
                    diagnostics.add(error(buffer, i, length, "Comentário de bloco não terminado."));
                    break;
                }
                i = end + 2;
                continue;
            }

            if (c == '\'') {
                int end = scanCharLiteral(text, i);
                if (end < 0) {
                    diagnostics.add(error(buffer, i, endOfLine(text, i), "Literal de caractere não terminado."));
                    i = endOfLine(text, i);
                    continue;
                }
                hasExpressionContent = true;
                i = end;
                continue;
            }

            if (isVerbatimStringStart(text, i)) {
                int quote = text.indexOf('"', i);
                int end = scanVerbatimString(text, quote);
                if (end < 0) {
                    diagnostics.add(error(buffer, i, length, "String verbatim não terminada."));
                    break;
                }
                hasExpressionContent = true;
                i = end;
                continue;
            }

            if (c == '"' || (c == '$' && i + 1 < length && text.charAt(i + 1) == '"')) {
                int quote = c == '"' ? i : i + 1;
                int end = scanRegularString(text, quote);
                if (end < 0) {
                    diagnostics.add(error(buffer, i, endOfLine(text, i), "String não terminada."));
                    i = endOfLine(text, i);
                    continue;
                }
                hasExpressionContent = true;
                i = end;
                continue;
            }

            if (c == '(' || c == '[' || c == '{') {
                delimiters.push(new OpenDelimiter(c, i));
                i++;
                continue;
            }

            if (c == ')' || c == ']' || c == '}') {
                if (delimiters.isEmpty() || delimiters.peek().symbol() != openerOf(c)) {
                    diagnostics.add(error(buffer, i, i + 1, "Delimitador '" + c + "' sem abertura correspondente."));
                } else {
                    delimiters.pop();
                }
                i++;
                continue;
            }

            if (c == ';') {
                diagnostics.add(error(buffer, i, i + 1, "A condição deve ser uma única expressão C#, sem ';'."));
                i++;
                continue;
            }

            if (c == '=') {
                if (i + 1 < length && (text.charAt(i + 1) == '=' || text.charAt(i + 1) == '>')) {
                    hasExpressionContent = true;
                    i += 2;
                    continue;
                }
                if (i > 0 && isComparisonPrefix(text.charAt(i - 1)) && !isShiftAssignment(text, i)) {
                    hasExpressionContent = true;
                    i++;
                    continue;
                }
                int start = assignmentStart(text, i);
                diagnostics.add(error(buffer, start, i + 1,
                        "Atribuição não é permitida na condição. Use '==' para comparação."));
                i++;
                continue;
            }

            hasExpressionContent = true;
            i++;
        }

        while (!delimiters.isEmpty()) {
            OpenDelimiter open = delimiters.pop();
            diagnostics.add(error(buffer, open.offset(), open.offset() + 1,
                    "Delimitador '" + open.symbol() + "' não fechado."));
        }

        if (diagnostics.isEmpty() && !hasExpressionContent) {
            diagnostics.add(new Diagnostic(0, 0, 0, 0, DiagnosticSeverity.WARNING,
                    "A condição contém apenas comentários; o breakpoint será tratado como incondicional.",
                    SOURCE, null));
        }

        return diagnostics;
    }

    private static Diagnostic error(TextBuffer buffer, int startOffset, int endOffset, String message) {
        return Diagnostic.ofOffset(buffer, startOffset, endOffset, DiagnosticSeverity.ERROR, message, SOURCE);
    }

    private static int endOfLine(String text, int from) {
        int end = text.indexOf('\n', from);
        return end < 0 ? text.length() : end;
    }

    private static boolean isVerbatimStringStart(String text, int i) {
        char c = text.charAt(i);
        if (c == '@') {
            if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                return true;
            }
            return i + 2 < text.length() && text.charAt(i + 1) == '$' && text.charAt(i + 2) == '"';
        }
        if (c == '$') {
            return i + 2 < text.length() && text.charAt(i + 1) == '@' && text.charAt(i + 2) == '"';
        }
        return false;
    }

    private static int scanCharLiteral(String text, int start) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n') {
                return -1;
            }
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\'') {
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    private static int scanRegularString(String text, int quote) {
        int i = quote + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n') {
                return -1;
            }
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    private static int scanVerbatimString(String text, int quote) {
        if (quote < 0) {
            return -1;
        }
        int i = quote + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    private static char openerOf(char closer) {
        return switch (closer) {
            case ')' -> '(';
            case ']' -> '[';
            default -> '{';
        };
    }

    private static boolean isComparisonPrefix(char c) {
        return c == '!' || c == '<' || c == '>';
    }

    private static boolean isShiftAssignment(String text, int i) {
        if (i < 2) {
            return false;
        }
        char previous = text.charAt(i - 1);
        return (previous == '<' || previous == '>') && text.charAt(i - 2) == previous;
    }

    private static int assignmentStart(String text, int i) {
        if (i >= 2) {
            String prefix = text.substring(i - 2, i);
            if ("<<".equals(prefix) || ">>".equals(prefix) || "??".equals(prefix)) {
                return i - 2;
            }
        }
        if (i >= 1 && "+-*/%&|^".indexOf(text.charAt(i - 1)) >= 0) {
            return i - 1;
        }
        return i;
    }
}
