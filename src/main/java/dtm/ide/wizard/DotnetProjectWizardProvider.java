package dtm.ide.wizard;

import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.extension.wizard.ProjectWizard;
import dtm.ide.api.extension.wizard.ProjectWizardProvider;
import dtm.ide.api.plugin.PluginScope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Singleton
@PluginReference(id = "dotnet-project-wizard", singleton = true, scope = PluginScope.APPLICATION)
public class DotnetProjectWizardProvider extends ProjectWizardProvider {

    @Override
    public Collection<ProjectWizard> getProjectWizards() {
        List<ProjectWizard> wizards = new ArrayList<>();
        for (DotnetTemplate template : DotnetTemplate.available()) {
            wizards.add(new DotnetProjectWizard(template));
        }
        return wizards;
    }
}
