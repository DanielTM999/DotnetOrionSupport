package dtm.ide.run;

public record DebugVar(String name, String value, String type, int variablesReference) {

    public boolean expandable() {
        return variablesReference > 0;
    }
}
