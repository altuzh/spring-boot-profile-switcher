package configswitcher.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import configswitcher.i18n.I18n;
import org.jetbrains.annotations.NotNull;

public class OpenPluginSettingsAction extends AnAction implements DumbAware {

    public OpenPluginSettingsAction() {
        super("Settings...",
                "Open settings dialog",
                AllIcons.General.Settings);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable.class);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        e.getPresentation().setEnabledAndVisible(true);
        e.getPresentation().setText(I18n.get(project, "action.settings"));
        e.getPresentation().setDescription(I18n.get(project, "action.settings.desc"));
    }
}
