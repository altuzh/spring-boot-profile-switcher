package configswitcher.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import configswitcher.state.PluginSettingsState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import configswitcher.i18n.I18n;
import configswitcher.service.AuthStatusService;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.Locale;

public final class AuthStatusAction extends AnAction implements DumbAware {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean available = project != null && !project.isDefault() && !project.isDisposed();
        available = available && project.getService(PluginSettingsState.class).getState().reportAuthState;
        e.getPresentation().setEnabledAndVisible(available);
        if (!available) return;

        AuthStatusService service = project.getService(AuthStatusService.class);
        AuthStatusService.Status status = service.getStatus();
        AuthStatusService.Settings settings = service.getSettings();
        Color color = switch (status) {
            case UNKNOWN, ABSENT -> JBColor.GRAY;
            case CHECKING, RECONNECTING -> JBColor.ORANGE;
            case ACTIVE -> JBColor.GREEN;
            case DISABLED -> JBColor.RED;
        };
        String label = I18n.get(project, "action.auth.label");
        String state = I18n.get(project, "action.auth." + status.name().toLowerCase(Locale.ROOT));
        String endpoint = settings.configured()
                ? I18n.get(project, "action.auth.endpoint", settings.namespace(), settings.service(), AuthStatusService.SERVICE_PORT)
                : I18n.get(project, "action.auth.setup");
        e.getPresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
        e.getPresentation().setIcon(new ColorDotIcon(JBUI.scale(10), color));
        e.getPresentation().setText(label + ": " + state);
        e.getPresentation().setDescription(endpoint);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDefault() || project.isDisposed()) return;
        AuthStatusService service = project.getService(AuthStatusService.class);
        service.refresh();
    }
}
