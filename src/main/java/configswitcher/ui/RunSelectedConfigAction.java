package configswitcher.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import configswitcher.i18n.I18n;
import configswitcher.i18n.PluginLanguage;
import configswitcher.model.ConfigFileModel;
import configswitcher.service.AppRunManager;
import configswitcher.service.ConfigScannerService;
import configswitcher.state.PluginSettingsState;
import configswitcher.util.ConfigNotifier;
import org.jetbrains.annotations.NotNull;

public class RunSelectedConfigAction extends AnAction implements DumbAware {

    public RunSelectedConfigAction() {
        super("Run Selected Profile", "Run or restart application with the currently selected YAML configuration", AllIcons.Actions.Restart);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }

        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        PluginLanguage lang = settings.language != null ? settings.language : PluginLanguage.EN;
        String profile = settings.selectedProfile;
        e.getPresentation().setEnabledAndVisible(true);
        e.getPresentation().setText(I18n.get(lang, "action.run_restart", profile));
        e.getPresentation().setDescription(I18n.get(lang, "action.run_restart.desc", profile));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        ConfigScannerService scanner = ConfigScannerService.getInstance(project);

        ConfigFileModel config = scanner.findConfigByProfile(settings.selectedProfile);
        if (config == null) {
            config = scanner.getBaseConfigFile();
        }

        if (config == null) {
            ConfigNotifier.notifyWarning(project, "No configuration found for profile '" + settings.selectedProfile + "'.");
            return;
        }

        AppRunManager.getInstance(project).switchAndRun(config, false);
    }
}
