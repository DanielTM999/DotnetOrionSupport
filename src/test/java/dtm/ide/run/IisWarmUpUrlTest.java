package dtm.ide.run;

import dtm.ide.iis.IisBinding;
import dtm.ide.iis.IisWarmUp;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IisWarmUpUrlTest {

    private static IisLaunchRequest request(String applicationPath, String launchUrl) {
        return IisLaunchRequest.builder()
                .projectFile(Path.of("C:", "src", "App", "App.csproj"))
                .applicationPath(applicationPath)
                .launchUrl(launchUrl)
                .bindings(List.of(new IisBinding("http", "*", "80", "")))
                .build();
    }

    @Test
    void applicationUrlKeepsTrailingSlashSoIisDoesNotRedirect() {
        assertEquals("http://localhost/TrendsAi.WebSite/",
                request("/TrendsAi.WebSite", null).resolveUrl());
    }

    @Test
    void rootApplicationIsNotSuffixed() {
        assertEquals("http://localhost", request("/", null).resolveUrl());
    }

    @Test
    void launchUrlWins() {
        assertEquals("http://localhost/TrendsAi.WebSite/Home/Index",
                request("/TrendsAi.WebSite", "Home/Index").resolveUrl());
    }

    @Test
    void relativeLocationIsResolvedAgainstCurrentUrl() {
        assertEquals("http://localhost/TrendsAi.WebSite/",
                IisWarmUp.resolveLocation("http://localhost/TrendsAi.WebSite", "/TrendsAi.WebSite/"));
    }

    @Test
    void absoluteLocationReplacesCurrentUrl() {
        assertEquals("https://localhost/TrendsAi.WebSite/",
                IisWarmUp.resolveLocation("http://localhost/TrendsAi.WebSite/",
                        "https://localhost/TrendsAi.WebSite/"));
    }
}
