package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationContribution;
import dtm.ide.api.extension.runconfig.RunConfigurationForm;

import java.nio.file.Path;
import java.util.function.Supplier;

public final class DotnetRunConfigurationContribution implements RunConfigurationContribution {

    private final Supplier<Path> projectRootSupplier;
    private final String type;

    public DotnetRunConfigurationContribution(Supplier<Path> projectRootSupplier) {
        this(projectRootSupplier, DotnetRunSupport.TYPE_RUN);
    }

    public DotnetRunConfigurationContribution(Supplier<Path> projectRootSupplier, String type) {
        this.projectRootSupplier = projectRootSupplier;
        this.type = type;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getDisplayName() {
        return switch (type) {
            case DotnetRunSupport.TYPE_BUILD -> ".NET: Compilar";
            case DotnetRunSupport.TYPE_TEST -> ".NET: Testar";
            default -> ".NET: Executar";
        };
    }

    @Override
    public RunConfigurationForm createForm() {
        return new DotnetRunConfigurationForm(projectRootSupplier, type);
    }
}
