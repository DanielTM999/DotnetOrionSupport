package dtm.ide.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DotnetTestServiceTest {
    @TempDir Path dir;
    @Test void missingCoverageIsReportedEvenWhenTheRunnerReturnsNoArtifacts() throws Exception {
        var run = new DotnetTestService.Run(null, dir, DotnetTestService.Platform.MTP,
                java.util.Map.of(), List.of("dotnet", "test", "--coverlet"));
        var results = new DotnetTestService().read(run, dir);
        assertTrue(results.coverage().files().isEmpty());
        assertTrue(results.warnings().stream().anyMatch(message -> message.contains("No Cobertura report")));
    }
    @Test void readsTimestampedNativeMtpCoverageReports() throws Exception {
        Path source = dir.resolve("Calculator.cs");
        Files.writeString(source, "public class Calculator {}\n");
        Files.writeString(dir.resolve("coverage.cobertura.201026233535232.xml"), """
                <coverage><sources><source>%s</source></sources><packages><package><classes>
                <class filename="Calculator.cs"><lines><line number="1" hits="1"/></lines></class>
                </classes></package></packages></coverage>
                """.formatted(dir.toString().replace("&", "&amp;")));
        var run = new DotnetTestService.Run(null, dir, DotnetTestService.Platform.MTP,
                java.util.Map.of(source, dtm.ide.coverage.CoverageReport.hash(Files.readString(source))), List.of("--coverlet"));
        assertEquals(1, new DotnetTestService().read(run, dir).coverage().covered());
    }
    @Test void selectsPlatformFromNearestGlobalJsonAndBuildsNativeMtpArguments() throws Exception {
        Files.writeString(dir.resolve("global.json"), "{ /* config */ \"test\": {\"runner\": \"Microsoft.Testing.Platform\",},}");
        var platform = DotnetTestService.platform(dir);
        assertEquals(DotnetTestService.Platform.MTP, platform);
        var args = DotnetTestService.baseArguments(Path.of("dotnet"), "Release", dir.resolve("Demo.slnx"), "net10.0", platform);
        assertTrue(args.contains("--solution"));
        assertTrue(args.containsAll(List.of("--framework", "net10.0", "Release")));
        Path child = Files.createDirectories(dir.resolve("child"));
        Files.writeString(child.resolve("global.json"), "{\"sdk\":{\"version\":\"8.0.100\"}}");
        assertEquals(DotnetTestService.Platform.VSTEST, DotnetTestService.platform(child));
    }
    @Test void translatesXunitMtpFiltersAndRejectsAmbiguousExpressions() throws Exception {
        Path project = dir.resolve("Demo.csproj");
        Files.writeString(project, "<Project><ItemGroup><PackageReference Include='xunit.v3'/></ItemGroup></Project>");
        assertEquals(List.of("--filter-method", "*Demo.Tests.Works*"), DotnetTestService.filterArguments(
                DotnetTestService.Platform.MTP, project, "FullyQualifiedName~Demo.Tests.Works"));
        assertThrows(java.io.IOException.class, () -> DotnetTestService.filterArguments(
                DotnetTestService.Platform.MTP, project, "Category=Fast"));
        assertEquals(List.of("--filter", "Category=Fast"), DotnetTestService.filterArguments(
                DotnetTestService.Platform.VSTEST, project, "Category=Fast"));
    }
    @Test void discoveryRetainsParameterizedNamesAndIgnoresBuildNoise() {
        String output = "Demo -> /build/Demo.dll\nThe following Tests are available:\n    Demo.Tests.Adds(value: 1)\n    Demo.Tests.Adds(value: 2)\n";
        assertEquals(List.of("Demo.Tests.Adds(value: 1)", "Demo.Tests.Adds(value: 2)"),
                DotnetTestService.parseDiscovery(output, DotnetTestService.Platform.VSTEST));
        assertEquals(List.of("Passes", "Another"), DotnetTestService.parseDiscovery(
                "Discovering tests from Mtp.dll\nTests 2 discovered in assembly - Mtp.dll\n  Passes\n  Another\nTests 2 discovered.\n", DotnetTestService.Platform.MTP));
    }
}
