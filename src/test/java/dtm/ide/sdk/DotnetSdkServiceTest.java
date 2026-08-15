package dtm.ide.sdk;

import dtm.ide.api.extension.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetSdkServiceTest {

    @Test
    void mapsModernTargetFrameworkToSdkVersion(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>net10.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);

        DotnetSdkService service = new DotnetSdkService(null, null);

        assertEquals(DotnetSdkService.DOTNET_10_SDK_VERSION, service.resolveSdkVersion(dir));
    }

    @Test
    void globalJsonVersionOverridesTargetFramework(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("global.json"), """
                {
                  "sdk": {
                    "version": "10.0.100"
                  }
                }
                """);
        Files.writeString(dir.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>net10.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);

        DotnetSdkService service = new DotnetSdkService(null, null);

        assertEquals("10.0.100", service.resolveSdkVersion(dir));
    }

    @Test
    void dotnetPathPrefersBundledResourceSdk(@TempDir Path dir) throws Exception {
        Path dotnet = bundleDotnetHome(dir, DotnetSdkService.DOTNET_10_SDK_VERSION);

        DotnetSdkService service = new DotnetSdkService(new TestResource(dir), null);

        assertEquals(dotnet.toAbsolutePath().normalize(),
                service.getDotnetPath(DotnetSdkService.DOTNET_10_SDK_VERSION).orElseThrow());
    }

    @Test
    void dotnetPathRequiresResolvedSdkVersionForProject(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("App");
        Files.createDirectories(project);
        Files.writeString(project.resolve("global.json"), """
                {
                  "sdk": {
                    "version": "999.0.100"
                  }
                }
                """);
        Files.writeString(project.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>net8.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);
        bundleDotnetHome(dir, DotnetSdkService.DOTNET_10_SDK_VERSION);

        DotnetSdkService service = new DotnetSdkService(new TestResource(dir), null);

        assertTrue(service.getDotnetPath(project).isEmpty());
    }

    @Test
    void dotnetPathFindsExactBundledSdkForProject(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("App");
        Files.createDirectories(project);
        Files.writeString(project.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>net8.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);
        Path dotnet = bundleDotnetHome(dir, DotnetSdkService.DEFAULT_DOTNET_SDK_VERSION);

        DotnetSdkService service = new DotnetSdkService(new TestResource(dir), null);

        assertEquals(dotnet.toAbsolutePath().normalize(), service.getDotnetPath(project).orElseThrow());
    }

    @Test
    void razorReadinessRequiresExtensionAndSourceGeneratorButNotDesignTimeTargets(@TempDir Path dir) throws Exception {
        Path roslynRoot = dir.resolve("sdk")
                .resolve("roslyn")
                .resolve(DotnetSdkService.DEFAULT_ROSLYN_LS_VERSION);
        DotnetSdkService service = new DotnetSdkService(new TestResource(dir), null);

        Files.createDirectories(roslynRoot);
        assertFalse(service.isRoslynRazorReady());

        Files.createFile(roslynRoot.resolve("Microsoft.VisualStudioCode.RazorExtension.dll"));
        assertFalse(service.isRoslynRazorReady());

        Files.createFile(roslynRoot.resolve("Microsoft.CodeAnalysis.Razor.Compiler.dll"));
        assertTrue(service.isRoslynRazorReady());
    }

    @Test
    void visualStudioRazorExtensionDoesNotEnableStandaloneRoslynRazor(@TempDir Path dir) throws Exception {
        Path roslynRoot = dir.resolve("sdk")
                .resolve("roslyn")
                .resolve(DotnetSdkService.DEFAULT_ROSLYN_LS_VERSION);
        DotnetSdkService service = new DotnetSdkService(new TestResource(dir), null);

        Files.createDirectories(roslynRoot.resolve("Targets"));
        Files.createFile(roslynRoot.resolve("Microsoft.VisualStudio.RazorExtension.dll"));
        Files.createFile(roslynRoot.resolve("Microsoft.CodeAnalysis.Razor.Compiler.dll"));
        Files.createFile(roslynRoot.resolve("Targets").resolve("Microsoft.NET.Sdk.Razor.DesignTime.targets"));

        assertFalse(service.isRoslynRazorReady());
    }

    @Test
    void roslynBundleLookupPrefersContentOverLibCopies(@TempDir Path dir) throws Exception {
        Path roslynRoot = dir.resolve("sdk")
                .resolve("roslyn")
                .resolve(DotnetSdkService.DEFAULT_ROSLYN_LS_VERSION);
        Path contentDll = roslynRoot.resolve("content").resolve("Microsoft.VisualStudioCode.RazorExtension.dll");
        Path libDll = roslynRoot.resolve("lib").resolve("net9.0").resolve("Microsoft.VisualStudioCode.RazorExtension.dll");
        Files.createDirectories(contentDll.getParent());
        Files.createDirectories(libDll.getParent());
        Files.createFile(libDll);
        Files.createFile(contentDll);

        DotnetSdkService service = new DotnetSdkService(new TestResource(dir), null);

        assertEquals(contentDll.toAbsolutePath().normalize(),
                service.getRazorExtensionPath().orElseThrow());
    }

    @Test
    void roslynLanguageServerLookupPrefersContentLanguageServerCopy(@TempDir Path dir) throws Exception {
        Path roslynRoot = dir.resolve("sdk")
                .resolve("roslyn")
                .resolve(DotnetSdkService.DEFAULT_ROSLYN_LS_VERSION);
        Path libDll = roslynRoot.resolve("lib").resolve("net9.0").resolve("Microsoft.CodeAnalysis.LanguageServer.dll");
        Path contentDll = roslynRoot.resolve("content").resolve("LanguageServer")
                .resolve("any").resolve("Microsoft.CodeAnalysis.LanguageServer.dll");
        Files.createDirectories(libDll.getParent());
        Files.createDirectories(contentDll.getParent());
        Files.createFile(libDll);
        Files.createFile(contentDll);

        DotnetSdkService service = new DotnetSdkService(new TestResource(dir), null);

        assertEquals(contentDll.toAbsolutePath().normalize(),
                service.getRoslynLanguageServerPath().orElseThrow());
    }

    @Test
    void nupkgArchivesAreExtractedAsZipFiles(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("microsoft.visualstudiocode.razorextension."
                + DotnetSdkService.DEFAULT_ROSLYN_RAZOR_VERSION.toLowerCase()
                + ".nupkg");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("lib/net472/Microsoft.VisualStudioCode.RazorExtension.dll"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }

        Method install = DotnetSdkService.class.getDeclaredMethod("installArchive", Path.class, Path.class);
        install.setAccessible(true);
        install.invoke(null, archive, dir);

        assertTrue(Files.isRegularFile(dir.resolve("lib/net472/Microsoft.VisualStudioCode.RazorExtension.dll")));
        assertFalse(Files.exists(archive));
    }

    private static Path bundleDotnetHome(Path dir, String sdkVersion) throws Exception {
        Path home = dir.resolve("sdk").resolve("dotnet").resolve("host");
        Path muxer = home.resolve(isWindows() ? "dotnet.exe" : "dotnet");
        Files.createDirectories(home.resolve("sdk").resolve(sdkVersion));
        Files.createFile(muxer);
        return muxer;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record TestResource(Path root) implements Resource {
        @Override
        public Path getResourcePath() {
            return root;
        }

        @Override
        public Path getResourcePath(String s) {
            return s == null ? root : root.resolve(s);
        }

        @Override
        public Path getResourcePath(Path path) {
            return path == null ? root : root.resolve(path);
        }

        @Override
        public URL getResource(String s) {
            return null;
        }

        @Override
        public List<URL> getResources(Collection<String> collection) {
            return List.of();
        }

        @Override
        public InputStream getResourceAsStream(String s) {
            return null;
        }

        @Override
        public List<InputStream> getResourcesAsStreams(Collection<String> collection) {
            return List.of();
        }

        @Override
        public Path getSharedResourcePath() {
            return root;
        }

        @Override
        public URL getSharedResource(String s) {
            return null;
        }

        @Override
        public List<URL> getSharedResources(Collection<String> collection) {
            return List.of();
        }

        @Override
        public InputStream getSharedResourceAsStream(String s) {
            return null;
        }

        @Override
        public List<InputStream> getSharedResourcesAsStreams(Collection<String> collection) {
            return List.of();
        }
    }
}
