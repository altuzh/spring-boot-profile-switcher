package configswitcher.service;

import com.intellij.execution.CommonJavaRunConfigurationParameters;
import com.intellij.execution.CommonProgramRunConfigurationParameters;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.execution.ParametersListUtil;
import configswitcher.model.ArgumentType;
import configswitcher.model.ConfigFileModel;
import configswitcher.model.ExecutionMode;
import configswitcher.model.SwitchMode;
import configswitcher.state.PluginSettingsState;
import configswitcher.util.ConfigNotifier;
import configswitcher.util.ConfigSwitcherLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
@SuppressWarnings("deprecation")
public final class AppRunManager implements Disposable {
    private final Project project;
    private volatile ProcessHandler activePipelineProcess = null;
    private volatile Process activeJavaProcess = null;
    private volatile Long activeJavaPid = null;

    public AppRunManager(@NotNull Project project) {
        this.project = project;
        WindowsJobObjectManager.ensureInitialized();

        // Register JVM shutdown hook for clean termination on IDE exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                terminateActivePipelineProcess();
            } catch (Throwable ignored) {}
        }, "ConfigSwitcher-AppRunManager-ShutdownHook"));

        // Asynchronously check and clean any leftover locks from previous crashed IDEA sessions
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                cleanupOrphanedJarLocks();
            } catch (Throwable t) {
                ConfigSwitcherLog.warn(project, "Startup jar lock cleanup failed: " + t.getMessage());
            }
        });

        // Subscribe to ExecutionManager to capture process output from any standard Run/Debug configs
        try {
            project.getMessageBus().connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, new com.intellij.execution.ExecutionListener() {
                @Override
                public void processStarted(@NotNull String executorId, @NotNull com.intellij.execution.runners.ExecutionEnvironment env, @NotNull ProcessHandler handler) {
                    try {
                        SessionLogManager.getInstance(project).startNewSession(env.getRunProfile().getName());
                    } catch (Throwable ignored) {}
                    handler.addProcessListener(new ProcessAdapter() {
                        @Override
                        public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                            try {
                                if (outputType == com.intellij.execution.process.ProcessOutputTypes.STDOUT || outputType == com.intellij.execution.process.ProcessOutputTypes.STDERR) {
                                    SessionLogManager.getInstance(project).appendOutput(event.getText());
                                    configswitcher.mesh.service.MeshCaptureService.getInstance(project).getAnalyzer().processChunk(event.getText());
                                }
                            } catch (Throwable ignored) {}
                        }

                        @Override
                        public void processTerminated(@NotNull ProcessEvent event) {
                            try {
                                SessionLogManager.getInstance(project).endSession(event.getExitCode());
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            });
        } catch (Throwable t) {
            ConfigSwitcherLog.warn(project, "Failed to subscribe to ExecutionManager.EXECUTION_TOPIC: " + t.getMessage());
        }
    }

    public static AppRunManager getInstance(@NotNull Project project) {
        return project.getService(AppRunManager.class);
    }

    @Override
    public void dispose() {
        ConfigSwitcherLog.info(project, "Disposing AppRunManager for project: " + project.getName() + " - terminating active pipeline and releasing locks...");
        terminateActivePipelineProcess();
    }

    public void cleanupOrphanedJarLocks() {
        if (project.isDisposed()) return;
        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        String targetJarRelative = extractJarPathFromCommand(settings.runCommandTemplate);
        if (targetJarRelative != null) {
            File workDir = resolveWorkDir(settings.runWorkingDir);
            File resolvedJar = new File(targetJarRelative);
            if (!resolvedJar.isAbsolute()) {
                resolvedJar = new File(workDir, targetJarRelative);
            }
            if (resolvedJar.exists() && isFileLocked(resolvedJar)) {
                ConfigSwitcherLog.info(project, "Startup check: build jar is locked by an orphaned process: " + resolvedJar.getAbsolutePath() + ". Releasing locks...");
                releaseJarFileLocks(project, resolvedJar);
            }
        }
    }

    public void registerActiveJavaProcess(@NotNull Process process) {
        this.activeJavaProcess = process;
        this.activeJavaPid = process.pid();
        WindowsJobObjectManager.assignProcess(process);
        ConfigSwitcherLog.info(project, "Tracked active Java process: PID " + activeJavaPid);
    }

    public void clearActiveJavaProcess() {
        this.activeJavaProcess = null;
        this.activeJavaPid = null;
    }

    public @Nullable Long getActiveJavaPid() {
        return activeJavaPid;
    }

    public @Nullable Process getActiveJavaProcess() {
        return activeJavaProcess;
    }

    @NotNull
    public static String updateProgramParameters(
            @Nullable String existing,
            @NotNull String profile,
            @NotNull String configFilePath,
            @NotNull ArgumentType argumentType,
            @Nullable String customArgs
    ) {
        String params = existing != null ? existing.trim() : "";
        params = params.replaceAll("--spring\\.profiles\\.active=\\S+", "").trim();
        params = params.replaceAll("--spring\\.config\\.additional-location=\\S+", "").trim();
        params = params.replaceAll("\\s+", " ").trim();

        List<String> additions = new ArrayList<>();
        switch (argumentType) {
            case SPRING_PROFILES_ACTIVE -> additions.add("--spring.profiles.active=" + profile);
            case SPRING_CONFIG_ADDITIONAL_LOCATION -> additions.add("--spring.config.additional-location=" + configFilePath);
            case BOTH -> {
                additions.add("--spring.profiles.active=" + profile);
                additions.add("--spring.config.additional-location=" + configFilePath);
            }
        }

        if (customArgs != null && !customArgs.isBlank()) {
            additions.add(customArgs.trim());
        }

        String addedString = String.join(" ", additions);
        return params.isEmpty() ? addedString : params + " " + addedString;
    }

    @NotNull
    public static String updateVmParameters(@Nullable String existing, @Nullable String customVmOptions) {
        String vm = existing != null ? existing.trim() : "";
        if (customVmOptions != null && !customVmOptions.isBlank()) {
            String trimmed = customVmOptions.trim();
            if (!vm.contains(trimmed)) {
                vm = vm.isEmpty() ? trimmed : vm + " " + trimmed;
            }
        }
        return vm;
    }

    public void stopApplication() {
        ConfigSwitcherLog.info(project, "Stopping application...");
        terminateActivePipelineProcess();

        RunnerAndConfigurationSettings runSettings = findTargetRunConfiguration();
        if (runSettings != null) {
            ExecutionManager executionManager = ExecutionManager.getInstance(project);
            List<RunContentDescriptor> activeDescriptors = executionManager.getRunningDescriptors(runProfile ->
                    runProfile.getName().equals(runSettings.getName()) || runProfile.equals(runSettings.getConfiguration())
            ).stream().filter(d -> d.getProcessHandler() != null && !d.getProcessHandler().isProcessTerminated()).toList();
            for (RunContentDescriptor descriptor : activeDescriptors) {
                ProcessHandler handler = descriptor.getProcessHandler();
                if (handler != null && !handler.isProcessTerminated()) {
                    handler.destroyProcess();
                }
            }
        }

        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        String targetJarRelative = extractJarPathFromCommand(settings.runCommandTemplate);
        if (targetJarRelative != null) {
            File workDir = resolveWorkDir(settings.runWorkingDir);
            File resolvedJar = new File(targetJarRelative);
            if (!resolvedJar.isAbsolute()) {
                resolvedJar = new File(workDir, targetJarRelative);
            }
            releaseJarFileLocks(project, resolvedJar);
        }

        if (settings.autoRevertPatches) {
            GitPatchService.getInstance(project).revertAppliedPatches();
        }

        try {
            AppStatusService.getInstance(project).onAppStopped();
        } catch (Throwable ignored) {}

        ConfigNotifier.notifyInfo(project, "Application stopped.");
    }

    public void rebuildAndRestart(@NotNull ConfigFileModel config) {
        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        ConfigSwitcherLog.info(project, "Rebuild & Restart triggered for profile: " + config.getProfile());

        boolean hasPipelinePreRun = settings.executionMode == ExecutionMode.COMMAND_PIPELINE
                && settings.preRunCommand != null && !settings.preRunCommand.isBlank();

        if (hasPipelinePreRun) {
            switchAndRun(config, false, false, true);
            return;
        }

        ConfigNotifier.notifyInfo(project, "Building project before restart...");
        try {
            AppStatusService.getInstance(project).setStage(AppStatusService.AppStartupStage.BUILDING);
        } catch (Throwable ignored) {}
        CompilerManager compilerManager = CompilerManager.getInstance(project);
        compilerManager.make((aborted, errors, warnings, compileContext) -> {
            if (aborted) {
                ConfigNotifier.notifyWarning(project, "Build was cancelled.");
                try {
                    AppStatusService.getInstance(project).onAppStopped();
                } catch (Throwable ignored) {}
                return;
            }
            if (errors > 0) {
                ConfigNotifier.notifyError(project, "Build failed with " + errors + " error(s). Aborting restart.");
                try {
                    AppStatusService.getInstance(project).onPreRunFailed("Build failed with " + errors + " error(s)");
                } catch (Throwable ignored) {}
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    switchAndRun(config, false);
                }
            });
        });
    }

    public void switchAndRun(@NotNull ConfigFileModel config, boolean isDebug) {
        switchAndRun(config, isDebug, true, false);
    }

    public void switchAndRun(@NotNull ConfigFileModel config, boolean isDebug, boolean skipPreRun) {
        switchAndRun(config, isDebug, skipPreRun, false);
    }

    public void switchAndRun(@NotNull ConfigFileModel config, boolean isDebug, boolean skipPreRun, boolean forcePreRun) {
        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        settings.selectedProfile = config.getProfile();
        settings.selectedFilePath = config.getFullPath();

        ConfigSwitcherLog.info(project, "Switching configuration to profile: " + config.getProfile() + " (executionMode=" + settings.executionMode + ", switchMode=" + settings.switchMode + ")");

        if (settings.executionMode == ExecutionMode.COMMAND_PIPELINE) {
            executeCommandPipeline(config, isDebug, skipPreRun, forcePreRun);
            return;
        }

        if (settings.switchMode == SwitchMode.PROFILE_ARGUMENTS) {
            handleProfileArgumentsMode(config, isDebug, settings.autoRestart);
        } else {
            handleFileSwapMode(config, isDebug, settings.autoRestart);
        }
    }

    private void handleProfileArgumentsMode(@NotNull ConfigFileModel config, boolean isDebug, boolean autoRestart) {
        RunnerAndConfigurationSettings runSettings = findTargetRunConfiguration();
        if (runSettings == null) {
            ConfigNotifier.notifyWarning(
                    project,
                    "Profile set to '" + config.getProfile() + "', but no suitable Run Configuration was found to update parameters."
            );
            return;
        }

        RunConfiguration configuration = runSettings.getConfiguration();
        if (configuration instanceof CommonProgramRunConfigurationParameters programParams) {
            PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
            String profile = config.getProfile();
            String customArgs = getEffectiveCustomArgs(profile, settings.profileCustomProgramArgs);
            String customVm = getEffectiveCustomVm(profile, settings.profileCustomVmOptions);

            String updatedProgramParams = updateProgramParameters(
                    programParams.getProgramParameters(),
                    profile,
                    config.getFullPath(),
                    ArgumentType.SPRING_PROFILES_ACTIVE,
                    customArgs
            );
            programParams.setProgramParameters(updatedProgramParams);

            if (configuration instanceof CommonJavaRunConfigurationParameters javaParams) {
                String updatedVmParams = updateVmParameters(javaParams.getVMParameters(), customVm);
                javaParams.setVMParameters(updatedVmParams);
            }

            ConfigNotifier.notifyInfo(
                    project,
                    "Configured '" + runSettings.getName() + "' with profile '" + profile + "'. Program parameters updated."
            );

            if (autoRestart) {
                executeOrRestart(runSettings, isDebug);
            }
        } else {
            ConfigNotifier.notifyWarning(
                    project,
                    "Run Configuration '" + runSettings.getName() + "' does not support program parameters directly."
            );
        }
    }

    private void handleFileSwapMode(@NotNull ConfigFileModel config, boolean isDebug, boolean autoRestart) {
        ConfigScannerService scanner = ConfigScannerService.getInstance(project);
        ConfigFileModel baseConfig = scanner.getBaseConfigFile();

        if (baseConfig == null || baseConfig.getVirtualFile() == null) {
            ConfigNotifier.notifyError(
                    project,
                    "Base 'application.yml' was not found in the project. Cannot swap files."
            );
            return;
        }

        VirtualFile targetVirtualFile = config.getVirtualFile();
        if (targetVirtualFile == null) {
            ConfigNotifier.notifyError(
                    project,
                    "Selected configuration file for profile '" + config.getProfile() + "' does not exist on disk."
            );
            return;
        }

        VirtualFile baseFile = baseConfig.getVirtualFile();
        VirtualFile parentDir = baseFile.getParent();
        if (parentDir == null) return;

        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();

        WriteCommandAction.runWriteCommandAction(project, "Swap Application Configuration", "SpringBootProfileSwitcher", () -> {
            try {
                // 1. Create backup if requested and not already present
                if (settings.backupFileBeforeSwap) {
                    VirtualFile bakFile = parentDir.findChild(baseFile.getName() + ".bak");
                    if (bakFile == null) {
                        VirtualFile newBak = parentDir.createChildData(this, baseFile.getName() + ".bak");
                        VfsUtil.saveText(newBak, VfsUtil.loadText(baseFile));
                    }
                }

                // 2. Overwrite application.yml with contents of the selected profile
                String newContent = VfsUtil.loadText(targetVirtualFile);
                VfsUtil.saveText(baseFile, newContent);
                baseFile.refresh(false, false);

                settings.mirroredProfile = config.getProfile();

                ConfigNotifier.notifyInfo(
                        project,
                        "Swapped '" + baseFile.getName() + "' with contents from '" + config.getFileName() + "' (" + config.getProfile() + ")."
                );
            } catch (IOException e) {
                ConfigNotifier.notifyError(
                        project,
                        "Failed to swap configuration files: " + e.getMessage()
                );
            }
        });

        if (autoRestart) {
            RunnerAndConfigurationSettings runSettings = findTargetRunConfiguration();
            if (runSettings != null) {
                executeOrRestart(runSettings, isDebug);
            }
        }
    }

    @Nullable
    public RunnerAndConfigurationSettings findTargetRunConfiguration() {
        RunManager runManager = RunManager.getInstance(project);
        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();

        // 1. Explicit target name from settings
        if (!settings.targetRunConfigName.isBlank()) {
            for (RunnerAndConfigurationSettings s : runManager.getAllSettings()) {
                if (s.getName().equals(settings.targetRunConfigName)) {
                    return s;
                }
            }
        }

        // 2. Selected configuration if it supports parameters
        RunnerAndConfigurationSettings selected = runManager.getSelectedConfiguration();
        if (selected != null && selected.getConfiguration() instanceof CommonProgramRunConfigurationParameters) {
            return selected;
        }

        // 3. Search for configuration by common application names
        for (RunnerAndConfigurationSettings runSetting : runManager.getAllSettings()) {
            RunConfiguration config = runSetting.getConfiguration();
            if (config instanceof CommonProgramRunConfigurationParameters) {
                if (config.getName().toLowerCase().contains("application")) {
                    return runSetting;
                }
            }
        }

        // 4. Fallback to any Java/Spring run configuration
        for (RunnerAndConfigurationSettings s : runManager.getAllSettings()) {
            if (s.getConfiguration() instanceof CommonProgramRunConfigurationParameters) {
                return s;
            }
        }

        return selected;
    }

    public void executeOrRestart(@NotNull RunnerAndConfigurationSettings runSettings, boolean isDebug) {
        Executor executor = isDebug
                ? DefaultDebugExecutor.getDebugExecutorInstance()
                : DefaultRunExecutor.getRunExecutorInstance();

        ExecutionManager executionManager = ExecutionManager.getInstance(project);
        List<RunContentDescriptor> activeDescriptors = executionManager.getRunningDescriptors(runProfile ->
                runProfile.getName().equals(runSettings.getName()) || runProfile.equals(runSettings.getConfiguration())
        ).stream().filter(d -> d.getProcessHandler() != null && !d.getProcessHandler().isProcessTerminated()).toList();

        if (!activeDescriptors.isEmpty()) {
            AtomicInteger pendingTerminations = new AtomicInteger(activeDescriptors.size());
            for (RunContentDescriptor descriptor : activeDescriptors) {
                ProcessHandler handler = descriptor.getProcessHandler();
                if (handler != null && !handler.isProcessTerminated()) {
                    handler.addProcessListener(new ProcessAdapter() {
                        @Override
                        public void processTerminated(@NotNull ProcessEvent event) {
                            if (pendingTerminations.decrementAndGet() <= 0) {
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    if (!project.isDisposed()) {
                                        ProgramRunnerUtil.executeConfiguration(runSettings, executor);
                                    }
                                });
                            }
                        }
                    });
                    handler.destroyProcess();
                } else {
                    pendingTerminations.decrementAndGet();
                }
            }

            if (pendingTerminations.get() <= 0) {
                ProgramRunnerUtil.executeConfiguration(runSettings, executor);
            }
        } else {
            ProgramRunnerUtil.executeConfiguration(runSettings, executor);
        }
    }

    public void executeCommandPipeline(@NotNull ConfigFileModel config, boolean isDebug, boolean skipPreRun) {
        executeCommandPipeline(config, isDebug, skipPreRun, false);
    }

    public void executeCommandPipeline(@NotNull ConfigFileModel config, boolean isDebug, boolean skipPreRun, boolean forcePreRun) {
        boolean wasRunning = isAppRunning();
        terminateActivePipelineProcess();

        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        if (PluginSettingsState.isLegacyDefaultTemplate(settings.runCommandTemplate)) {
            settings.runCommandTemplate = calculateDefaultCommandTemplate(project);
        }

        // Ensure build jar file locks are released so the build can overwrite/release it
        String targetJarRelative = extractJarPathFromCommand(settings.runCommandTemplate);
        File resolvedJar = null;
        if (targetJarRelative != null) {
            File workDir = resolveWorkDir(settings.runWorkingDir);
            resolvedJar = new File(targetJarRelative);
            if (!resolvedJar.isAbsolute()) {
                resolvedJar = new File(workDir, targetJarRelative);
            }
            releaseJarFileLocks(project, resolvedJar);
        }

        String profile = config.getProfile();

        // Determine if pre-run build stage should be skipped:
        // If app was already running and skipPreRunIfRunning is true, don't run pre-run stage as it always builds the app
        boolean shouldSkipPreRun = !forcePreRun && (skipPreRun || (wasRunning && settings.skipPreRunIfRunning) || settings.skipPreRunOnQuickSwitch);
        if (shouldSkipPreRun && resolvedJar != null && !resolvedJar.exists()) {
            ConfigSwitcherLog.info(project, "Build jar does not exist on disk; forcing Pre-Run build.");
            shouldSkipPreRun = false;
        }

        if (wasRunning && settings.enablePreRun && shouldSkipPreRun) {
            ConfigSwitcherLog.info(project, "Application was running; skipping Pre-Run build stage for profile switch.");
            ConfigNotifier.notifyInfo(project, "Application was running. Skipped pre-run build for profile switch.");
        }

        // Build GeneralCommandLine for Pre-Run (if enabled or force-rebuilding, and not skipped)
        GeneralCommandLine preRunCmd = null;
        if ((settings.enablePreRun || forcePreRun) && !shouldSkipPreRun && settings.preRunCommand != null && !settings.preRunCommand.isBlank()) {
            List<String> tokens = ParametersListUtil.parse(settings.preRunCommand);
            if (!tokens.isEmpty()) {
                File workDir = resolveWorkDir(settings.preRunWorkingDir);
                List<String> resolvedTokens = resolveCommandTokens(tokens, workDir);
                preRunCmd = new GeneralCommandLine(resolvedTokens);
                preRunCmd.setWorkDirectory(workDir);
            }
        }

        // Stage 1: Git Patches (Only applied before pre-run build stage)
        GitPatchService patchService = GitPatchService.getInstance(project);
        if (patchService.hasActivePatches()) {
            patchService.revertAppliedPatches();
        }

        if (preRunCmd != null) {
            if (!patchService.applyConfiguredPatches(profile)) {
                ConfigNotifier.notifyError(project, "Pre-run aborted due to git patch failure.");
                return;
            }
        } else {
            ConfigSwitcherLog.info(project, "Pre-Run build stage was skipped; skipping git patch apply.");
        }

        // Build GeneralCommandLine for Application Stage
        String customArgs = getEffectiveCustomArgs(profile, settings.profileCustomProgramArgs);
        String customVm = getEffectiveCustomVm(profile, settings.profileCustomVmOptions);
        String rawRunCommand = buildRunCommandLine(
                settings.runCommandTemplate,
                profile,
                config.getFullPath(),
                customArgs,
                customVm
        );
        List<String> appTokens = ParametersListUtil.parse(rawRunCommand);
        if (appTokens.isEmpty()) {
            ConfigNotifier.notifyError(project, "Run command template is empty.");
            return;
        }
        File appWorkDir = resolveWorkDir(settings.runWorkingDir);
        List<String> resolvedAppTokens = resolveCommandTokens(appTokens, appWorkDir);
        GeneralCommandLine appCmd = new GeneralCommandLine(resolvedAppTokens);
        appCmd.setWorkDirectory(appWorkDir);

        try {
            SessionLogManager.getInstance(project).startNewSession(profile);
        } catch (Throwable ignored) {}

        try {
            AppStatusService statusService = AppStatusService.getInstance(project);
            statusService.reset();
            if (preRunCmd != null) {
                statusService.setStage(AppStatusService.AppStartupStage.BUILDING);
            } else {
                statusService.setStage(AppStatusService.AppStartupStage.STARTING);
            }
        } catch (Throwable ignored) {}

        Runnable onPreRunSuccess = () -> {
            ConfigNotifier.notifyInfo(project, "Pre-run build completed successfully.");
            try {
                AppStatusService.getInstance(project).setStage(AppStatusService.AppStartupStage.STARTING);
            } catch (Throwable ignored) {}
            if (settings.autoRevertPatches && patchService.hasActivePatches()) {
                if (!project.isDisposed()) {
                    ConfigSwitcherLog.info(project, "Pre-run build stage finished successfully; reverting applied git patches.");
                    patchService.revertAppliedPatches();
                }
            }
        };

        Runnable onPreRunFailure = () -> {
            ConfigNotifier.notifyError(project, "Pre-run build failed. Aborting launch.");
            try {
                AppStatusService.getInstance(project).onPreRunFailed("Pre-run build failed");
            } catch (Throwable ignored) {}
            clearActiveJavaProcess();
            activePipelineProcess = null;
            try {
                SessionLogManager.getInstance(project).endSession(1);
            } catch (Throwable ignored) {}
            if (settings.autoRevertPatches && patchService.hasActivePatches()) {
                if (!project.isDisposed()) {
                    ConfigSwitcherLog.info(project, "Pre-run build stage failed; reverting applied git patches.");
                    patchService.revertAppliedPatches();
                }
            }
        };

        Runnable onAppTerminated = () -> {
            clearActiveJavaProcess();
            activePipelineProcess = null;
            try {
                AppStatusService.getInstance(project).onAppTerminated(0);
            } catch (Throwable ignored) {}
            try {
                SessionLogManager.getInstance(project).endSession(0);
            } catch (Throwable ignored) {}
            if (settings.autoRevertPatches && patchService.hasActivePatches()) {
                if (!project.isDisposed()) {
                    patchService.revertAppliedPatches();
                }
            }
        };

        String tabTitle = "Spring: " + profile;

        if (settings.executeInTerminal) {
            TerminalRunnerService.runPipelineInTerminalConsole(
                    project,
                    preRunCmd,
                    appCmd,
                    tabTitle,
                    onPreRunSuccess,
                    onPreRunFailure,
                    onAppTerminated,
                    proc -> registerActiveJavaProcess(proc),
                    handler -> activePipelineProcess = handler
            );
        } else {
            runPipelineInRunToolWindow(
                    project,
                    preRunCmd,
                    appCmd,
                    tabTitle,
                    isDebug,
                    onPreRunSuccess,
                    onPreRunFailure,
                    onAppTerminated,
                    proc -> registerActiveJavaProcess(proc)
            );
        }
    }

    private void runPipelineInRunToolWindow(
            @NotNull Project project,
            @Nullable GeneralCommandLine preRunCmd,
            @NotNull GeneralCommandLine appCmd,
            @NotNull String tabTitle,
            boolean isDebug,
            @Nullable Runnable onPreRunSuccess,
            @Nullable Runnable onPreRunFailure,
            @Nullable Runnable onAppTerminated,
            @Nullable Consumer<Process> onAppProcessStarted
    ) {
        Consumer<String> rawConsumer = text -> {
            try {
                SessionLogManager.getInstance(project).appendOutput(text);
                configswitcher.mesh.service.MeshCaptureService.getInstance(project).getAnalyzer().processChunk(text);
                AppStatusService.getInstance(project).processChunk(text);
            } catch (Throwable ignored) {}
        };
        configswitcher.util.TerminalOutputFilter outputFilter = new configswitcher.util.TerminalOutputFilter(
                () -> PluginSettingsState.getInstance(project).getState()
        );

        Consumer<Integer> onExit = exitCode -> {
            try {
                AppStatusService.getInstance(project).onAppTerminated(exitCode);
            } catch (Throwable ignored) {}
        };

        PipelineProcessHandler pipelineHandler = new PipelineProcessHandler(
                project,
                preRunCmd,
                appCmd,
                onPreRunSuccess,
                onPreRunFailure,
                onAppTerminated,
                onExit,
                onAppProcessStarted,
                rawConsumer,
                outputFilter
        );
        activePipelineProcess = pipelineHandler;

        TextConsoleBuilder builder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        ConsoleView console = builder.getConsole();
        console.attachToProcess(pipelineHandler);

        Executor executor = isDebug ? DefaultDebugExecutor.getDebugExecutorInstance() : DefaultRunExecutor.getRunExecutorInstance();
        RunContentDescriptor descriptor = new RunContentDescriptor(
                console,
                pipelineHandler,
                console.getComponent(),
                tabTitle
        );

        RunContentManager.getInstance(project).showRunContent(executor, descriptor);
        pipelineHandler.startNotify();
    }

    public void terminateActivePipelineProcess() {
        // 1. Forcibly kill tracked Java process tree
        Long javaPid = activeJavaPid;
        Process javaProc = activeJavaProcess;
        if (javaPid != null || (javaProc != null && javaProc.isAlive())) {
            ConfigSwitcherLog.info(project, "Killing tracked Java process PID " + javaPid + " on restart...");
            if (javaPid != null) {
                PipelineProcessHandler.killProcessTreeByPid(project, javaPid);
            }
            if (javaProc != null && javaProc.isAlive()) {
                try {
                    javaProc.toHandle().descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                    javaProc.destroyForcibly();
                    javaProc.waitFor(2000, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {}
            }
            clearActiveJavaProcess();
        }

        // 2. Terminate the pipeline ProcessHandler
        ProcessHandler p = activePipelineProcess;
        if (p != null && !p.isProcessTerminated()) {
            ConfigSwitcherLog.info(project, "Terminating active pipeline process...");
            p.destroyProcess();
            long deadline = System.currentTimeMillis() + 1500;
            while (!p.isProcessTerminated() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            activePipelineProcess = null;
        }
    }

    public boolean isPipelineRunning() {
        ProcessHandler p = activePipelineProcess;
        return p != null && !p.isProcessTerminated();
    }

    public boolean isAppRunning() {
        if (isPipelineRunning()) return true;
        Process proc = activeJavaProcess;
        if (proc != null && proc.isAlive()) return true;
        Long pid = activeJavaPid;
        if (pid != null) {
            try {
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return isRunConfigRunning();
    }

    public boolean isRunConfigRunning() {
        RunnerAndConfigurationSettings runSettings = findTargetRunConfiguration();
        if (runSettings == null) return false;
        ExecutionManager executionManager = ExecutionManager.getInstance(project);
        return executionManager.getRunningDescriptors(runProfile ->
                runProfile.getName().equals(runSettings.getName()) || runProfile.equals(runSettings.getConfiguration())
        ).stream().anyMatch(d -> d.getProcessHandler() != null && !d.getProcessHandler().isProcessTerminated());
    }

    public static boolean isFileLocked(@NotNull File file) {
        if (!file.exists() || !file.isFile()) return false;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            java.nio.channels.FileLock lock = raf.getChannel().tryLock();
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean waitUntilFileUnlocked(@NotNull File file, long timeoutMs) {
        if (!file.exists()) return true;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isFileLocked(file)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return !isFileLocked(file);
    }

    @Nullable
    public static String extractJarPathFromCommand(@Nullable String command) {
        if (command == null || command.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:-jar)\\s+[\"']?([^\"'\\s]+)[\"']?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(command);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    @NotNull
    public static List<Long> findJavaPidsForJar(@NotNull File jarFile) {
        List<Long> pids = new ArrayList<>();
        String jarName = jarFile.getName().toLowerCase();
        String jarPath = jarFile.getAbsolutePath().toLowerCase();

        if (SystemInfo.isWindows) {
            // 1. Try jps -l
            String jpsExe = resolveJpsExecutable();
            try {
                Process p = new ProcessBuilder(jpsExe, "-l").start();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String lower = line.toLowerCase();
                        if (lower.contains(jarName) || lower.contains(jarPath)) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length > 0) {
                                try {
                                    pids.add(Long.parseLong(parts[0]));
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
                p.waitFor(1, TimeUnit.SECONDS);
            } catch (Throwable ignored) {}

            // 2. Fallback to PowerShell Get-CimInstance Win32_Process
            if (pids.isEmpty()) {
                try {
                    Process p = new ProcessBuilder(
                            "powershell.exe",
                            "-NoProfile",
                            "-NonInteractive",
                            "-Command",
                            "Get-CimInstance Win32_Process -Filter \"Name like '%java%'\" | ForEach-Object { \"$($_.ProcessId)::$($_.CommandLine)\" }"
                    ).start();
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String lower = line.toLowerCase();
                            if (lower.contains(jarName) || lower.contains(jarPath)) {
                                int delim = line.indexOf("::");
                                if (delim > 0) {
                                    try {
                                        pids.add(Long.parseLong(line.substring(0, delim).trim()));
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                    }
                    p.waitFor(2, TimeUnit.SECONDS);
                } catch (Throwable ignored) {}
            }
        }
        return pids;
    }

    @NotNull
    private static String resolveJpsExecutable() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            File jps = new File(javaHome, SystemInfo.isWindows ? "bin/jps.exe" : "bin/jps");
            if (jps.isFile()) return jps.getAbsolutePath();
        }
        String jdkHome = System.getProperty("java.home");
        if (jdkHome != null && !jdkHome.isBlank()) {
            File jps = new File(jdkHome, SystemInfo.isWindows ? "bin/jps.exe" : "bin/jps");
            if (jps.isFile()) return jps.getAbsolutePath();
        }
        return "jps";
    }

    public static void releaseJarFileLocks(@NotNull Project project, @Nullable File jarFile) {
        if (jarFile == null || !jarFile.exists()) return;

        if (!isFileLocked(jarFile)) {
            ConfigSwitcherLog.info(project, "Jar file is already unlocked: " + jarFile.getAbsolutePath());
            return;
        }

        ConfigSwitcherLog.info(project, "Jar file is locked: " + jarFile.getAbsolutePath() + ". Hunting and terminating locking processes...");

        // 1. Terminate tracked Java process if recorded
        AppRunManager runMgr = AppRunManager.getInstance(project);
        Long trackedPid = runMgr.getActiveJavaPid();
        if (trackedPid != null) {
            ConfigSwitcherLog.info(project, "Killing tracked Java process PID " + trackedPid + " to release jar lock.");
            PipelineProcessHandler.killProcessTreeByPid(project, trackedPid);
            runMgr.clearActiveJavaProcess();
        }

        // 2. Discover and terminate any Java processes referencing the jar (jps -l / Win32_Process)
        List<Long> lockingPids = findJavaPidsForJar(jarFile);
        for (Long pid : lockingPids) {
            ConfigSwitcherLog.info(project, "Forcibly killing Java process holding jar lock: PID " + pid);
            PipelineProcessHandler.killProcessTreeByPid(project, pid);
        }

        // 3. Fallback: ProcessHandle inspection
        String jarName = jarFile.getName().toLowerCase();
        String jarPath = jarFile.getAbsolutePath().toLowerCase();
        try {
            ProcessHandle.allProcesses().filter(ProcessHandle::isAlive).forEach(ph -> {
                try {
                    ProcessHandle.Info info = ph.info();
                    String cmd = info.commandLine().orElse("");
                    String exec = info.command().orElse("").toLowerCase();
                    if (exec.contains("java") && (cmd.toLowerCase().contains(jarName) || cmd.toLowerCase().contains(jarPath))) {
                        ConfigSwitcherLog.info(project, "Forcibly terminating process locking build jar: PID " + ph.pid());
                        ph.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                        ph.destroyForcibly();
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            ConfigSwitcherLog.warn(project, "Failed to inspect processes for jar lock: " + t.getMessage());
        }

        // 4. Poll until the lock is truly released
        boolean unlocked = waitUntilFileUnlocked(jarFile, 3000);
        if (unlocked) {
            ConfigSwitcherLog.info(project, "Build jar lock released successfully: " + jarFile.getAbsolutePath());
        } else {
            ConfigSwitcherLog.warn(project, "Build jar is still locked after timeout: " + jarFile.getAbsolutePath());
        }
    }

    private File resolveWorkDir(@Nullable String configuredDir) {
        if (configuredDir != null && !configuredDir.isBlank()) {
            File dir = new File(configuredDir);
            if (dir.isAbsolute() && dir.exists()) return dir;
            String basePath = project.getBasePath();
            if (basePath != null) {
                File relative = new File(basePath, configuredDir);
                if (relative.exists()) return relative;
            }
        }
        String basePath = project.getBasePath();
        return basePath != null ? new File(basePath) : new File(".");
    }

    @NotNull
    public static String combineProfiles(@NotNull String selectedProfile, @Nullable String staticProfiles) {
        if (staticProfiles == null || staticProfiles.isBlank()) {
            return selectedProfile.trim();
        }
        List<String> staticList = Arrays.stream(staticProfiles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (staticList.isEmpty()) {
            return selectedProfile.trim();
        }

        String sel = selectedProfile.trim();
        if (sel.isEmpty()) {
            return String.join(",", staticList);
        }

        List<String> combined = new ArrayList<>();
        combined.add(sel);
        for (String s : staticList) {
            if (!s.equalsIgnoreCase(sel) && !combined.contains(s)) {
                combined.add(s);
            }
        }
        return String.join(",", combined);
    }

    @NotNull
    public static String getEffectiveProfile(@NotNull String selectedProfile, @NotNull PluginSettingsState.State settings) {
        return selectedProfile.trim();
    }

    @NotNull
    public static String getEffectiveCustomArgs(@NotNull String effectiveProfile, @NotNull Map<String, String> profileCustomProgramArgs) {
        List<String> args = new ArrayList<>();
        for (String p : effectiveProfile.split(",")) {
            String trimmed = p.trim();
            String val = profileCustomProgramArgs.get(trimmed);
            if (val != null && !val.isBlank() && !args.contains(val.trim())) {
                args.add(val.trim());
            }
        }
        return String.join(" ", args);
    }

    @NotNull
    public static String getEffectiveCustomVm(@NotNull String effectiveProfile, @NotNull Map<String, String> profileCustomVmOptions) {
        List<String> vms = new ArrayList<>();
        for (String p : effectiveProfile.split(",")) {
            String trimmed = p.trim();
            String val = profileCustomVmOptions.get(trimmed);
            if (val != null && !val.isBlank() && !vms.contains(val.trim())) {
                vms.add(val.trim());
            }
        }
        return String.join(" ", vms);
    }

    @NotNull
    public static String buildRunCommandLine(
            @Nullable String template,
            @NotNull String profile,
            @NotNull String configFilePath,
            @Nullable String customArgs,
            @Nullable String customVm
    ) {
        return buildRunCommandLine(template, profile, configFilePath, customArgs, customVm, null, null);
    }

    @NotNull
    public static String buildRunCommandLine(
            @Nullable String template,
            @NotNull String profile,
            @NotNull String configFilePath,
            @Nullable String customArgs,
            @Nullable String customVm,
            @Nullable String selectedProfile,
            @Nullable String staticProfiles
    ) {
        String cmd = template != null && !template.isBlank()
                ? template.trim()
                : "java -jar target/server.jar --debug --spring.profiles.active={profile},auth-dev";

        boolean hadProfilePlaceholder = cmd.contains("{profile}")
                || cmd.contains("{peofile}")
                || cmd.contains("{selectedProfile}");

        // Replace placeholders
        if (selectedProfile != null) {
            cmd = cmd.replace("{selectedProfile}", selectedProfile.trim());
        } else {
            cmd = cmd.replace("{selectedProfile}", profile);
        }
        if (staticProfiles != null) {
            cmd = cmd.replace("{staticProfiles}", staticProfiles.trim());
        }
        cmd = cmd.replace("{profile}", profile);
        cmd = cmd.replace("{peofile}", profile);
        cmd = cmd.replace("{filePath}", configFilePath);
        cmd = cmd.replace("{configPath}", configFilePath);
        cmd = cmd.replace("{customArgs}", customArgs != null ? customArgs.trim() : "");
        cmd = cmd.replace("{customVm}", customVm != null ? customVm.trim() : "");

        // Ensure --spring.profiles.active is present if not in template
        if (!cmd.contains("--spring.profiles.active=")) {
            cmd += " --spring.profiles.active=" + profile;
        } else if (!hadProfilePlaceholder) {
            cmd = cmd.replaceAll("--spring\\.profiles\\.active=\\S+", "--spring.profiles.active=" + profile);
        }

        if (customArgs != null && !customArgs.isBlank() && !cmd.contains(customArgs.trim())) {
            cmd += " " + customArgs.trim();
        }

        return normalizeCommandLines(cmd);
    }

    @NotNull
    public static String normalizeCommandLines(@NotNull String cmd) {
        String normalized = cmd.replace("\r\n", "\n").replace('\r', '\n');
        String[] rawLines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i].stripTrailing();
            if (line.isBlank() && (i == 0 || i == rawLines.length - 1)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(line);
        }
        return sb.toString();
    }

    @NotNull
    public static List<String> resolveCommandTokens(@NotNull List<String> tokens, @Nullable File workDir) {
        if (tokens.isEmpty()) return tokens;

        List<String> resolved = new ArrayList<>(tokens);
        String exe = tokens.get(0);

        if (!SystemInfo.isWindows) {
            return resolved;
        }

        String lower = exe.toLowerCase();
        if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".com")) {
            if (workDir != null) {
                File local = new File(workDir, exe);
                if (local.isFile()) {
                    resolved.set(0, local.getAbsolutePath());
                    return resolved;
                }
            }
            return resolved;
        }

        String cleanExe = exe;
        if (cleanExe.startsWith("./") || cleanExe.startsWith(".\\")) {
            cleanExe = cleanExe.substring(2);
        }

        // 1. Check local working directory for script/binary (.cmd, .bat, .exe)
        if (workDir != null) {
            for (String ext : new String[]{".cmd", ".bat", ".exe"}) {
                File local = new File(workDir, cleanExe + ext);
                if (local.isFile()) {
                    resolved.set(0, local.getAbsolutePath());
                    return resolved;
                }
            }
        }

        // 2. Check system PATH for Windows executable extensions (.cmd, .bat, .exe)
        for (String ext : new String[]{".cmd", ".bat", ".exe"}) {
            File inPath = PathEnvironmentVariableUtil.findInPath(cleanExe + ext);
            if (inPath != null && inPath.isFile()) {
                resolved.set(0, inPath.getAbsolutePath());
                return resolved;
            }
        }

        // 3. Fallback: check PathEnvironmentVariableUtil.findInPath(cleanExe)
        File inPath = PathEnvironmentVariableUtil.findInPath(cleanExe);
        if (inPath != null && inPath.isFile()) {
            File parent = inPath.getParentFile();
            if (parent != null) {
                for (String ext : new String[]{".cmd", ".bat", ".exe"}) {
                    File sibling = new File(parent, cleanExe + ext);
                    if (sibling.isFile()) {
                        resolved.set(0, sibling.getAbsolutePath());
                        return resolved;
                    }
                }
            }
            resolved.set(0, inPath.getAbsolutePath());
            return resolved;
        }

        return resolved;
    }

    @Nullable
    public static File resolveProjectDir(@Nullable Project project) {
        if (project == null) return null;
        if (project.getBasePath() != null && !project.getBasePath().isBlank()) {
            return new File(project.getBasePath());
        }
        try {
            com.intellij.openapi.vfs.VirtualFile vf = com.intellij.openapi.project.ProjectUtil.guessProjectDir(project);
            if (vf != null) {
                return new File(vf.getPath());
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @NotNull
    public static String calculateDefaultCommandTemplate(@Nullable Project project) {
        File projectDir = resolveProjectDir(project);
        if (projectDir == null) {
            return "java -jar target/server.jar --debug --spring.profiles.active={profile},auth-dev";
        }
        String targetJar = findTargetJar(projectDir);
        String n2oConfigPath = findN2oConfigPath(projectDir);

        StringBuilder sb = new StringBuilder("java -jar ");
        if (targetJar.contains(" ")) {
            sb.append("\"").append(targetJar).append("\"");
        } else {
            sb.append(targetJar);
        }
        sb.append(" --debug");

        if (n2oConfigPath != null && !n2oConfigPath.isBlank()) {
            sb.append(" --n2o.config.path=");
            if (n2oConfigPath.contains(" ")) {
                sb.append("\"").append(n2oConfigPath).append("\"");
            } else {
                sb.append(n2oConfigPath);
            }
        }
        sb.append(" --spring.profiles.active={profile},auth-dev");

        return sb.toString();
    }

    @NotNull
    public static String findTargetJar(@NotNull File projectDir) {
        // 1. Direct target directory in project root
        File rootTarget = new File(projectDir, "target");
        String jarInRoot = pickBestJarInDir(rootTarget, projectDir);
        if (jarInRoot != null) {
            return jarInRoot;
        }

        // 2. Scan immediate subdirectories for <module>/target/*.jar
        File[] children = projectDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !isIgnoredDir(child.getName())) {
                    File moduleTarget = new File(child, "target");
                    String jar = pickBestJarInDir(moduleTarget, projectDir);
                    if (jar != null) {
                        return jar;
                    }
                }
            }
        }

        // 3. Fallback: check if any module has a target directory
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !isIgnoredDir(child.getName())) {
                    File moduleTarget = new File(child, "target");
                    if (moduleTarget.isDirectory()) {
                        return (child.getName() + "/target/server.jar").replace('\\', '/');
                    }
                }
            }
        }

        return "target/server.jar";
    }

    @Nullable
    private static String pickBestJarInDir(@Nullable File targetDir, @NotNull File projectDir) {
        if (targetDir == null || !targetDir.isDirectory()) return null;
        File[] jars = targetDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".jar")
                    && !lower.endsWith("-sources.jar")
                    && !lower.endsWith("-javadoc.jar")
                    && !lower.endsWith(".jar.original")
                    && !lower.startsWith("original-");
        });
        if (jars == null || jars.length == 0) return null;

        File best = null;
        for (File f : jars) {
            String lower = f.getName().toLowerCase();
            if (lower.equals("server.jar")) {
                best = f;
                break;
            }
            if (lower.contains("server") || lower.contains("app") || lower.contains("boot")) {
                best = f;
            }
        }
        if (best == null) {
            best = Arrays.stream(jars).max(Comparator.comparingLong(File::lastModified)).orElse(jars[0]);
        }

        try {
            return projectDir.toPath().relativize(best.toPath()).toString().replace('\\', '/');
        } catch (Exception e) {
            return best.getAbsolutePath().replace('\\', '/');
        }
    }

    @NotNull
    public static String findN2oConfigPath(@NotNull File projectDir) {
        // 1. Direct standard location: <projectDir>/src/main/resources/META-INF/conf
        File defaultConf = new File(projectDir, "src/main/resources/META-INF/conf");
        if (defaultConf.isDirectory()) {
            return defaultConf.getAbsolutePath();
        }

        // 2. Search recursively in project for any directory named 'conf' whose parent is 'META-INF'
        File found = searchForMetaInfConf(projectDir, 0);
        if (found != null && found.isDirectory()) {
            return found.getAbsolutePath();
        }

        // 3. Search for any src/main/resources in modules
        File foundResources = searchForResourcesDir(projectDir, 0);
        if (foundResources != null && foundResources.isDirectory()) {
            return new File(foundResources, "META-INF/conf").getAbsolutePath();
        }

        return defaultConf.getAbsolutePath();
    }

    @Nullable
    private static File searchForMetaInfConf(@NotNull File dir, int depth) {
        if (depth > 6) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File f : files) {
            if (f.isDirectory() && !isIgnoredDir(f.getName())) {
                if (f.getName().equals("conf")) {
                    File parent = f.getParentFile();
                    if (parent != null && parent.getName().equals("META-INF")) {
                        return f;
                    }
                }
                File sub = searchForMetaInfConf(f, depth + 1);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    @Nullable
    private static File searchForResourcesDir(@NotNull File dir, int depth) {
        if (depth > 5) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File f : files) {
            if (f.isDirectory() && !isIgnoredDir(f.getName())) {
                if (f.getName().equals("resources")) {
                    File mainDir = f.getParentFile();
                    if (mainDir != null && mainDir.getName().equals("main")) {
                        return f;
                    }
                }
                File sub = searchForResourcesDir(f, depth + 1);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    private static boolean isIgnoredDir(@NotNull String name) {
        return name.startsWith(".") || "target".equalsIgnoreCase(name) || "build".equalsIgnoreCase(name)
                || "node_modules".equalsIgnoreCase(name) || "out".equalsIgnoreCase(name) || "dist".equalsIgnoreCase(name);
    }
}
