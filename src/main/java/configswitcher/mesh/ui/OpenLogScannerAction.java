package configswitcher.mesh.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class OpenLogScannerAction extends DumbAwareAction {

    public OpenLogScannerAction() {
        super("Log File Scanner",
                "Open Log Scanner to inspect and filter session.log",
                AllIcons.Debugger.Console);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            OpenMeshInspectorAction.openTab(project, "Log Scanner");
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
        if (project != null) {
            e.getPresentation().setText(configswitcher.i18n.I18n.get(project, "mesh.tab.log_scanner"));
        }
    }
}
