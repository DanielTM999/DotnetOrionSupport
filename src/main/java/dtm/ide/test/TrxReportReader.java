package dtm.ide.test;

import dtm.ide.util.XmlDocuments;
import org.w3c.dom.Element;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TrxReportReader {
    public List<TestResult> read(Path file) throws IOException {
        var document = XmlDocuments.read(file);
        Map<String, String> names = new HashMap<>();
        var definitions = document.getElementsByTagNameNS("*", "UnitTest");
        for (int i = 0; i < definitions.getLength(); i++) {
            var unit = (Element) definitions.item(i);
            var methods = unit.getElementsByTagNameNS("*", "TestMethod");
            if (methods.getLength() > 0) {
                var method = (Element) methods.item(0);
                // Some adapters append an assembly identity after the class name.
                String type = method.getAttribute("className").split(",", 2)[0];
                names.put(unit.getAttribute("id"), type + "." + method.getAttribute("name"));
            }
        }
        List<TestResult> results = new ArrayList<>();
        var nodes = document.getElementsByTagNameNS("*", "UnitTestResult");
        for (int i = 0; i < nodes.getLength(); i++) {
            var result = (Element) nodes.item(i);
            if (result.getElementsByTagNameNS("*", "UnitTestResult").getLength() > 0) continue;
            String id = result.getAttribute("testId");
            String display = result.getAttribute("testName");
            var outcome = switch (result.getAttribute("outcome").toLowerCase(Locale.ROOT)) {
                case "passed" -> TestResult.Outcome.PASSED;
                case "failed", "error", "timeout", "aborted" -> TestResult.Outcome.FAILED;
                case "notexecuted", "inconclusive", "skipped" -> TestResult.Outcome.SKIPPED;
                default -> TestResult.Outcome.UNKNOWN;
            };
            results.add(new TestResult(file.toAbsolutePath().normalize(),
                    id + ":" + result.getAttribute("executionId"), names.getOrDefault(id, display), display,
                    outcome, result.getAttribute("duration"), childText(result, "Message"), childText(result, "StackTrace")));
        }
        return List.copyOf(results);
    }

    private static String childText(Element parent, String name) {
        var nodes = parent.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }
}
