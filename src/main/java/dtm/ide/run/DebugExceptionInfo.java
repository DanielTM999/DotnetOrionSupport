package dtm.ide.run;

public record DebugExceptionInfo(String typeName, String message, String description,
                                 String stackTrace, String breakMode) {

    public boolean hasContent() {
        return hasText(typeName) || hasText(message) || hasText(description) || hasText(stackTrace);
    }

    public String title() {
        return hasText(typeName) ? typeName : "Exception";
    }

    public String summary() {
        if (hasText(message)) {
            return message;
        }
        return hasText(description) ? description : "Excecao nao tratada.";
    }

    public String copyText() {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, title());
        appendLine(sb, summary());
        if (hasText(breakMode)) {
            appendLine(sb, "Break mode: " + breakMode);
        }
        if (hasText(stackTrace)) {
            if (!sb.isEmpty()) {
                sb.append(System.lineSeparator());
            }
            sb.append(stackTrace);
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String text) {
        if (!hasText(text)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(System.lineSeparator());
        }
        sb.append(text);
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
