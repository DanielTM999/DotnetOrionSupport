package dtm.ide.nuget.models;

import java.util.List;

public record NuGetPackage(
        String id,
        String title,
        String version,
        String description,
        String authors,
        long totalDownloads,
        String iconUrl,
        List<String> versions,
        boolean verified
) {
    public String displayTitle() {
        return title == null || title.isBlank() ? id : title;
    }

    public NuGetPackage withVersions(List<String> resolvedVersions) {
        return new NuGetPackage(id, title, version, description, authors, totalDownloads,
                iconUrl, resolvedVersions, verified);
    }
}
