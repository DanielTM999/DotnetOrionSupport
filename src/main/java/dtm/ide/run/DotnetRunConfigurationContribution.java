package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationContribution;
import dtm.ide.api.extension.runconfig.RunConfigurationForm;

import java.nio.file.Path;
import java.util.function.Supplier;

public final class DotnetRunConfigurationContribution implements RunConfigurationContribution {

    private final Supplier<Path> projectRootSupplier;

    public DotnetRunConfigurationContribution(Supplier<Path> projectRootSupplier) {
        this.projectRootSupplier = projectRootSupplier;
    }

    @Override
    public String getType() {
        return DotnetRunSupport.TYPE_RUN;
    }

    @Override
    public String getDisplayName() {
        return ".NET: Executar";
    }

    @Override
    public RunConfigurationForm createForm() {
        return new DotnetRunConfigurationForm(projectRootSupplier);
    }
}
