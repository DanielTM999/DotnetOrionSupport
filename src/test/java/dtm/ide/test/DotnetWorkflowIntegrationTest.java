package dtm.ide.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real CLI boundary: framework selection, parameterized/skipped results, failures and coverage. */
class DotnetWorkflowIntegrationTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path dir;

    @Test void discoversRunsReportsAndCancelsARealTestProject() throws Exception {
        Path dotnet = Path.of(System.getProperty("dotnet.path", "dotnet"));
        // A bare command must stay on PATH; absolute paths are supplied by CI/local validation.
        assertTrue(Files.isRegularFile(dotnet), "Set -Ddotnet.path to the dotnet executable");
        Path root = Files.createDirectories(dir.resolve("solution with spaces"));
        Path library = Files.createDirectories(root.resolve("Library"));
        Path tests = Files.createDirectories(root.resolve("Tests"));
        Files.writeString(root.resolve("global.json"), "{\"sdk\":{\"version\":\"8.0.100\",\"rollForward\":\"latestFeature\"}}");
        Files.writeString(library.resolve("Library.csproj"), "<Project Sdk='Microsoft.NET.Sdk'><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        Files.writeString(library.resolve("Calculator.cs"), "namespace Demo; public class Calculator { public int Add(int a, int b) { if (a < 0) return b; return a + b; } }");
        Path project = tests.resolve("Tests.csproj");
        Files.writeString(project, """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup><TargetFramework>net8.0</TargetFramework><IsTestProject>true</IsTestProject></PropertyGroup>
              <ItemGroup>
                <ProjectReference Include="../Library/Library.csproj"/>
                <PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.11.1"/>
                <PackageReference Include="xunit" Version="2.9.2"/>
                <PackageReference Include="xunit.runner.visualstudio" Version="2.8.2" PrivateAssets="all"/>
                <PackageReference Include="coverlet.collector" Version="6.0.0" PrivateAssets="all"/>
              </ItemGroup>
            </Project>
            """);
        Files.writeString(tests.resolve("Tests.cs"), """
            using Xunit;
            namespace Demo;
            public class Tests {
              [Fact] public void Passes() { Assert.Equal(3, new Calculator().Add(1, 2)); }
              [Theory][InlineData(1)][InlineData(2)] public void Adds(int value) { Assert.Equal(value + 1, new Calculator().Add(value, 1)); }
              [Fact] public void Fails() { Assert.Equal(99, new Calculator().Add(1, 2)); }
              [Fact(Skip="intentional")] public void Skipped() { }
              [Fact] public async System.Threading.Tasks.Task Slow() { await System.Threading.Tasks.Task.Delay(120000); }
            }
            """);
        var service = new DotnetTestService();
        try {
            var discovered = service.discover(root, dotnet, "Debug", project, "net8.0");
            assertTrue(discovered.stream().anyMatch(name -> name.contains("Demo.Tests.Adds")), discovered.toString());
            var run = service.start(root, dotnet, "Debug", "FullyQualifiedName!~Slow", false, true, project, "net8.0");
            int exit = await(run.process());
            assertNotEquals(0, exit, "fixture contains one deliberate assertion failure");
            var results = service.read(run, root);
            assertEquals(5, results.tests().size(), results.toString());
            assertEquals(1, results.tests().stream().filter(r -> r.outcome() == TestResult.Outcome.FAILED).count());
            assertEquals(1, results.tests().stream().filter(r -> r.outcome() == TestResult.Outcome.SKIPPED).count());
            assertTrue(results.coverage().lines() > 0, "coverage must include the referenced library: " + results.warnings());
            assertFalse(TestProblems.diagnostics(results.tests(), root).isEmpty());
            var rerun = service.start(root, dotnet, "Debug", "FullyQualifiedName=Demo.Tests.Fails", false, false, project, "net8.0");
            await(rerun.process());
            assertEquals(1, service.read(rerun, root).tests().size());
            var slow = service.start(root, dotnet, "Debug", "FullyQualifiedName=Demo.Tests.Slow", false, false, project, "net8.0");
            var drain = Thread.ofVirtual().start(() -> { try { slow.process().getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); } catch (Exception ignored) {} });
            Thread.sleep(1500);
            service.stop();
            assertTrue(slow.process().waitFor(15, TimeUnit.SECONDS), "cancellation must terminate the test process");
            drain.join(3000);
        } finally { service.stop(); }
    }

    private static int await(Process process) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> { try { process.getInputStream().transferTo(output); } catch (Exception ignored) {} });
        if (!process.waitFor(150, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Test run timed out"); }
        drain.join(3000);
        System.out.println(output.toString(java.nio.charset.StandardCharsets.UTF_8));
        return process.exitValue();
    }

    @Test void nativeMtpProducesStructuredResults() throws Exception {
        Path dotnet = Path.of(System.getProperty("dotnet.path", "dotnet"));
        assertTrue(Files.isRegularFile(dotnet), "Set -Ddotnet.path");
        Path root = Files.createDirectories(dir.resolve("MTP project"));
        Path library = Files.createDirectories(root.resolve("Library"));
        Path tests = Files.createDirectories(root.resolve("Tests"));
        Files.writeString(library.resolve("Library.csproj"), "<Project Sdk='Microsoft.NET.Sdk'><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>");
        Files.writeString(library.resolve("Calculator.cs"), "namespace Demo; public class Calculator { public int Add(int a, int b) { return a + b; } }");
        Files.writeString(root.resolve("global.json"), "{\"sdk\":{\"version\":\"10.0.100\",\"rollForward\":\"latestFeature\"},\"test\":{\"runner\":\"Microsoft.Testing.Platform\"}}");
        Path project = tests.resolve("Mtp.csproj");
        Files.writeString(project, """
            <Project Sdk="MSTest.Sdk/4.4.1">
              <PropertyGroup><TargetFramework>net10.0</TargetFramework><TestingExtensionsProfile>None</TestingExtensionsProfile></PropertyGroup>
              <ItemGroup>
                <ProjectReference Include="../Library/Library.csproj"/>
                <PackageReference Include="Microsoft.Testing.Extensions.TrxReport" Version="2.4.1"/>
                <PackageReference Include="coverlet.MTP" Version="10.1.0" PrivateAssets="all"/>
              </ItemGroup>
            </Project>
            """);
        Files.writeString(tests.resolve("Tests.cs"), """
            using Microsoft.VisualStudio.TestTools.UnitTesting;
            namespace Demo;
            [TestClass] public class Tests {
              [TestMethod] public void Passes() { Assert.AreEqual(2, new Calculator().Add(1, 1)); }
              [TestMethod] public void Another() { Assert.IsTrue(true); }
            }
            """);
        var service = new DotnetTestService();
        try {
            var run = service.start(root, dotnet, "Debug", "FullyQualifiedName=Demo.Tests.Passes", false, true, project, "net10.0");
            assertEquals(0, await(run.process()));
            var results = service.read(run, root);
            assertEquals(1, results.tests().size(), results.toString());
            assertEquals(TestResult.Outcome.PASSED, results.tests().getFirst().outcome());
            assertTrue(results.coverage().covered() > 0, "MTP coverage must use the permissive Coverlet extension: " + results.warnings());
            assertFalse(service.discover(root, dotnet, "Debug", project, "net10.0").isEmpty(), "MTP discovery must populate the explorer");
            var debug = service.start(root, dotnet, "Debug", "FullyQualifiedName=Demo.Tests.Passes", true, false, project, "net10.0");
            var pid = new java.util.concurrent.CompletableFuture<Long>();
            Thread drain = Thread.ofVirtual().start(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(debug.process().getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Long candidate = DotnetTestService.debugProcessId(line);
                        if (candidate != null) pid.complete(candidate);
                    }
                } catch (Exception error) { pid.completeExceptionally(error); }
            });
            long debugPid = pid.get(30, TimeUnit.SECONDS);
            assertTrue(ProcessHandle.of(debugPid).map(ProcessHandle::isAlive).orElse(false), "debug PID must identify the live test application");
            service.stop();
            assertTrue(debug.process().waitFor(15, TimeUnit.SECONDS));
            drain.join(3000);
        } finally { service.stop(); }
    }

    @Test void nunitParameterizedCasesProduceSeparateResults() throws Exception {
        Path dotnet = Path.of(System.getProperty("dotnet.path", "dotnet"));
        assertTrue(Files.isRegularFile(dotnet), "Set -Ddotnet.path");
        Path root = Files.createDirectories(dir.resolve("NUnit project"));
        Path project = root.resolve("NUnitTests.csproj");
        Files.writeString(project, """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup><TargetFramework>net8.0</TargetFramework><IsTestProject>true</IsTestProject></PropertyGroup>
              <ItemGroup>
                <PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.11.1"/>
                <PackageReference Include="NUnit" Version="4.3.2"/>
                <PackageReference Include="NUnit3TestAdapter" Version="5.0.0"/>
              </ItemGroup>
            </Project>
            """);
        Files.writeString(root.resolve("Tests.cs"), """
            using NUnit.Framework;
            namespace Demo;
            public class Tests {
              [TestCase(1)][TestCase(2)] public void Works(int value) { Assert.That(value, Is.GreaterThan(0)); }
              [Test, Ignore("intentional")] public void Skipped() { }
            }
            """);
        var service = new DotnetTestService();
        try {
            var run = service.start(root, dotnet, "Debug", null, false, false, project, "net8.0");
            assertEquals(0, await(run.process()));
            var results = service.read(run, root);
            assertEquals(3, results.tests().size(), results.toString());
            assertEquals(2, results.tests().stream().filter(r -> r.outcome() == TestResult.Outcome.PASSED).count());
        } finally { service.stop(); }
    }
}
