package dtm.ide.lsp;

import dtm.ide.api.extension.Resource;
import dtm.ide.sdk.DotnetSdkService;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public final class OmniSharpLspService extends AbstractLspService {

    public OmniSharpLspService(Resource resource, DotnetSdkService sdkService) {
        super(resource, sdkService);
    }

    @Override
    protected String serverName() {
        return "C# IntelliSense";
    }

    @Override
    protected Optional<Path> resolveServerBinary() {
        return sdkService().getOmniSharpPath();
    }

    @Override
    protected List<String> buildLaunchCommand(Path binary, Path loadTarget) {
        return List.of(
                binary.toAbsolutePath().toString(),
                "-lsp",
                "-s",
                loadTarget.toAbsolutePath().toString());
    }

    @Override
    protected void prepareServerConfig(Path binary) {
        try {
            Path dir = binary.toAbsolutePath().getParent();
            if (dir == null) {
                return;
            }
            Path config = dir.resolve("omnisharp.json");
            String content = "{\n"
                    + "  \"RoslynExtensionsOptions\": {\n"
                    + "    \"enableImportCompletion\": true,\n"
                    + "    \"enableAnalyzersSupport\": true,\n"
                    + "    \"enableDecompilationSupport\": true\n"
                    + "  }\n"
                    + "}\n";
            if (!Files.exists(config) || !content.equals(Files.readString(config))) {
                Files.writeString(config, content, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("Não foi possível gravar omnisharp.json: {}", e.getMessage());
        }
    }

    @Override
    protected Map<String, Object> initializationOptions() {
        return Map.of(
                "RoslynExtensionsOptions", Map.of(
                        "enableDecompilationSupport", true,
                        "enableImportCompletion", true,
                        "enableAnalyzersSupport", true
                )
        );
    }

    @Override
    protected void registerServerNotificationHandlers(LspJsonRpcClient rpc) {
        rpc.onNotification("o#/backgrounddiagnosticstatus", this::handleBackgroundDiagnosticStatus);
    }
}
