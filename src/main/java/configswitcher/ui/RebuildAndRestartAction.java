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

/**
 * Action that forces a rebuild (pre-run build stage or project compile)
 * and restarts the application with the currently selected profile.
 */
public class RebuildAndRestartAction extends AnAction implements DumbAware {

    public RebuildAndRestartAction() {
        super("Rebuild and Restart", "Rebuild project and restart application with selected profile", AllIcons.Actions.Compile);
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
        String profile = settings.selectedProfile != null && !settings.selectedProfile.isBlank()
                ? settings.selectedProfile
                : "default";

        e.getPresentation().setEnabledAndVisible(true);
        e.getPresentation().setIcon(AllIcons.Actions.Compile);
        e.getPresentation().setText(I18n.get(lang, "action.rebuild_restart", profile));
        e.getPresentation().setDescription(I18n.get(lang, "action.rebuild_restart.desc", profile));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;

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

        AppRunManager.getInstance(project).rebuildAndRestart(config);
    }
}
