package configswitcher.service;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.JBTerminalWidget;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import configswitcher.model.TerminalShellType;
import configswitcher.state.PluginSettingsState;
import configswitcher.util.ConfigNotifier;
import configswitcher.util.ConfigSwitcherLog;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalOptionsProvider;
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;
import org.jetbrains.plugins.terminal.TerminalView;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service to execute commands in IntelliJ's Terminal Tool Window,
 * reusing the active/current terminal tab if available, or creating a new tab.
 * Accurately detects and supports PowerShell, CMD, and Bash/POSIX shells.
 */
@SuppressWarnings({"deprecation", "removal"})
public final class TerminalRunnerService {

    public static final Key<Boolean> IS_PIPELINE_TAB = Key.create("ConfigSwitcher.IsPipelineTab");

    public enum ShellType {
        POWERSHELL,
        CMD,
        BASH
    }

    public static void runPipelineInTerminalConsole(
            @NotNull Project project,
            @Nullable GeneralCommandLine preRunCmd,
            @NotNull GeneralCommandLine appCmd,
            @NotNull String tabTitle,
            @Nullable Runnable onPreRunSuccess,
            @Nullable Runnable onPreRunFailure,
            @Nullable Runnable onAppTerminated,
            @NotNull Consumer<ProcessHandler> onHandlerStarted
    ) {
        runPipelineInTerminalConsole(
                project, preRunCmd, appCmd, tabTitle,
                onPreRunSuccess, onPreRunFailure, onAppTerminated,
                null, onHandlerStarted
        );
    }

    public static void runPipelineInTerminalConsole(
            @NotNull Project project,
            @Nullable GeneralCommandLine preRunCmd,
            @NotNull GeneralCommandLine appCmd,
            @NotNull String tabTitle,
            @Nullable Runnable onPreRunSuccess,
            @Nullable Runnable onPreRunFailure,
            @Nullable Runnable onAppTerminated,
            @Nullable Consumer<Process> onAppProcessStarted,
            @NotNull Consumer<ProcessHandler> onHandlerStarted
    ) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ToolWindow terminalWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
                if (terminalWindow == null) {
                    try {
                        terminalWindow = TerminalToolWindowManager.getInstance(project).getToolWindow();
                    } catch (Throwable ignored) {}
                }

                if (terminalWindow == null) {
                    ConfigNotifier.notifyError(project, "Terminal tool window is not available.");
                    return;
                }

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
                onHandlerStarted.accept(pipelineHandler);

                if (!terminalWindow.isVisible()) {
                    terminalWindow.activate(null);
                }

                ContentManager cm = terminalWindow.getContentManager();

                // Find existing pipeline tab to reuse
                Content targetContent = null;
                for (Content c : cm.getContents()) {
                    if (Boolean.TRUE.equals(c.getUserData(IS_PIPELINE_TAB)) || tabTitle.equals(c.getDisplayName())) {
                        targetContent = c;
                        break;
                    }
                }

                TerminalExecutionConsole console = new TerminalExecutionConsole(project, pipelineHandler);
                console.withConvertLfToCrlfForNonPtyProcess(true);

                Content newContent = cm.getFactory().createContent(console.getComponent(), tabTitle, true);
                newContent.putUserData(IS_PIPELINE_TAB, Boolean.TRUE);
                newContent.setDisposer(console);
                newContent.setPreferredFocusableComponent(console.getPreferredFocusableComponent());

                if (targetContent != null) {
                    cm.removeContent(targetContent, true);
                }

                cm.addContent(newContent);
                cm.setSelectedContent(newContent);
                terminalWindow.activate(null);

                pipelineHandler.startNotify();
                ConfigNotifier.notifyInfo(project, "Pipeline running in Terminal: " + tabTitle);
            } catch (Throwable t) {
                ConfigSwitcherLog.error(project, "Failed to run pipeline in terminal console: " + t.getMessage(), t);
                ConfigNotifier.notifyError(project, "Failed to run in terminal: " + t.getMessage());
            }
        });
    }

    public static void runPipelineInTerminal(
            @NotNull Project project,
            @Nullable String preRunCommand,
            @NotNull String runCommand,
            @Nullable File workDir,
            @NotNull String tabTitle,
            @NotNull TerminalShellType preferredShellType
    ) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ShellTerminalWidget widget = getOrCreateTerminalWidget(project, workDir, tabTitle);
                if (widget != null) {
                    boolean hadRunning = hasRunningTask(widget);
                    if (hadRunning) {
                        ConfigSwitcherLog.info(project, "Previous task is running in terminal. Stopping it before running new pipeline...");
                        stopRunningTask(project, widget);
                    }

                    Runnable runAction = () -> {
                        try {
                            clearTerminal(project, widget);

                            ShellType shellType = detectShellType(project, widget, preferredShellType);
                            String chainedCommand = buildChainedCommand(preRunCommand, runCommand, shellType);
                            String finalCommand = buildCommandWithWorkDir(project, widget, chainedCommand, workDir, shellType);

                            // Ensure clean \n line breaks for terminal emulator
                            String cmdToSend = finalCommand.replace("\r\n", "\n").replace('\r', '\n');

                            ConfigSwitcherLog.info(project, "Executing pipeline in terminal [" + shellType + "]:\n" + cmdToSend);
                            widget.executeCommand(cmdToSend);
                            ConfigNotifier.notifyInfo(project, "Command sent to Terminal (" + shellType + "): " + tabTitle);
                        } catch (Exception e) {
                            ConfigSwitcherLog.error(project, "Terminal execution failed: " + e.getMessage(), e);
                            ConfigNotifier.notifyError(project, "Terminal execution failed: " + e.getMessage());
                        }
                    };

                    if (hadRunning) {
                        AppExecutorUtil.getAppScheduledExecutorService().schedule(
                                () -> ApplicationManager.getApplication().invokeLater(runAction),
                                300,
                                TimeUnit.MILLISECONDS
                        );
                    } else {
                        runAction.run();
                    }
                } else {
                    ConfigNotifier.notifyError(project, "Could not open or locate terminal window.");
                }
            } catch (Exception e) {
                ConfigSwitcherLog.error(project, "Terminal execution failed: " + e.getMessage(), e);
                ConfigNotifier.notifyError(project, "Terminal execution failed: " + e.getMessage());
            }
        });
    }

    public static boolean hasRunningTask(@NotNull ShellTerminalWidget widget) {
        try {
            if (widget.hasRunningCommands()) {
                return true;
            }
        } catch (Throwable ignored) {}

        try {
            com.jediterm.terminal.ProcessTtyConnector pty = widget.getProcessTtyConnector();
            if (pty != null) {
                Process shellProc = pty.getProcess();
                if (shellProc != null && shellProc.isAlive()) {
                    return shellProc.toHandle().descendants().anyMatch(ProcessHandle::isAlive);
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    public static void stopRunningTask(@NotNull ShellTerminalWidget widget) {
        stopRunningTask(null, widget);
    }

    public static void stopRunningTask(@Nullable Project project, @NotNull ShellTerminalWidget widget) {
        ConfigSwitcherLog.info(project, "Stopping previous task in terminal widget...");

        // 1. Send Ctrl+C to PTY to interrupt interactive foreground task
        try {
            com.jediterm.terminal.ProcessTtyConnector pty = widget.getProcessTtyConnector();
            if (pty != null) {
                pty.write(new byte[]{3}); // Ctrl+C (ETX)
            }
        } catch (Throwable t) {
            ConfigSwitcherLog.warn(project, "Failed to write Ctrl+C to terminal PTY: " + t.getMessage());
        }

        // 2. Terminate any descendant processes spawned by the shell (e.g. java.exe, mvn)
        try {
            com.jediterm.terminal.ProcessTtyConnector pty = widget.getProcessTtyConnector();
            if (pty != null) {
                Process shellProc = pty.getProcess();
                if (shellProc != null && shellProc.isAlive()) {
                    List<ProcessHandle> descendants = shellProc.toHandle().descendants()
                            .filter(ProcessHandle::isAlive)
                            .toList();
                    for (ProcessHandle ph : descendants) {
                        try {
                            ConfigSwitcherLog.info(project, "Stopping terminal child process PID " + ph.pid());
                            ph.destroy();
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            ConfigSwitcherLog.warn(project, "Failed to terminate terminal descendant processes: " + t.getMessage());
        }

        // 3. Send another Ctrl+C to clear any lingering command line / subprompt
        try {
            com.jediterm.terminal.ProcessTtyConnector pty = widget.getProcessTtyConnector();
            if (pty != null) {
                pty.write(new byte[]{3});
            }
        } catch (Throwable ignored) {}
    }

    public static void clearTerminal(@NotNull ShellTerminalWidget widget) {
        clearTerminal(null, widget);
    }

    public static void clearTerminal(@Nullable Project project, @NotNull ShellTerminalWidget widget) {
        try {
            com.jediterm.terminal.ui.TerminalPanel panel = widget.getTerminalPanel();
            if (panel != null) {
                panel.clearBuffer();
            }
        } catch (Throwable t) {
            ConfigSwitcherLog.warn(project, "Failed to clear terminal panel buffer: " + t.getMessage());
        }

        try {
            com.jediterm.terminal.model.TerminalTextBuffer textBuffer = widget.getTerminalTextBuffer();
            if (textBuffer != null) {
                textBuffer.clearScreenAndHistoryBuffers();
            }
        } catch (Throwable t) {
            ConfigSwitcherLog.warn(project, "Failed to clear terminal text buffer: " + t.getMessage());
        }

        try {
            com.jediterm.terminal.Terminal term = widget.getTerminal();
            if (term != null) {
                term.clearScreen();
                term.cursorPosition(1, 1);
            }
        } catch (Throwable t) {
            ConfigSwitcherLog.warn(project, "Failed to clear terminal screen: " + t.getMessage());
        }

        try {
            if (widget.getTerminalPanel() != null) {
                widget.getTerminalPanel().repaint();
            }
        } catch (Throwable ignored) {}
    }

    public static void runInTerminal(
            @NotNull Project project,
            @NotNull String command,
            @Nullable File workDir,
            @NotNull String tabTitle
    ) {
        TerminalShellType pref = TerminalShellType.AUTO;
        try {
            pref = PluginSettingsState.getInstance(project).getState().terminalShellType;
        } catch (Throwable ignored) {}
        runPipelineInTerminal(project, null, command, workDir, tabTitle, pref);
    }

    @Nullable
    public static ShellTerminalWidget getOrCreateTerminalWidget(
            @NotNull Project project,
            @Nullable File workDir,
            @NotNull String tabTitle
    ) {
        ToolWindow terminalWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
        if (terminalWindow == null) {
            try {
                terminalWindow = TerminalToolWindowManager.getInstance(project).getToolWindow();
            } catch (Throwable ignored) {}
        }

        if (terminalWindow != null && !terminalWindow.isVisible()) {
            terminalWindow.activate(null);
        }

        ShellTerminalWidget currentWidget = getActiveTerminalWidget(project, terminalWindow);
        if (currentWidget != null) {
            ConfigSwitcherLog.info(project, "Found active terminal tab to reuse.");
            return currentWidget;
        }

        // No active widget, create new shell widget
        String dirPath = (workDir != null && workDir.exists()) ? workDir.getAbsolutePath() : project.getBasePath();
        ConfigSwitcherLog.info(project, "Creating new terminal tab in directory: " + dirPath);

        try {
            return TerminalToolWindowManager.getInstance(project).createLocalShellWidget(dirPath, tabTitle);
        } catch (Throwable t) {
            try {
                return TerminalView.getInstance(project).createLocalShellWidget(dirPath, tabTitle);
            } catch (Throwable t2) {
                ConfigSwitcherLog.error(project, "Failed to create local shell widget: " + t2.getMessage(), t2);
            }
        }
        return null;
    }

    @Nullable
    public static ShellTerminalWidget getActiveTerminalWidget(
            @NotNull Project project,
            @Nullable ToolWindow terminalWindow
    ) {
        if (terminalWindow == null) return null;

        ContentManager cm = terminalWindow.getContentManager();
        Content selected = cm.getSelectedContent();
        if (selected == null) return null;

        // 1. Try TerminalToolWindowManager.getWidgetByContent
        try {
            JBTerminalWidget jb = TerminalToolWindowManager.getWidgetByContent(selected);
            if (jb instanceof ShellTerminalWidget) {
                return (ShellTerminalWidget) jb;
            }
        } catch (Throwable ignored) {}

        // 2. Try TerminalView.getWidgetByContent
        try {
            JBTerminalWidget jb = TerminalView.getWidgetByContent(selected);
            if (jb instanceof ShellTerminalWidget) {
                return (ShellTerminalWidget) jb;
            }
        } catch (Throwable ignored) {}

        // 3. Try TerminalToolWindowManager.findWidgetByContent
        try {
            Object tw = TerminalToolWindowManager.findWidgetByContent(selected);
            if (tw != null) {
                return ShellTerminalWidget.asShellJediTermWidget((com.intellij.terminal.ui.TerminalWidget) tw);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    @NotNull
    public static ShellType detectShellType(
            @NotNull Project project,
            @Nullable ShellTerminalWidget widget,
            @NotNull TerminalShellType preference
    ) {
        if (preference == TerminalShellType.POWERSHELL) return ShellType.POWERSHELL;
        if (preference == TerminalShellType.CMD) return ShellType.CMD;
        if (preference == TerminalShellType.BASH) return ShellType.BASH;

        // 1. Inspect widget process / TTY connector
        if (widget != null) {
            try {
                com.jediterm.terminal.ProcessTtyConnector pty = widget.getProcessTtyConnector();
                if (pty != null) {
                    List<String> cmdLine = pty.getCommandLine();
                    if (cmdLine != null && !cmdLine.isEmpty()) {
                        ShellType st = matchShellType(cmdLine.get(0));
                        if (st != null) return st;
                    }
                    String name = pty.getName();
                    if (name != null) {
                        ShellType st = matchShellType(name);
                        if (st != null) return st;
                    }
                    Process proc = pty.getProcess();
                    if (proc != null) {
                        try {
                            String cmd = proc.info().command().orElse(null);
                            if (cmd != null) {
                                ShellType st = matchShellType(cmd);
                                if (st != null) return st;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            try {
                List<String> shellCmd = widget.getShellCommand();
                if (shellCmd != null && !shellCmd.isEmpty()) {
                    ShellType st = matchShellType(shellCmd.get(0));
                    if (st != null) return st;
                }
            } catch (Throwable ignored) {}

            try {
                if (widget.getTerminalTitle() != null) {
                    String title = widget.getTerminalTitle().getDefaultTitle();
                    ShellType st = matchShellType(title);
                    if (st != null) return st;
                }
            } catch (Throwable ignored) {}
        }

        // 2. Check Terminal Project / Application Options Provider
        try {
            TerminalProjectOptionsProvider projectOpts = TerminalProjectOptionsProvider.getInstance(project);
            if (projectOpts != null && projectOpts.getShellPath() != null) {
                ShellType st = matchShellType(projectOpts.getShellPath());
                if (st != null) return st;
            }
        } catch (Throwable ignored) {}

        try {
            TerminalOptionsProvider appOpts = TerminalOptionsProvider.getInstance();
            if (appOpts != null && appOpts.getShellPath() != null) {
                ShellType st = matchShellType(appOpts.getShellPath());
                if (st != null) return st;
            }
        } catch (Throwable ignored) {}

        // 3. Fallback based on OS
        if (SystemInfo.isWindows) {
            return ShellType.POWERSHELL;
        } else {
            return ShellType.BASH;
        }
    }

    @Nullable
    public static ShellType matchShellType(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        String s = text.toLowerCase().trim();
        if (s.contains("powershell") || s.contains("pwsh")) {
            return ShellType.POWERSHELL;
        }
        if (s.contains("cmd.exe") || s.equals("cmd") || s.endsWith("\\cmd.exe") || s.endsWith("/cmd.exe") || s.contains("command prompt")) {
            return ShellType.CMD;
        }
        if (s.contains("bash") || s.contains("zsh") || s.contains("git-bash") || s.contains("wsl.exe") || s.endsWith("\\sh.exe") || s.endsWith("/sh.exe")) {
            return ShellType.BASH;
        }
        return null;
    }

    @NotNull
    public static String buildChainedCommand(
            @Nullable String preRunCommand,
            @NotNull String runCommand,
            @NotNull ShellType shellType
    ) {
        if (preRunCommand == null || preRunCommand.isBlank()) {
            return runCommand.trim();
        }
        String pre = preRunCommand.trim();
        String run = runCommand.trim();

        if (shellType == ShellType.POWERSHELL) {
            return pre + "; if ($?) { " + run + " }";
        } else {
            return pre + " && " + run;
        }
    }

    @NotNull
    public static String buildChainedCommand(
            @Nullable String preRunCommand,
            @NotNull String runCommand,
            boolean isPowerShell
    ) {
        return buildChainedCommand(preRunCommand, runCommand, isPowerShell ? ShellType.POWERSHELL : ShellType.CMD);
    }

    @NotNull
    public static String buildCommandWithWorkDir(
            @Nullable Project project,
            @NotNull ShellTerminalWidget widget,
            @NotNull String command,
            @Nullable File workDir,
            @NotNull ShellType shellType
    ) {
        if (workDir == null || !workDir.exists()) {
            return command;
        }

        String targetDir = workDir.getAbsolutePath();

        if (project != null) {
            String basePath = project.getBasePath();
            if (basePath != null && normalizePath(basePath).equalsIgnoreCase(normalizePath(targetDir))) {
                return command;
            }
        }

        String currentDir = null;
        try {
            java.lang.reflect.Method m = widget.getClass().getMethod("getCurrentDirectory");
            Object res = m.invoke(widget);
            if (res instanceof String) {
                currentDir = (String) res;
            }
        } catch (Throwable ignored) {}

        if (currentDir != null && normalizePath(currentDir).equalsIgnoreCase(normalizePath(targetDir))) {
            return command;
        }

        if (shellType == ShellType.POWERSHELL) {
            return "Set-Location -LiteralPath \"" + targetDir + "\"; if ($?) { " + command + " }";
        } else if (shellType == ShellType.CMD) {
            return "cd /d \"" + targetDir + "\" && " + command;
        } else {
            return "cd \"" + targetDir + "\" && " + command;
        }
    }

    @NotNull
    public static String buildCommandWithWorkDir(
            @NotNull ShellTerminalWidget widget,
            @NotNull String command,
            @Nullable File workDir,
            @NotNull ShellType shellType
    ) {
        return buildCommandWithWorkDir(null, widget, command, workDir, shellType);
    }

    @NotNull
    public static String buildCommandWithWorkDir(
            @NotNull ShellTerminalWidget widget,
            @NotNull String command,
            @Nullable File workDir
    ) {
        ShellType shellType = isPowerShellWidget(widget) ? ShellType.POWERSHELL : (SystemInfo.isWindows ? ShellType.CMD : ShellType.BASH);
        return buildCommandWithWorkDir(null, widget, command, workDir, shellType);
    }

    public static boolean isPowerShellWidget(@NotNull Project project, @Nullable ShellTerminalWidget widget) {
        TerminalShellType pref = TerminalShellType.AUTO;
        try {
            pref = PluginSettingsState.getInstance(project).getState().terminalShellType;
        } catch (Throwable ignored) {}
        return detectShellType(project, widget, pref) == ShellType.POWERSHELL;
    }

    public static boolean isPowerShellWidget(@Nullable ShellTerminalWidget widget) {
        if (widget != null) {
            try {
                com.jediterm.terminal.ProcessTtyConnector pty = widget.getProcessTtyConnector();
                if (pty != null) {
                    if (pty.getCommandLine() != null && !pty.getCommandLine().isEmpty()) {
                        ShellType st = matchShellType(pty.getCommandLine().get(0));
                        if (st != null) return st == ShellType.POWERSHELL;
                    }
                    if (pty.getName() != null) {
                        ShellType st = matchShellType(pty.getName());
                        if (st != null) return st == ShellType.POWERSHELL;
                    }
                }
            } catch (Throwable ignored) {}
            try {
                List<String> cmd = widget.getShellCommand();
                if (cmd != null && !cmd.isEmpty()) {
                    ShellType st = matchShellType(cmd.get(0));
                    if (st != null) return st == ShellType.POWERSHELL;
                }
            } catch (Throwable ignored) {}
        }
        return SystemInfo.isWindows;
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/').replaceAll("/+$", "");
    }
}
