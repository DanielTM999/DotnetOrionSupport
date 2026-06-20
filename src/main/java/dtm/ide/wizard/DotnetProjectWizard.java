package dtm.ide.wizard;

import dtm.ide.api.extension.wizard.ProjectWizard;
import dtm.ide.api.extension.wizard.ProjectWizardCallback;

import javax.swing.Icon;
import javax.swing.JPanel;

public class DotnetProjectWizard implements ProjectWizard {

    private static final String LANGUAGE = "C#";

    private final DotnetTemplate template;

    public DotnetProjectWizard(DotnetTemplate template) {
        this.template = template;
    }

    @Override
    public String getId() {
        return template.id();
    }

    @Override
    public String getName() {
        return template.displayName();
    }

    @Override
    public String getLanguage() {
        return LANGUAGE;
    }

    @Override
    public Icon getIcon() {
        return null;
    }

    @Override
    public JPanel getView(ProjectWizardCallback callback) {
        return new DotnetProjectWizardView(template, callback);
    }
}
