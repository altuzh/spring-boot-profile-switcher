package configswitcher.ui;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import configswitcher.i18n.I18n;
import configswitcher.model.ConfigFileModel;
import configswitcher.service.ConfigScannerService;
import configswitcher.state.PluginSettingsState;
import configswitcher.util.ConfigNotifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CompareConfigDiffAction extends AnAction implements DumbAware {
    private final ConfigFileModel targetConfig;

    public CompareConfigDiffAction() {
        this(null);
    }

    public CompareConfigDiffAction(@Nullable ConfigFileModel targetConfig) {
        super("Compare with Base (application.yml)...", "Compare this configuration with the default application.yml", null);
        this.targetConfig = targetConfig;
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
        e.getPresentation().setText(I18n.get(project, "action.diff.title"));
        e.getPresentation().setDescription(I18n.get(project, "action.diff.desc"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ConfigScannerService scanner = ConfigScannerService.getInstance(project);
        ConfigFileModel baseConfig = scanner.getBaseConfigFile();

        if (baseConfig == null || baseConfig.getVirtualFile() == null) {
            ConfigNotifier.notifyWarning(project, "Base application.yml was not found in the project to compare against.");
            return;
        }

        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        String selectedProfile = settings.selectedProfile;

        ConfigFileModel compareWith = targetConfig;
        if (compareWith == null) {
            compareWith = scanner.findConfigByProfile(selectedProfile);
        }
        if (compareWith == null) {
            for (ConfigFileModel c : scanner.getConfigurations()) {
                if (!c.isDefault()) {
                    compareWith = c;
                    break;
                }
            }
        }

        if (compareWith == null || compareWith.getVirtualFile() == null) {
            ConfigNotifier.notifyWarning(project, "No profile configuration found to compare with application.yml.");
            return;
        }

        VirtualFile baseVf = baseConfig.getVirtualFile();
        VirtualFile compareVf = compareWith.getVirtualFile();

        if (compareVf.equals(baseVf)) {
            ConfigNotifier.notifyInfo(project, "Selected configuration is already the base application.yml.");
            return;
        }

        DiffContentFactory contentFactory = DiffContentFactory.getInstance();
        DiffContent baseContent = contentFactory.create(project, baseVf);
        DiffContent profileContent = contentFactory.create(project, compareVf);

        String title = "Compare: " + baseConfig.getFileName() + " \u2194 " + compareWith.getFileName() + " [" + compareWith.getProfile() + "]";
        SimpleDiffRequest request = new SimpleDiffRequest(
                title,
                baseContent,
                profileContent,
                baseConfig.getFileName() + " (default)",
                compareWith.getFileName() + " (" + compareWith.getProfile() + ")"
        );

        DiffManager.getInstance().showDiff(project, request);
    }
}
