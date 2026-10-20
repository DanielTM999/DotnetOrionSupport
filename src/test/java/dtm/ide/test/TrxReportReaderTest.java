package dtm.ide.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TrxReportReaderTest {
    @TempDir Path dir;
    @Test void readsNamesDurationsFailuresAndSkippedCases() throws Exception {
        Path report = dir.resolve("results.trx");
        Files.writeString(report, """
            <TestRun xmlns="http://microsoft.com/schemas/VisualStudio/TeamTest/2010">
              <TestDefinitions><UnitTest id="a"><TestMethod className="Demo.Tests, Demo" name="Works"/></UnitTest></TestDefinitions>
              <Results>
                <UnitTestResult testId="a" executionId="1" testName="Works(value: 1)" outcome="Passed" duration="00:00:00.015"/>
                <UnitTestResult testId="a" executionId="2" testName="Works(value: 2)" outcome="Failed">
                  <Output><ErrorInfo><Message>Expected &lt;2&gt;</Message><StackTrace>in /src/Tests.cs:line 9</StackTrace></ErrorInfo></Output>
                </UnitTestResult>
                <UnitTestResult testId="b" executionId="3" testName="Ignored" outcome="NotExecuted"/>
              </Results>
            </TestRun>
            """);
        var results = new TrxReportReader().read(report);
        assertEquals(3, results.size());
        assertEquals("Demo.Tests.Works", results.getFirst().name());
        assertEquals("00:00:00.015", results.getFirst().duration());
        assertNotEquals(results.get(0).id(), results.get(1).id());
        assertEquals(TestResult.Outcome.FAILED, results.get(1).outcome());
        assertEquals("Expected <2>", results.get(1).message());
        assertEquals(TestResult.Outcome.SKIPPED, results.get(2).outcome());
    }
    @Test void rejectsExternalEntities() throws Exception {
        Path report = dir.resolve("evil.trx");
        Files.writeString(report, "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///secret'>]><TestRun>&e;</TestRun>");
        assertThrows(java.io.IOException.class, () -> new TrxReportReader().read(report));
    }
}
