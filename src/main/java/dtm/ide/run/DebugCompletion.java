package dtm.ide.run;

public record DebugCompletion(String label, String insertText, String detail, String kind,
                              int start, int length) {

    public String displayDetail() {
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        return kind == null ? "" : kind;
    }
}
