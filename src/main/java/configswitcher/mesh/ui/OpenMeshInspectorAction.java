package configswitcher.mesh.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.RegisterToolWindowTask;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import configswitcher.util.ConfigSwitcherLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

public class OpenMeshInspectorAction extends DumbAwareAction {

    private static Icon getActionIcon() {
        try {
            return IconLoader.getIcon("/icons/meshIcon.svg", OpenMeshInspectorAction.class);
        } catch (Exception e) {
            return com.intellij.icons.AllIcons.Nodes.Services;
        }
    }

    public OpenMeshInspectorAction() {
        this("Log & Error Analyzer");
    }

    public OpenMeshInspectorAction(String text) {
        super(text,
                "View real-time error analysis, logs, and GraphQL Mesh requests",
                getActionIcon());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        openTab(project, null);
    }

    public static void openTab(@NotNull Project project, @Nullable String tabName) {
        ToolWindowManager twm = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = twm.getToolWindow(MeshToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            toolWindow = twm.getToolWindow(MeshToolWindowFactory.LEGACY_TOOL_WINDOW_ID);
        }
        if (toolWindow == null) {
            try {
                RegisterToolWindowTask task = RegisterToolWindowTask.closable(
                        MeshToolWindowFactory.TOOL_WINDOW_ID,
                        () -> configswitcher.i18n.I18n.get(project, "action.log_analyzer"),
                        getActionIcon()
                );
                toolWindow = twm.registerToolWindow(task);
                new MeshToolWindowFactory().createToolWindowContent(project, toolWindow);
            } catch (Throwable t) {
                ConfigSwitcherLog.warn("Failed to register Log & Error Analyzer tool window dynamically: " + t.getMessage());
            }
        }

        if (toolWindow != null) {
            toolWindow.setStripeTitle(configswitcher.i18n.I18n.get(project, "action.log_analyzer"));
            toolWindow.show(null);
            toolWindow.activate(null, true);
            if (tabName != null) {
                com.intellij.ui.content.ContentManager cm = toolWindow.getContentManager();
                for (com.intellij.ui.content.Content c : cm.getContents()) {
                    if (tabName.equalsIgnoreCase(c.getTabName())
                            || tabName.equalsIgnoreCase(c.getDisplayName())
                            || (tabName.contains("Scanner") && c.getComponent() instanceof MeshLogScannerPanel)
                            || (tabName.contains("Error") && c.getComponent() instanceof MeshErrorAnalysisPanel)
                            || (tabName.contains("Live") && c.getComponent() instanceof MeshInspectorPanel)) {
                        cm.setSelectedContent(c);
                        break;
                    }
                }
            }
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
        e.getPresentation().setText(configswitcher.i18n.I18n.get(project, "action.log_analyzer"));
        e.getPresentation().setDescription(configswitcher.i18n.I18n.get(project, "action.log_analyzer.desc"));
    }
}
