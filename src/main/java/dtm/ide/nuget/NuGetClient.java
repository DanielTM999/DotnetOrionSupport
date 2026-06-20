package dtm.ide.nuget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.nuget.models.NuGetPackage;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public final class NuGetClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    private final NuGetSource source;
    private final HttpClient http;
    private final AtomicReference<Resources> resources = new AtomicReference<>();
    private final Map<String, Cached<List<NuGetPackage>>> searchCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<String>>> versionsCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<String>> iconUrlCache = new ConcurrentHashMap<>();

    private record Cached<T>(T value, long timestamp) {
        boolean fresh() {
            return System.currentTimeMillis() - timestamp < CACHE_TTL_MILLIS;
        }
    }

    public NuGetClient(NuGetSource source) {
        this.source = source;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public NuGetSource source() {
        return source;
    }

    public List<NuGetPackage> search(String query, boolean includePrerelease, int skip, int take) {
        String key = (query == null ? "" : query) + "|" + includePrerelease + "|" + skip + "|" + take;
        Cached<List<NuGetPackage>> cached = searchCache.get(key);
        if (cached != null && cached.fresh()) {
            return cached.value();
        }
        try {
            String searchService = resources().searchQueryService;
            if (searchService == null) {
                return Collections.emptyList();
            }
            String url = searchService
                    + (searchService.contains("?") ? "&" : "?")
                    + "q=" + encode(query == null ? "" : query)
                    + "&skip=" + Math.max(0, skip)
                    + "&take=" + Math.max(1, take)
                    + "&prerelease=" + includePrerelease
                    + "&semVerLevel=2.0.0";
            JsonNode root = getJson(url);
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            List<NuGetPackage> out = new ArrayList<>(data.size());
            for (JsonNode node : data) {
                out.add(parsePackage(node));
            }
            searchCache.put(key, new Cached<>(out, System.currentTimeMillis()));
            return out;
        } catch (Exception e) {
            log.warn("Falha na busca NuGet '{}': {}", query, e.getMessage());
            return cached != null ? cached.value() : Collections.emptyList();
        }
    }

    public List<String> versions(String packageId, boolean includePrerelease) {
        if (packageId == null) {
            return Collections.emptyList();
        }
        String key = packageId.toLowerCase(Locale.ROOT) + "|" + includePrerelease;
        Cached<List<String>> cached = versionsCache.get(key);
        if (cached != null && cached.fresh()) {
            return cached.value();
        }
        try {
            String base = resources().packageBaseAddress;
            if (base == null) {
                return Collections.emptyList();
            }
            String idLower = packageId.toLowerCase(Locale.ROOT);
            String url = trailingSlash(base) + idLower + "/index.json";
            JsonNode root = getJson(url);
            JsonNode versionsNode = root == null ? null : root.get("versions");
            if (versionsNode == null || !versionsNode.isArray()) {
                return Collections.emptyList();
            }
            List<String> versions = new ArrayList<>(versionsNode.size());
            for (JsonNode v : versionsNode) {
                String value = v.asText("");
                if (value.isBlank()) {
                    continue;
                }
                if (!includePrerelease && value.contains("-")) {
                    continue;
                }
                versions.add(value);
            }
            Collections.reverse(versions);
            versionsCache.put(key, new Cached<>(versions, System.currentTimeMillis()));
            return versions;
        } catch (Exception e) {
            log.warn("Falha ao listar versões de {}: {}", packageId, e.getMessage());
            return cached != null ? cached.value() : Collections.emptyList();
        }
    }

    public String iconUrl(String packageId) {
        if (packageId == null || packageId.isBlank()) {
            return null;
        }
        String key = packageId.toLowerCase(Locale.ROOT);
        Cached<String> cached = iconUrlCache.get(key);
        if (cached != null && cached.fresh()) {
            return cached.value();
        }
        String url = resolveIconUrl(packageId);
        iconUrlCache.put(key, new Cached<>(url, System.currentTimeMillis()));
        return url;
    }

    private String resolveIconUrl(String packageId) {
        for (NuGetPackage pkg : search(packageId, true, 0, 20)) {
            if (packageId.equalsIgnoreCase(pkg.id())) {
                return pkg.iconUrl();
            }
        }
        return null;
    }

    public String latestVersion(String packageId, boolean includePrerelease) {
        List<String> versions = versions(packageId, includePrerelease);
        return versions.isEmpty() ? null : versions.get(0);
    }

    public Path download(String packageId, String version, Path destDir) throws Exception {
        String base = resources().packageBaseAddress;
        if (base == null) {
            throw new IllegalStateException("Feed sem PackageBaseAddress (flat container).");
        }
        String idLower = packageId.toLowerCase(Locale.ROOT);
        String verLower = version.toLowerCase(Locale.ROOT);
        String url = trailingSlash(base) + idLower + "/" + verLower + "/"
                + idLower + "." + verLower + ".nupkg";
        Files.createDirectories(destDir);
        Path target = destDir.resolve(packageId + "." + version + ".nupkg");
        Files.deleteIfExists(target);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "DotnetOrionSupport/1.0")
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<Path> response = http.send(request,
                HttpResponse.BodyHandlers.ofFile(target,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(target);
            throw new IllegalStateException("Download do .nupkg falhou (HTTP " + response.statusCode() + "): " + url);
        }
        return target;
    }

    private Resources resources() throws Exception {
        Resources cached = resources.get();
        if (cached != null) {
            return cached;
        }
        Resources resolved = resolveResources();
        resources.set(resolved);
        return resolved;
    }

    private Resources resolveResources() throws Exception {
        Resources result = new Resources();
        if (source == null || source.url() == null || !source.isV3()) {
            return result;
        }
        JsonNode root = getJson(source.url());
        JsonNode resourcesNode = root == null ? null : root.get("resources");
        if (resourcesNode == null || !resourcesNode.isArray()) {
            return result;
        }
        for (JsonNode node : resourcesNode) {
            String type = node.path("@type").asText("");
            String id = node.path("@id").asText("");
            if (id.isBlank()) {
                continue;
            }
            if (result.searchQueryService == null && type.startsWith("SearchQueryService")) {
                result.searchQueryService = id;
            } else if (result.packageBaseAddress == null && type.startsWith("PackageBaseAddress")) {
                result.packageBaseAddress = id;
            } else if (result.registrationsBaseUrl == null && type.startsWith("RegistrationsBaseUrl")) {
                result.registrationsBaseUrl = id;
            }
        }
        return result;
    }

    private static NuGetPackage parsePackage(JsonNode node) {
        String id = node.path("id").asText("");
        String version = node.path("version").asText("");
        String description = node.path("description").asText("");
        String title = node.path("title").asText("");
        String iconUrl = node.path("iconUrl").asText("");
        long downloads = node.path("totalDownloads").asLong(0);
        boolean verified = node.path("verified").asBoolean(false);
        String authors = parseAuthors(node.get("authors"));
        List<String> versions = new ArrayList<>();
        JsonNode versionsNode = node.get("versions");
        if (versionsNode != null && versionsNode.isArray()) {
            for (JsonNode v : versionsNode) {
                String value = v.path("version").asText("");
                if (!value.isBlank()) {
                    versions.add(value);
                }
            }
            Collections.reverse(versions);
        }
        return new NuGetPackage(id, title, version, description, authors, downloads,
                iconUrl.isBlank() ? null : iconUrl, versions, verified);
    }

    private static String parseAuthors(JsonNode authors) {
        if (authors == null || authors.isNull()) {
            return "";
        }
        if (authors.isArray()) {
            List<String> names = new ArrayList<>();
            for (JsonNode a : authors) {
                String value = a.asText("");
                if (!value.isBlank()) {
                    names.add(value);
                }
            }
            return String.join(", ", names);
        }
        return authors.asText("");
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "DotnetOrionSupport/1.0")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " em " + url);
        }
        return MAPPER.readTree(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private static final class Resources {
        private volatile String searchQueryService;
        private volatile String packageBaseAddress;
        private volatile String registrationsBaseUrl;
    }

    private static final ConcurrentHashMap<String, NuGetClient> CLIENT_CACHE = new ConcurrentHashMap<>();

    public static NuGetClient forSource(NuGetSource source) {
        if (source == null || source.url() == null) {
            return new NuGetClient(NuGetSource.nugetOrg());
        }
        return CLIENT_CACHE.computeIfAbsent(source.url(), k -> new NuGetClient(source));
    }
}
