package dtm.ide.iis;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisAppPoolConfigTest {

    private IisAppPool pool(Map<String, String> raw) {
        return new IisAppPool("DefaultAppPool", "Started", "v4.0", "Integrated",
                "ApplicationPoolIdentity", "", false, "OnDemand", "true",
                1000, 20, 1, 1740, 0, 0, raw);
    }

    @Test
    void exposesTheSameCategoriesAsIisManager() {
        List<String> categories = IisAppPoolConfig.categories();

        assertEquals(List.of(IisAppPoolConfig.CATEGORY_GENERAL,
                IisAppPoolConfig.CATEGORY_CPU,
                IisAppPoolConfig.CATEGORY_PROCESS_MODEL,
                IisAppPoolConfig.CATEGORY_ORPHANING,
                IisAppPoolConfig.CATEGORY_RAPID_FAIL,
                IisAppPoolConfig.CATEGORY_RECYCLING), categories);
    }

    @Test
    void everyPropertyHasLabelAndDescription() {
        for (IisAppPoolConfig.Property property : IisAppPoolConfig.properties()) {
            assertTrue(!property.label().isBlank(), property.name() + " precisa de rótulo");
            assertTrue(property.description().startsWith("[" + property.name() + "]"),
                    property.name() + " precisa de descrição iniciando pelo atributo");
        }
    }

    @Test
    void readsValuesFromRawPoolAttributes() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("processModel.idleTimeout", "00:20:00");
        raw.put("processModel.maxProcesses", "4");
        raw.put("failure.rapidFailProtectionMaxCrashes", "7");
        raw.put("cpu.limit", "85000");

        Map<String, String> values = IisAppPoolConfig.read(pool(raw));

        assertEquals("00:20:00", values.get("processModel.idleTimeout"));
        assertEquals("4", values.get("processModel.maxProcesses"));
        assertEquals("7", values.get("failure.rapidFailProtectionMaxCrashes"));
        assertEquals("85000", values.get("cpu.limit"));
    }

    @Test
    void fallsBackToPoolBasicsWhenAttributeIsAbsent() {
        Map<String, String> values = IisAppPoolConfig.read(pool(Map.of()));

        assertEquals("DefaultAppPool", values.get("name"));
        assertEquals("v4.0", values.get("managedRuntimeVersion"));
        assertEquals("Integrated", values.get("managedPipelineMode"));
        assertEquals("ApplicationPoolIdentity", values.get("processModel.identityType"));
        assertEquals("OnDemand", values.get("startMode"));
    }

    @Test
    void readReturnsEmptyForNullPool() {
        assertTrue(IisAppPoolConfig.read(null).isEmpty());
    }

    @Test
    void applyIsNoOpWhenNothingChanged() {
        Map<String, String> current = IisAppPoolConfig.read(pool(Map.of()));

        IisService.Result result = IisAppPoolConfig.apply("DefaultAppPool", current,
                new LinkedHashMap<>(current));

        assertTrue(result.success());
        assertEquals("", result.message());
    }

    @Test
    void propertiesCoverTheAdvancedDialogSurface() {
        List<String> names = IisAppPoolConfig.properties().stream()
                .map(IisAppPoolConfig.Property::name)
                .toList();

        assertTrue(names.contains("enable32BitAppOnWin64"));
        assertTrue(names.contains("cpu.action"));
        assertTrue(names.contains("cpu.smpProcessorAffinityMask2"));
        assertTrue(names.contains("processModel.idleTimeoutAction"));
        assertTrue(names.contains("processModel.loadUserProfile"));
        assertTrue(names.contains("failure.orphanWorkerProcess"));
        assertTrue(names.contains("failure.rapidFailProtection"));
        assertTrue(names.contains("failure.loadBalancerCapabilities"));
        assertTrue(names.contains("recycling.disallowOverlappingRotation"));
        assertTrue(names.contains("recycling.periodicRestart.requests"));
    }
}
