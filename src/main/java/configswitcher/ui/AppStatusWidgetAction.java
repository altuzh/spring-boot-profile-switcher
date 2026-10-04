package configswitcher.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import configswitcher.service.AppStatusService;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Toolbar widget displayed near the Start/Stop and Rebuild & Restart buttons
 * showing the current application startup stage and health:
 * 1. Tomcat started on port(s): <port> (Grey)
 * 2. WatchDir is started (Yellow)
 * 3. Application started (Green)
 * 4. ERROR (Red)
 */
@SuppressWarnings("deprecation")
public class AppStatusWidgetAction extends AnAction implements CustomComponentAction, DumbAware {

    public AppStatusWidgetAction() {
        super("App Status", "Shows application startup stage and health", null);
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

        AppStatusService statusService = AppStatusService.getInstance(project);
        AppStatusService.StatusInfo info = statusService.getCurrentStatus();

        e.getPresentation().setEnabledAndVisible(true);
        e.getPresentation().setText(info.displayText());
        e.getPresentation().setDescription(info.tooltipText() + " (Click to open Log & Error Analyzer)");
        e.getPresentation().setIcon(info.createIcon(JBUI.scale(10)));
        e.getPresentation().putClientProperty("statusColor", info.color());
        e.getPresentation().putClientProperty("project", project);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;
        openLogAnalyzer(project);
    }

    @Override
    public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
        JBLabel label = new JBLabel();
        label.setBorder(JBUI.Borders.empty(0, 6));
        label.setFont(label.getFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(11)));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setOpaque(false);

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Project p = (Project) presentation.getClientProperty("project");
                if (p != null && !p.isDisposed()) {
                    openLogAnalyzer(p);
                }
            }
        });

        final AppStatusService[] attachedService = new AppStatusService[1];
        final Runnable[] statusListener = new Runnable[1];

        Runnable syncListener = () -> {
            Project p = (Project) presentation.getClientProperty("project");
            if (p != null && !p.isDisposed()) {
                AppStatusService svc = AppStatusService.getInstance(p);
                if (attachedService[0] != svc) {
                    if (attachedService[0] != null && statusListener[0] != null) {
                        attachedService[0].removeListener(statusListener[0]);
                    }
                    attachedService[0] = svc;
                    statusListener[0] = () -> {
                        AppStatusService.StatusInfo status = svc.getCurrentStatus();
                        UIUtil.invokeLaterIfNeeded(() -> {
                            presentation.setText(status.displayText());
                            presentation.setDescription(status.tooltipText() + " (Click to open Log & Error Analyzer)");
                            presentation.setIcon(status.createIcon(JBUI.scale(10)));
                            presentation.putClientProperty("statusColor", status.color());
                            updateFromPresentation(label, presentation);
                        });
                    };
                    svc.addListener(statusListener[0]);
                }
            }
        };

        label.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (!label.isShowing()) {
                    if (attachedService[0] != null && statusListener[0] != null) {
                        attachedService[0].removeListener(statusListener[0]);
                        attachedService[0] = null;
                        statusListener[0] = null;
                    }
                } else {
                    syncListener.run();
                }
            }
        });

        updateFromPresentation(label, presentation);
        syncListener.run();

        presentation.addPropertyChangeListener(evt -> {
            if ("project".equals(evt.getPropertyName())) {
                syncListener.run();
            }
            UIUtil.invokeLaterIfNeeded(() -> updateFromPresentation(label, presentation));
        });

        return label;
    }

    private void updateFromPresentation(JBLabel label, Presentation presentation) {
        label.setText(presentation.getText());
        label.setToolTipText(presentation.getDescription());
        label.setIcon(presentation.getIcon());
        label.setVisible(presentation.isVisible());
        Color color = (Color) presentation.getClientProperty("statusColor");
        if (color != null) {
            label.setForeground(color);
        }
        label.revalidate();
        label.repaint();
    }

    private static void openLogAnalyzer(@NotNull Project project) {
        ToolWindowManager twm = ToolWindowManager.getInstance(project);
        ToolWindow tw = twm.getToolWindow("Log & Error Analyzer");
        if (tw == null) {
            tw = twm.getToolWindow("GraphQL Mesh");
        }
        if (tw != null) {
            tw.activate(null);
        }
    }
}
