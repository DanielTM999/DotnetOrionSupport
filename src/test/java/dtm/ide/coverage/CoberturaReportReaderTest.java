package dtm.ide.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CoberturaReportReaderTest {
    @TempDir Path dir;
    @Test void resolvesSourcesMergesDuplicateMethodsAndIgnoresOutsideWorkspace() throws Exception {
        Path source = Files.createDirectories(dir.resolve("src")).resolve("Demo.cs");
        Files.writeString(source, "class Demo {}");
        Path report = dir.resolve("coverage.cobertura.xml");
        Files.writeString(report, """
            <coverage><sources><source>src</source></sources><packages><package><classes>
              <class filename="Demo.cs"><methods><method><lines><line number="1" hits="0"/></lines></method></methods>
                <lines><line number="1" hits="2"/><line number="2" hits="0"/></lines></class>
              <class filename="../outside.cs"><lines><line number="1" hits="1"/></lines></class>
            </classes></package></packages></coverage>
            """);
        var reader = new CoberturaReportReader();
        var result = reader.read(report, dir);
        assertEquals(1, result.files().size());
        assertEquals(2, result.lines());
        assertEquals(1, result.covered());
        assertEquals(50.0, result.percent());
        assertEquals(2L, result.files().get(source).get(1));
        assertEquals(result, reader.merge(List.of(result, result)));
    }
    @Test void rejectsInvalidLineNumbers() throws Exception {
        Files.writeString(dir.resolve("Demo.cs"), "");
        Path report = dir.resolve("report.xml");
        Files.writeString(report, "<coverage><class filename='Demo.cs'><line number='oops' hits='1'/></class></coverage>");
        assertThrows(java.io.IOException.class, () -> new CoberturaReportReader().read(report, dir));
    }

    @Test void acceptsCoverletFilesystemRootButOnlyMapsFilesInsideWorkspace() throws Exception {
        Path source = dir.resolve("Calculator.cs"); Files.writeString(source, "class Calculator {}");
        Path report = dir.resolve("coverage.xml");
        Path filesystemRoot = source.getRoot();
        String filename = filesystemRoot.relativize(source).toString().replace('\\', '/');
        Files.writeString(report, "<coverage><sources><source>" + filesystemRoot + "</source></sources>"
                + "<class filename='" + filename + "'><line number='1' hits='1'/></class></coverage>");
        assertEquals(1, new CoberturaReportReader().read(report, dir).covered());
    }

    @Test void acceptsStandardDoctypeWithoutLoadingItAndRejectsEntityDeclarations() throws Exception {
        Files.writeString(dir.resolve("Demo.cs"), "class Demo {}");
        Path report = dir.resolve("report.xml");
        String coverage = "<coverage><class filename='Demo.cs'><line number='1' hits='1'/></class></coverage>";
        Files.writeString(report, "<!DOCTYPE coverage SYSTEM 'http://127.0.0.1:1/no-network.dtd'>" + coverage);
        assertEquals(1, new CoberturaReportReader().read(report, dir).covered());
        Files.writeString(report, "<!DOCTYPE coverage [<!ENTITY ex SYSTEM 'file:///secret'>]>" + coverage);
        assertThrows(java.io.IOException.class, () -> new CoberturaReportReader().read(report, dir));
    }
}
