package dtm.ide.aspnet;

import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.editor.CSharpLexical;
import dtm.ide.editor.CSharpLexical.Token;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A bounded source index. Dynamic registrations/routes remain explicitly unresolved. */
public final class AspNetIndex {
    public enum Kind { ENDPOINT, SERVICE, CONFIGURATION, CONFIGURATION_USE, PROFILE, UNRESOLVED }
    public record Entry(Kind kind, String name, Path file, int line, String detail) {}
    public record Snapshot(List<Entry> entries, List<String> warnings) {}
    private static final Set<String> SKIP = Set.of("bin", "obj", "node_modules", "target", "packages", "vendor");
    private static final Set<String> MAPS = Set.of("MapGet", "MapPost", "MapPut", "MapDelete", "MapPatch", "MapMethods", "MapGroup");
    private static final Set<String> SERVICES = Set.of("AddSingleton", "AddScoped", "AddTransient", "TryAddSingleton", "TryAddScoped", "TryAddTransient");
    private static final Set<String> HTTP = Set.of("HttpGet", "HttpPost", "HttpPut", "HttpDelete", "HttpPatch", "HttpHead", "HttpOptions");

    public Snapshot scan(Path workspace, Map<Path, String> openDocuments) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int[] visited = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                String name = dir.getFileName().toString();
                return !dir.equals(root) && (name.startsWith(".") || SKIP.contains(name.toLowerCase(java.util.Locale.ROOT)))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                if (++visited[0] > 10000) { warnings.add("Index limited to 10000 files."); return FileVisitResult.TERMINATE; }
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                String name = file.getFileName().toString();
                if (!(name.endsWith(".cs") || name.endsWith(".csproj") || name.matches("appsettings(?:\\..+)?\\.json"))) return FileVisitResult.CONTINUE;
                if (attrs.size() > 2_000_000) { warnings.add("Skipped large file: " + file); return FileVisitResult.CONTINUE; }
                try {
                    if (name.endsWith(".csproj")) {
                        for (var profile : dtm.ide.run.LaunchSettings.read(file)) {
                            Path settings = dtm.ide.run.LaunchSettings.locate(file);
                            if (settings != null && Files.isRegularFile(settings)) entries.add(new Entry(Kind.PROFILE, profile.name(), settings, 0,
                                    profile.commandName() + " — " + java.util.Objects.requireNonNullElse(profile.applicationUrl(), "")));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    String source = openDocuments.get(file.toAbsolutePath().normalize());
                    if (source == null) source = Files.readString(file);
                    entries.addAll(name.endsWith(".cs") ? sourceEntries(file, source) : configuration(file, source));
                } catch (IOException e) { warnings.add(file + ": " + e.getMessage()); }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                warnings.add(file + ": " + e.getMessage()); return FileVisitResult.CONTINUE;
            }
        });
        return new Snapshot(List.copyOf(entries), List.copyOf(warnings));
    }

    public List<Entry> sourceEntries(Path file, String source) {
        List<Entry> entries = new ArrayList<>();
        var tokens = CSharpLexical.tokens(source);
        var controllers = controllers(tokens);
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.literal()) continue;
            String name = token.text();
            if (MAPS.contains(name) && is(tokens, i - 1, ".") && is(tokens, i + 1, "(")) {
                Token argument = at(tokens, i + 2);
                if (argument == null) continue;
                boolean literal = argument.literal() && !argument.dynamic() && (is(tokens, i + 3, ",") || is(tokens, i + 3, ")"));
                String receiver = i >= 2 ? tokens.get(i - 2).text() : "";
                // A group receiver is shown explicitly: do not invent a full composed route.
                boolean knownReceiver = receiver.equals("app") || receiver.equals("endpoints");
                entries.add(new Entry(literal && knownReceiver ? Kind.ENDPOINT : Kind.UNRESOLVED,
                        name.substring(3).toUpperCase(java.util.Locale.ROOT) + " " + (literal ? argument.text() : "<dynamic>"), file,
                        CSharpLexical.line(source, token.start()), receiver + "." + name + (!literal ? " — route not resolved"
                                : !knownReceiver ? " — route fragment; receiver/group prefix not resolved" : "")));
            }
            if (SERVICES.contains(name) && is(tokens, i - 1, ".")) {
                int end = i + 1;
                String registration = "<dynamic>";
                if (is(tokens, end, "<")) {
                    int depth = 0;
                    StringBuilder types = new StringBuilder();
                    for (; end < tokens.size() && end < i + 100; end++) {
                        String part = tokens.get(end).text();
                        if (part.equals("<")) { if (depth++ > 0) types.append(part); }
                        else if (part.equals(">")) { if (--depth == 0) break; types.append(part); }
                        else types.append(part);
                    }
                    if (depth == 0) registration = types.toString();
                }
                entries.add(new Entry(registration.equals("<dynamic>") ? Kind.UNRESOLVED : Kind.SERVICE,
                        registration, file, CSharpLexical.line(source, token.start()), name));
            }
            if (HTTP.contains(name) && is(tokens, i - 1, "[")) {
                Token argument = is(tokens, i + 1, "(") ? at(tokens, i + 2) : null;
                boolean resolved = argument != null && argument.literal() && !argument.dynamic() && is(tokens, i + 3, ")");
                boolean empty = is(tokens, i + 1, "]") || is(tokens, i + 1, "(") && is(tokens, i + 2, ")");
                int position = i;
                var controller = controllers.stream().filter(c -> c.open() < position && c.close() > position).findFirst().orElse(null);
                String method = actionName(tokens, i);
                String route = resolved ? argument.text() : empty ? "" : null;
                if (controller != null && route != null && method != null && controller.route() != null
                        && !(route.contains("[") && !route.contains("[controller]") && !route.contains("[action]"))) {
                    String path = route.startsWith("/") || route.startsWith("~/") ? route.replaceFirst("^~", "")
                            : "/" + controller.route().replaceAll("^/+|/+$", "") + "/" + route.replaceAll("^/+", "");
                    path = path.replace("[controller]", controller.name()).replace("[action]", method).replaceAll("/{2,}", "/");
                    entries.add(new Entry(path.contains("[") || path.contains("]") ? Kind.UNRESOLVED : Kind.ENDPOINT,
                            name.substring(4).toUpperCase(java.util.Locale.ROOT) + " " + path,
                            file, CSharpLexical.line(source, token.start()), controller.name() + "." + method));
                } else entries.add(new Entry(Kind.UNRESOLVED, "[" + name + "] " + (route == null ? "<dynamic>" : route),
                        file, CSharpLexical.line(source, token.start()), "Controller route composition is not resolved"));
            }
            if ((name.equals("GetSection") || name.equals("GetConnectionString") || name.equals("GetValue")) && is(tokens, i - 1, ".")) {
                int open = i + 1;
                while (open < tokens.size() && open < i + 15 && !is(tokens, open, "(")) open++;
                Token key = at(tokens, open + 1);
                if (key != null && key.literal() && !key.dynamic()) entries.add(new Entry(Kind.CONFIGURATION_USE,
                        (name.equals("GetConnectionString") ? "ConnectionStrings:" : "") + key.text(), file,
                        CSharpLexical.line(source, token.start()), name));
            }
            if ((name.equals("Configuration") || name.equals("configuration")) && is(tokens, i + 1, "[")) {
                Token key = at(tokens, i + 2);
                if (key != null && key.literal() && !key.dynamic() && is(tokens, i + 3, "]")) entries.add(new Entry(
                        Kind.CONFIGURATION_USE, key.text(), file, CSharpLexical.line(source, key.start()), "Configuration indexer"));
            }
        }
        return List.copyOf(entries);
    }

    private record Controller(String name, String route, int open, int close) {}
    private List<Controller> controllers(List<Token> tokens) {
        List<Controller> result = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (!is(tokens, i, "class")) continue;
            String name = tokens.get(i + 1).text();
            if (!name.endsWith("Controller")) continue;
            int open = i + 2;
            while (open < tokens.size() && !is(tokens, open, "{") && !is(tokens, open, ";")) open++;
            if (open >= tokens.size() || !is(tokens, open, "{")) continue;
            int close = matching(tokens, open, "{", "}");
            if (close < 0) continue;
            int start = i - 1;
            while (start >= 0 && !is(tokens, start, "{") && !is(tokens, start, "}") && !is(tokens, start, ";")) start--;
            String route = null;
            for (int a = start + 1; a < i; a++) if (is(tokens, a, "Route") && is(tokens, a - 1, "[")
                    && is(tokens, a + 1, "(") && at(tokens, a + 2) != null && tokens.get(a + 2).literal()
                    && !tokens.get(a + 2).dynamic() && is(tokens, a + 3, ")")) {
                if (route != null) { route = null; break; }
                route = tokens.get(a + 2).text();
            }
            result.add(new Controller(name.substring(0, name.length() - "Controller".length()), route, open, close));
        }
        return result;
    }
    private static String actionName(List<Token> tokens, int attribute) {
        int end = attribute;
        while (end < tokens.size() && !is(tokens, end, "]")) end++;
        for (int i = end + 1; i < tokens.size() && i < end + 80; i++) {
            if (is(tokens, i, "[")) { int close = matching(tokens, i, "[", "]"); if (close < 0) return null; i = close; continue; }
            if (is(tokens, i, "{") || is(tokens, i, ";")) return null;
            if (is(tokens, i, "(")) return i > 0 && !tokens.get(i - 1).literal() ? tokens.get(i - 1).text() : null;
        }
        return null;
    }
    private static int matching(List<Token> tokens, int start, String open, String close) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            if (is(tokens, i, open)) depth++;
            if (is(tokens, i, close) && --depth == 0) return i;
        }
        return -1;
    }

    public List<Entry> configuration(Path file, String source) throws IOException {
        var mapper = new ObjectMapper();
        mapper.enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS);
        mapper.enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA);
        List<Entry> result = new ArrayList<>();
        try (var parser = mapper.getFactory().createParser(source)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME
                        || (parser.currentToken().isScalarValue() && parser.getParsingContext().inArray())) {
                    String pointer = parser.getParsingContext().pathAsPointer().toString();
                    String key = java.util.Arrays.stream(pointer.substring(1).split("/"))
                            .map(part -> part.replace("~1", "/").replace("~0", "~"))
                            .collect(java.util.stream.Collectors.joining(":"));
                    result.add(new Entry(Kind.CONFIGURATION, key, file, parser.currentTokenLocation().getLineNr() - 1, "appsettings"));
                }
            }
        }
        return List.copyOf(result);
    }
    private static Token at(List<Token> tokens, int i) { return i >= 0 && i < tokens.size() ? tokens.get(i) : null; }
    private static boolean is(List<Token> tokens, int i, String text) {
        var token = at(tokens, i); return token != null && !token.literal() && token.text().equals(text);
    }
}
