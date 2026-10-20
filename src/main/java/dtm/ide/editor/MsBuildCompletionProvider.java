package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class MsBuildCompletionProvider {
    private static final Map<String, List<String>> CHILDREN = Map.of(
            "", List.of("Project"),
            "Project", List.of("PropertyGroup", "ItemGroup", "Import", "Target", "Choose", "ItemDefinitionGroup"),
            "PropertyGroup", List.of("TargetFramework", "TargetFrameworks", "OutputType", "Nullable", "ImplicitUsings",
                    "LangVersion", "RootNamespace", "AssemblyName", "TreatWarningsAsErrors", "NoWarn", "RuntimeIdentifier",
                    "RuntimeIdentifiers", "IsPackable", "IsTestProject", "PackageId", "Version", "ManagePackageVersionsCentrally"),
            "ItemGroup", List.of("PackageReference", "PackageVersion", "ProjectReference", "Reference", "Compile", "Content", "None", "EmbeddedResource"),
            "Target", List.of("Exec", "Message", "Copy", "Delete", "MakeDir", "MSBuild", "PropertyGroup", "ItemGroup"),
            "Choose", List.of("When", "Otherwise"));
    private static final Pattern TAG = Pattern.compile("<(/?)([\\w.:-]+)\\b[^>]*?(/?)>");

    public List<AutoCompleteItem> suggestions(String source, int caret) {
        if (source == null || caret < 0 || caret > source.length()) return List.of();
        String before = source.substring(0, caret);
        int open = before.lastIndexOf('<');
        if (open < 0 || before.indexOf('>', open) >= 0) return List.of();
        int comment = before.lastIndexOf("<!--");
        if (comment >= 0 && before.indexOf("-->", comment) < 0) return List.of();
        String prefix = before.substring(open + 1);
        var stack = new ArrayDeque<String>();
        var matcher = TAG.matcher(before.substring(0, open).replaceAll("(?s)<!--.*?-->", ""));
        while (matcher.find()) {
            if (!matcher.group(1).isEmpty()) { if (!stack.isEmpty() && stack.peek().equals(matcher.group(2))) stack.pop(); }
            else if (matcher.group(3).isEmpty()) stack.push(matcher.group(2));
        }
        if (prefix.startsWith("/")) {
            String parent = stack.isEmpty() ? "" : stack.peek();
            return !parent.isEmpty() && parent.startsWith(prefix.substring(1))
                    ? List.of(AutoCompleteItem.snippet(parent, parent + ">", "MSBuild")) : List.of();
        }
        if (!prefix.matches("[\\w.]*")) return List.of();
        return CHILDREN.getOrDefault(stack.isEmpty() ? "" : stack.peek(), List.of()).stream()
                .filter(name -> name.startsWith(prefix)).map(name -> AutoCompleteItem.snippet(name,
                        switch (name) {
                            case "PackageReference", "PackageVersion" -> name + " Include=\"${1:PackageId}\" Version=\"${2:1.0.0}\" />$0";
                            case "ProjectReference" -> name + " Include=\"${1:../Project/Project.csproj}\" />$0";
                            default -> name + ">$0</" + name + ">";
                        }, "MSBuild")).toList();
    }
}
