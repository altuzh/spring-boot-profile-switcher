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
 * Action that dynamically toggles between Start (Green Play) and Stop (Red Square)
 * depending on whether the application or command pipeline is currently running.
 */
public class StartStopConfigAction extends AnAction implements DumbAware {

    public StartStopConfigAction() {
        super("Start / Stop Application", "Start or stop application with selected profile", AllIcons.Actions.Execute);
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

        e.getPresentation().setEnabledAndVisible(true);
        AppRunManager runManager = AppRunManager.getInstance(project);
        boolean isRunning = runManager.isAppRunning();

        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        PluginLanguage lang = settings.language != null ? settings.language : PluginLanguage.EN;
        String profile = settings.selectedProfile != null && !settings.selectedProfile.isBlank()
                ? settings.selectedProfile
                : "default";

        if (isRunning) {
            e.getPresentation().setIcon(AllIcons.Actions.Suspend);
            e.getPresentation().setText(I18n.get(lang, "action.stop", profile));
            e.getPresentation().setDescription(I18n.get(lang, "action.stop.desc", profile));
        } else {
            e.getPresentation().setIcon(AllIcons.Actions.Execute);
            e.getPresentation().setText(I18n.get(lang, "action.start", profile));
            e.getPresentation().setDescription(I18n.get(lang, "action.start.desc", profile));
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;

        AppRunManager runManager = AppRunManager.getInstance(project);
        if (runManager.isAppRunning()) {
            runManager.stopApplication();
        } else {
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

            runManager.switchAndRun(config, false, false);
        }
    }
}
