package dtm.ide.iis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisModelTest {

    @Test
    void parsesMultipleBindings() {
        List<IisBinding> bindings = IisBinding.parse("http/*:80:,https/*:443:localhost");

        assertEquals(2, bindings.size());
        assertEquals("http", bindings.get(0).protocol());
        assertEquals("80", bindings.get(0).port());
        assertEquals("", bindings.get(0).hostName());
        assertEquals("https", bindings.get(1).protocol());
        assertEquals("localhost", bindings.get(1).hostName());
    }

    @Test
    void buildsUrlOmittingDefaultPort() {
        assertEquals("http://localhost", new IisBinding("http", "*", "80", "localhost").url());
        assertEquals("https://localhost", new IisBinding("https", "*", "443", "localhost").url());
        assertEquals("http://localhost:5000", new IisBinding("http", "*", "5000", "localhost").url());
    }

    @Test
    void bindingDescriptorRoundTrips() {
        IisBinding binding = new IisBinding("http", "*", "8080", "localhost");
        IisBinding parsed = IisBinding.parseSingle(binding.descriptor());

        assertEquals(binding, parsed);
    }

    @Test
    void parsesEmptyBindingsAsEmptyList() {
        assertTrue(IisBinding.parse(null).isEmpty());
        assertTrue(IisBinding.parse("").isEmpty());
        assertNull(IisBinding.parseSingle(null));
    }

    @Test
    void sitePicksHttpBindingForBrowsing() {
        IisSite site = new IisSite("Site", "1", "Started", List.of(
                new IisBinding("https", "*", "443", "localhost"),
                new IisBinding("http", "*", "8080", "localhost")), "C:\\web", "Pool");

        assertEquals("http://localhost:8080", site.browseUrl());
        assertTrue(site.started());
    }

    @Test
    void normalizesApplicationPaths() {
        assertEquals("/", IisService.normalizePath(null));
        assertEquals("/", IisService.normalizePath(""));
        assertEquals("/", IisService.normalizePath("/"));
        assertEquals("/app", IisService.normalizePath("app"));
        assertEquals("/app", IisService.normalizePath("/app/"));
        assertEquals("/app/sub", IisService.normalizePath("\\app\\sub"));
    }

    @Test
    void suggestsSafeIdentifiers() {
        assertEquals("My_AppAppPool", IisService.suggestAppPoolName("My@App"));
        assertEquals("My App", IisService.suggestSiteName("My App"));
    }

    @Test
    void convertsTimeSpansBothWays() {
        assertEquals(20, AppCmd.parseTimeSpanMinutes("00:20:00", 0));
        assertEquals(1740, AppCmd.parseTimeSpanMinutes("1.05:00:00", 0));
        assertEquals(90, AppCmd.parseTimeSpanMinutes("01:30:00", 0));
        assertEquals(7, AppCmd.parseTimeSpanMinutes("invalid", 7));

        assertEquals("00:20:00", AppCmd.toTimeSpan(20));
        assertEquals("1.05:00:00", AppCmd.toTimeSpan(1740));
        assertEquals("00:00:00", AppCmd.toTimeSpan(-5));
    }

    @Test
    void poolReportsNoManagedCode() {
        IisAppPool pool = new IisAppPool("Pool", "Started", "", "Integrated", "ApplicationPoolIdentity",
                "", false, "OnDemand", "true", 1000, 20, 1, 1740, 0, 0, java.util.Map.of());

        assertTrue(pool.noManagedCode());
        assertEquals("No Managed Code", pool.runtimeLabel());
        assertEquals("ApplicationPoolIdentity", pool.identityLabel());
    }

    @Test
    void deploymentTargetBuildsApplicationNameAndUrl() {
        IisDeployment.Target target = new IisDeployment.Target("Default Web Site", "/shop",
                "ShopAppPool", java.nio.file.Path.of("C:\\web\\shop"),
                new IisBinding("http", "*", "80", "localhost"), true);

        assertEquals("Default Web Site/shop", target.applicationName());
        assertEquals("http://localhost/shop", target.url());
        assertTrue(!target.rootApplication());
    }
}
