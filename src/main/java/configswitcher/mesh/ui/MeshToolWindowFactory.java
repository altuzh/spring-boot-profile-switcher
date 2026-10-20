package configswitcher.mesh.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import configswitcher.i18n.I18n;
import org.jetbrains.annotations.NotNull;

public class MeshToolWindowFactory implements ToolWindowFactory, DumbAware {

    public static final String TOOL_WINDOW_ID = "Log & Error Analyzer";
    public static final String LEGACY_TOOL_WINDOW_ID = "GraphQL Mesh";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        try {
            toolWindow.setIcon(IconLoader.getIcon("/icons/meshIcon.svg", MeshToolWindowFactory.class));
        } catch (Exception ignored) {}
        toolWindow.setStripeTitle(I18n.get(project, "action.log_analyzer"));

        ContentFactory contentFactory = ContentFactory.getInstance();

        // Tab 1: Live Requests & Streams
        MeshInspectorPanel requestsPanel = new MeshInspectorPanel(project);
        Content requestsContent = contentFactory.createContent(
                requestsPanel,
                I18n.get(project, "mesh.tab.live_requests"),
                false
        );
        requestsContent.setDisposer(requestsPanel);
        requestsContent.setIcon(AllIcons.Nodes.Folder);
        requestsContent.setDescription("Live GraphQL queries, mutations, responses, and latency metrics");
        toolWindow.getContentManager().addContent(requestsContent);

        // Tab 2: Dedicated Error Analysis Studio
        MeshErrorAnalysisPanel errorPanel = new MeshErrorAnalysisPanel(project);
        Content errorContent = contentFactory.createContent(
                errorPanel,
                I18n.get(project, "mesh.tab.error_analysis"),
                false
        );
        errorContent.setDisposer(errorPanel);
        errorContent.setIcon(AllIcons.General.Error);
        errorContent.setDescription("Categorized GraphQL server/validation errors, HTTP 5xx/4xx, timeouts, stack traces, and diagnostics");
        toolWindow.getContentManager().addContent(errorContent);

        // Tab 3: Session Log File Scanner
        MeshLogScannerPanel logScannerPanel = new MeshLogScannerPanel(project);
        Content logContent = contentFactory.createContent(
                logScannerPanel,
                I18n.get(project, "mesh.tab.log_scanner"),
                false
        );
        logContent.setIcon(AllIcons.Debugger.Console);
        logContent.setDescription("Scan session.log, filter log levels, and feed logs into error classifier");
        toolWindow.getContentManager().addContent(logContent);
    }

    public static void reinitToolWindow(@NotNull Project project) {
        if (project.isDisposed()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ToolWindowManager twm = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = twm.getToolWindow(TOOL_WINDOW_ID);
            if (toolWindow == null) {
                toolWindow = twm.getToolWindow(LEGACY_TOOL_WINDOW_ID);
            }
            if (toolWindow == null) return;

            toolWindow.setStripeTitle(I18n.get(project, "action.log_analyzer"));

            ContentManager cm = toolWindow.getContentManager();
            if (cm == null || cm.getContentCount() == 0) {
                return;
            }

            int selectedTabIndex = 0;
            Content selectedContent = cm.getSelectedContent();
            if (selectedContent != null) {
                selectedTabIndex = cm.getIndexOfContent(selectedContent);
            }

            Integer selectedRequestId = null;
            Integer selectedErrorId = null;
            for (Content c : cm.getContents()) {
                if (c.getComponent() instanceof MeshInspectorPanel p) {
                    selectedRequestId = p.getSelectedEntryId();
                } else if (c.getComponent() instanceof MeshErrorAnalysisPanel p) {
                    selectedErrorId = p.getSelectedEntryId();
                }
            }

            cm.removeAllContents(true);
            new MeshToolWindowFactory().createToolWindowContent(project, toolWindow);

            if (selectedTabIndex >= 0 && selectedTabIndex < cm.getContentCount()) {
                cm.setSelectedContent(cm.getContent(selectedTabIndex));
            }

            for (Content c : cm.getContents()) {
                if (c.getComponent() instanceof MeshInspectorPanel p && selectedRequestId != null) {
                    p.selectEntryById(selectedRequestId);
                } else if (c.getComponent() instanceof MeshErrorAnalysisPanel p && selectedErrorId != null) {
                    p.selectEntryById(selectedErrorId);
                }
            }
        });
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
