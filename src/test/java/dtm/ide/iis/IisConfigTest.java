package dtm.ide.iis;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisConfigTest {

    private IisConfig.Section sectionById(String id) {
        return IisConfig.catalog().stream()
                .filter(section -> section.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void catalogCoversTheRequestedFeatures() {
        List<String> ids = IisConfig.catalog().stream().map(IisConfig.Section::id).toList();

        assertTrue(ids.contains("httpErrors"), "páginas de erro");
        assertTrue(ids.contains("staticContent"), "tipos MIME");
        assertTrue(ids.contains("httpProtocol"), "cabeçalhos de resposta");
        assertTrue(ids.contains("urlCompression"), "compactação");
        assertTrue(ids.contains("caching"), "cache de saída");
        assertTrue(ids.contains("modules"), "módulos");
        assertTrue(ids.contains("handlers"), "handler mappings");
        assertTrue(ids.contains("requestFiltering"), "filtragem de solicitações");
        assertTrue(ids.contains("access"), "SSL");
    }

    private List<Map<String, String>> firstCollection(IisConfig.Section section, IisConfig.Data data) {
        return data.itemsOf(section.collections().getFirst());
    }

    @Test
    void parsesScalarsAndDefaultCollection() {
        String xml = """
                <staticContent>
                  <mimeMap fileExtension=".json" mimeType="application/json" />
                  <mimeMap fileExtension=".woff2" mimeType="font/woff2" />
                </staticContent>
                """;

        IisConfig.Section section = sectionById("staticContent");
        List<Map<String, String>> items = firstCollection(section, IisConfig.parse(xml, section));

        assertEquals(2, items.size());
        assertEquals(".json", items.get(0).get("fileExtension"));
        assertEquals("font/woff2", items.get(1).get("mimeType"));
    }

    @Test
    void parsesNamedCollection() {
        String xml = """
                <httpProtocol allowKeepAlive="true">
                  <customHeaders>
                    <add name="X-Frame-Options" value="DENY" />
                  </customHeaders>
                </httpProtocol>
                """;

        IisConfig.Section section = sectionById("httpProtocol");
        IisConfig.Data data = IisConfig.parse(xml, section);

        assertEquals("true", data.values().get("allowKeepAlive"));
        assertEquals(1, firstCollection(section, data).size());
        assertEquals("X-Frame-Options", firstCollection(section, data).getFirst().get("name"));
    }

    @Test
    void parsesSeveralCollectionsOfTheSameSection() {
        String xml = """
                <requestFiltering allowDoubleEscaping="false">
                  <fileExtensions allowUnlisted="true">
                    <add fileExtension=".exe" allowed="false" />
                  </fileExtensions>
                  <hiddenSegments>
                    <add segment="bin" />
                    <add segment="App_Data" />
                  </hiddenSegments>
                  <verbs allowUnlisted="true">
                    <add verb="TRACE" allowed="false" />
                  </verbs>
                </requestFiltering>
                """;

        IisConfig.Section section = sectionById("requestFiltering");
        IisConfig.Data data = IisConfig.parse(xml, section);

        assertEquals(1, data.itemsOf(section.collections().get(0)).size());
        assertEquals(".exe", data.itemsOf(section.collections().get(0)).getFirst().get("fileExtension"));
        assertEquals(2, data.itemsOf(section.collections().get(1)).size());
        assertEquals("App_Data", data.itemsOf(section.collections().get(1)).get(1).get("segment"));
        assertEquals("TRACE", data.itemsOf(section.collections().get(3)).getFirst().get("verb"));
        assertEquals("true", data.values().get("fileExtensions.allowUnlisted"));
    }

    @Test
    void resolvesNestedCollectionContainers() {
        String xml = """
                <requestFiltering>
                  <requestLimits maxUrl="4096">
                    <headerLimits>
                      <add header="Content-type" sizeLimit="100" />
                    </headerLimits>
                  </requestLimits>
                </requestFiltering>
                """;

        IisConfig.Section section = sectionById("requestFiltering");
        IisConfig.Data data = IisConfig.parse(xml, section);
        IisConfig.Collection headers = section.collections().get(4);

        assertEquals(1, data.itemsOf(headers).size());
        assertEquals("Content-type", data.itemsOf(headers).getFirst().get("header"));
    }

    @Test
    void flattensNestedScalarAttributes() {
        String xml = """
                <requestFiltering allowDoubleEscaping="false">
                  <requestLimits maxAllowedContentLength="30000000" maxUrl="4096" />
                </requestFiltering>
                """;

        IisConfig.Data data = IisConfig.parse(xml, sectionById("requestFiltering"));

        assertEquals("false", data.values().get("allowDoubleEscaping"));
        assertEquals("30000000", data.values().get("requestLimits.maxAllowedContentLength"));
        assertEquals("4096", data.values().get("requestLimits.maxUrl"));
    }

    @Test
    void parsesErrorPagesCollection() {
        String xml = """
                <httpErrors errorMode="Custom" existingResponse="Replace">
                  <error statusCode="404" subStatusCode="-1" path="/404.html" responseMode="ExecuteURL" />
                </httpErrors>
                """;

        IisConfig.Section section = sectionById("httpErrors");
        IisConfig.Data data = IisConfig.parse(xml, section);

        assertEquals("Custom", data.values().get("errorMode"));
        assertEquals("404", firstCollection(section, data).getFirst().get("statusCode"));
        assertEquals("/404.html", firstCollection(section, data).getFirst().get("path"));
    }

    @Test
    void buildsSelectorForCollectionItems() {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("fileExtension", ".json");
        item.put("mimeType", "application/json");

        assertEquals("[fileExtension='.json']",
                IisConfig.selector(item, List.of("fileExtension")));
        assertEquals("[fileExtension='.json',mimeType='application/json']",
                IisConfig.selector(item, List.of("fileExtension", "mimeType")));
    }

    @Test
    void selectorSkipsBlankValues() {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("statusCode", "404");
        item.put("subStatusCode", "");

        assertEquals("[statusCode='404']", IisConfig.selector(item, List.of("statusCode", "subStatusCode")));
    }

    @Test
    void customSectionKeepsPathAsTitle() {
        IisConfig.Section section = IisConfig.custom("system.webServer/httpLogging");

        assertEquals("system.webServer/httpLogging", section.path());
        assertEquals("system.webServer/httpLogging", section.title());
        assertTrue(section.attributes().isEmpty());
    }

    @Test
    void returnsEmptyDataForInvalidOutput() {
        IisConfig.Data data = IisConfig.parse("nao e xml", sectionById("modules"));

        assertTrue(data.isEmpty());
    }

    @Test
    void catalogCoversEverySectionWithUsableCollections() {
        for (IisConfig.Section section : IisConfig.catalog()) {
            for (IisConfig.Collection collection : section.collections()) {
                assertTrue(!collection.keys().isEmpty(),
                        section.id() + " precisa de chaves para remover itens");
                assertTrue(collection.columns().containsAll(collection.keys()),
                        section.id() + " deve expor as chaves como colunas editáveis");
                assertTrue(!collection.title().isBlank(), section.id() + " precisa de título de aba");
            }
        }
    }
}
