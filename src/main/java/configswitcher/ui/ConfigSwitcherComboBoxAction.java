package configswitcher.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import configswitcher.i18n.I18n;
import configswitcher.i18n.PluginLanguage;
import configswitcher.model.ConfigFileModel;
import configswitcher.model.ExecutionMode;
import configswitcher.model.SwitchMode;
import configswitcher.service.AppRunManager;
import configswitcher.service.ConfigScannerService;
import configswitcher.service.GitPatchService;
import configswitcher.state.PluginSettingsState;
import configswitcher.util.ConfigNotifier;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.List;

public class ConfigSwitcherComboBoxAction extends ComboBoxAction implements DumbAware {

    private static Icon pluginIcon = null;

    private static synchronized Icon getPluginIcon() {
        if (pluginIcon == null) {
            try {
                pluginIcon = IconLoader.getIcon("/icons/switchIcon.svg", ConfigSwitcherComboBoxAction.class);
            } catch (Exception e) {
                pluginIcon = AllIcons.General.GearPlain;
            }
        }
        return pluginIcon;
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
        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        PluginLanguage lang = settings.language != null ? settings.language : PluginLanguage.EN;
        String profile = settings.selectedProfile;

        String modeLabel = "";
        if (settings.executionMode == ExecutionMode.COMMAND_PIPELINE) {
            modeLabel = " [Pipeline]";
        } else if (settings.switchMode == SwitchMode.FILE_SWAP) {
            modeLabel = " [Swap]";
        }

        e.getPresentation().setText(I18n.get(lang, "action.profile_switcher.text", profile + modeLabel));
        e.getPresentation().setIcon(getPluginIcon());

        String desc = I18n.get(lang, "action.profile_switcher.desc", profile, settings.executionMode.getTitle());
        e.getPresentation().setDescription(desc);
    }

    @Override
    protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext dataContext) {
        DefaultActionGroup group = new DefaultActionGroup();
        Project project = PlatformCoreDataKeys.PROJECT.getData(dataContext);
        if (project == null) return group;

        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        PluginLanguage lang = settings.language != null ? settings.language : PluginLanguage.EN;
        ConfigScannerService scanner = ConfigScannerService.getInstance(project);
        List<ConfigFileModel> configs = scanner.getConfigurations();

        // 1. Header with active mode
        String currentProfile = settings.selectedProfile;
        String modeTitle = settings.executionMode == ExecutionMode.COMMAND_PIPELINE
                ? settings.executionMode.getTitle()
                : settings.switchMode.getTitle();
        group.add(Separator.create(I18n.get(lang, "action.header.spring_configs", modeTitle)));

        // 2. List of detected configuration files
        if (configs.isEmpty()) {
            group.add(new AnAction(I18n.get(lang, "action.no_configs"), I18n.get(lang, "action.no_configs.desc"), null) {
                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.BGT;
                }

                @Override
                public void update(@NotNull AnActionEvent e) {
                    e.getPresentation().setEnabled(false);
                }

                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {}
            });
        } else {
            for (ConfigFileModel config : configs) {
                boolean isSelected = config.getProfile().equalsIgnoreCase(currentProfile);
                boolean isMirrored = settings.switchMode == SwitchMode.FILE_SWAP &&
                        config.getProfile().equalsIgnoreCase(settings.mirroredProfile);

                String itemLabel = config.getDisplayName() + (isMirrored ? " [Mirrored]" : "");
                Icon icon = isSelected ? AllIcons.Actions.Checked : AllIcons.FileTypes.Yaml;

                group.add(new AnAction(itemLabel, "Select and apply profile '" + config.getProfile() + "' (" + config.getRelativePath() + ")", icon) {
                    @Override
                    public @NotNull ActionUpdateThread getActionUpdateThread() {
                        return ActionUpdateThread.BGT;
                    }

                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        settings.selectedProfile = config.getProfile();
                        settings.selectedFilePath = config.getFullPath();

                        AppRunManager.getInstance(project).switchAndRun(config, false, true);
                    }
                });
            }
        }

        group.add(Separator.create(I18n.get(lang, "action.header.actions")));

        // 3. Start / Stop Application
        group.add(new StartStopConfigAction());

        // 4. Rebuild & Restart
        group.add(new RebuildAndRestartAction());

        // 5. Quick Restart (without rebuild)
        group.add(new RunSelectedConfigAction());

        GitPatchService patchService = GitPatchService.getInstance(project);
        if (patchService.hasActivePatches()) {
            int activeCount = patchService.getAppliedPatches().size();
            group.add(new AnAction(
                    I18n.get(lang, "action.revert_patches", activeCount),
                    I18n.get(lang, "action.revert_patches.desc"),
                    AllIcons.Actions.Rollback) {
                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.BGT;
                }

                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    int reverted = patchService.revertAppliedPatches();
                    ConfigNotifier.notifyInfo(project, "Reverted " + reverted + " git patch(es).");
                }
            });
        }

        group.add(Separator.getInstance());

        // 6. Compare with Base Diff Action
        group.add(new CompareConfigDiffAction());

        // 7. Log & Error Analyzer
        group.add(new configswitcher.mesh.ui.OpenMeshInspectorAction(I18n.get(lang, "action.log_analyzer")));

        // 8. Open Session Log Folder
        group.add(new AnAction(
                I18n.get(lang, "action.open_log_folder"),
                I18n.get(lang, "action.open_log_folder.desc"),
                AllIcons.Nodes.Folder) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Project p = e.getProject();
                if (p != null) {
                    configswitcher.service.SessionLogManager.getInstance(p).openLogFolder();
                } else {
                    configswitcher.util.ConfigSwitcherLog.openLogDirectory();
                }
            }
        });

        group.add(Separator.getInstance());

        // 9. Open Settings Dialog
        group.add(new AnAction(
                I18n.get(lang, "action.settings_dialog"),
                I18n.get(lang, "action.settings_dialog.desc"),
                AllIcons.General.Settings) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable.class);
            }
        });

        return group;
    }
}
