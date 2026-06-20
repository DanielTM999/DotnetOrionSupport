package dtm.ide.nuget;

import java.util.Locale;

public record NuGetSource(String name, String url, boolean enabled) {

    public static final String NUGET_ORG_NAME = "nuget.org";
    public static final String NUGET_ORG_URL = "https://api.nuget.org/v3/index.json";

    public static NuGetSource nugetOrg() {
        return new NuGetSource(NUGET_ORG_NAME, NUGET_ORG_URL, true);
    }

    public boolean isV3() {
        return url != null && url.toLowerCase().endsWith("index.json");
    }

    public NuGetSource enabled(boolean value) {
        return new NuGetSource(name, url, value);
    }

    public boolean isNugetOrg() {
        return isNugetOrg(name, url);
    }

    public static boolean isNugetOrg(String name, String url) {
        return NUGET_ORG_NAME.equalsIgnoreCase(safe(name))
                || NUGET_ORG_URL.equalsIgnoreCase(safe(url));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
