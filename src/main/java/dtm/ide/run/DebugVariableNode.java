package dtm.ide.run;

import javax.swing.tree.DefaultMutableTreeNode;

final class DebugVariableNode extends DefaultMutableTreeNode {

    private final String name;
    private final String value;
    private final String type;
    private final int variablesReference;
    private final boolean scope;
    private final boolean placeholder;
    private boolean loaded;

    private DebugVariableNode(String name, String value, String type, int variablesReference,
                             boolean scope, boolean placeholder) {
        this.name = name;
        this.value = value;
        this.type = type;
        this.variablesReference = variablesReference;
        this.scope = scope;
        this.placeholder = placeholder;
    }

    static DebugVariableNode scope(DebugScope scope) {
        return new DebugVariableNode(scope.name(), "", "", scope.variablesReference(), true, false);
    }

    static DebugVariableNode variable(DebugVar var) {
        return new DebugVariableNode(var.name(), var.value(), var.type(), var.variablesReference(), false, false);
    }

    static DebugVariableNode placeholder() {
        return new DebugVariableNode("", "", "", 0, false, true);
    }

    boolean isPlaceholder() {
        return placeholder;
    }

    DebugVar asVar() {
        return new DebugVar(name, value, type, variablesReference);
    }

    String name() {
        return name;
    }

    String value() {
        return value;
    }

    String type() {
        return type;
    }

    int variablesReference() {
        return variablesReference;
    }

    boolean isScope() {
        return scope;
    }

    boolean expandable() {
        return variablesReference > 0;
    }

    boolean isLoaded() {
        return loaded;
    }

    void markLoaded() {
        this.loaded = true;
    }
}
