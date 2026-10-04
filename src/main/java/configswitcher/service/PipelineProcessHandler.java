package configswitcher.service;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import configswitcher.util.ConfigSwitcherLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Executes a multi-stage command pipeline (Pre-Run Build -> Application Run)
 * internally as native OS processes, streaming stdout/stderr unified into a single ProcessHandler.
 * Suitable for attaching to TerminalExecutionConsole or ConsoleView.
 */
public class PipelineProcessHandler extends ProcessHandler {
    private final Project project;
    private final GeneralCommandLine preRunCmd;
    private final GeneralCommandLine appCmd;
    private final Runnable onPreRunSuccess;
    private final Runnable onPreRunFailure;
    private final Runnable onAppTerminated;
    private final Consumer<Integer> onAppTerminatedWithExitCode;
    private final Consumer<Process> onAppProcessStarted;
    private final Consumer<String> rawOutputConsumer;
    private final configswitcher.util.TerminalOutputFilter outputFilter;

    private volatile OSProcessHandler currentSubHandler = null;
    private final AtomicBoolean terminating = new AtomicBoolean(false);

    public PipelineProcessHandler(
            @Nullable GeneralCommandLine preRunCmd,
            @NotNull GeneralCommandLine appCmd,
            @Nullable Runnable onPreRunSuccess,
            @Nullable Runnable onPreRunFailure,
            @Nullable Runnable onAppTerminated
    ) {
        this(null, preRunCmd, appCmd, onPreRunSuccess, onPreRunFailure, onAppTerminated, null, null, null, null);
    }

    public PipelineProcessHandler(
            @Nullable GeneralCommandLine preRunCmd,
            @NotNull GeneralCommandLine appCmd,
            @Nullable Runnable onPreRunSuccess,
            @Nullable Runnable onPreRunFailure,
            @Nullable Runnable onAppTerminated,
            @Nullable Consumer<Process> onAppProcessStarted
    ) {
        this(null, preRunCmd, appCmd, onPreRunSuccess, onPreRunFailure, onAppTerminated, null, onAppProcessStarted, null, null);
    }

    public PipelineProcessHandler(
            @Nullable GeneralCommandLine preRunCmd,
            @NotNull GeneralCommandLine appCmd,
            @Nullable Runnable onPreRunSuccess,
            @Nullable Runnable onPreRunFailure,
            @Nullable Runnable onAppTerminated,
            @Nullable Consumer<Process> onAppProcessStarted,
            @Nullable Consumer<String> rawOutputConsumer,
            @Nullable configswitcher.util.TerminalOutputFilter outputFilter
    ) {
        this(null, preRunCmd, appCmd, onPreRunSuccess, onPreRunFailure, onAppTerminated, null, onAppProcessStarted, rawOutputConsumer, outputFilter);
    }

    public PipelineProcessHandler(
            @Nullable GeneralCommandLine preRunCmd,
            @NotNull GeneralCommandLine appCmd,
            @Nullable Runnable onPreRunSuccess,
            @Nullable Runnable onPreRunFailure,
            @Nullable Runnable onAppTerminated,
            @Nullable Consumer<Integer> onAppTerminatedWithExitCode,
            @Nullable Consumer<Process> onAppProcessStarted,
            @Nullable Consumer<String> rawOutputConsumer,
            @Nullable configswitcher.util.TerminalOutputFilter outputFilter
    ) {
        this(null, preRunCmd, appCmd, onPreRunSuccess, onPreRunFailure, onAppTerminated, onAppTerminatedWithExitCode, onAppProcessStarted, rawOutputConsumer, outputFilter);
    }

    public PipelineProcessHandler(
            @Nullable Project project,
            @Nullable GeneralCommandLine preRunCmd,
            @NotNull GeneralCommandLine appCmd,
            @Nullable Runnable onPreRunSuccess,
            @Nullable Runnable onPreRunFailure,
            @Nullable Runnable onAppTerminated,
            @Nullable Consumer<Integer> onAppTerminatedWithExitCode,
            @Nullable Consumer<Process> onAppProcessStarted,
            @Nullable Consumer<String> rawOutputConsumer,
            @Nullable configswitcher.util.TerminalOutputFilter outputFilter
    ) {
        this.project = project;
        this.preRunCmd = preRunCmd;
        this.appCmd = appCmd;
        this.onPreRunSuccess = onPreRunSuccess;
        this.onPreRunFailure = onPreRunFailure;
        this.onAppTerminated = onAppTerminated;
        this.onAppTerminatedWithExitCode = onAppTerminatedWithExitCode;
        this.onAppProcessStarted = onAppProcessStarted;
        this.rawOutputConsumer = rawOutputConsumer;
        this.outputFilter = outputFilter;
    }

    @Override
    public void startNotify() {
        super.startNotify();
        if (preRunCmd != null) {
            runPreRunStage();
        } else {
            runAppStage();
        }
    }

    private void runPreRunStage() {
        notifyTextAvailable("======================================================================\n", ProcessOutputTypes.SYSTEM);
        notifyTextAvailable("[ConfigSwitcher] Running Pre-Run Build: " + preRunCmd.getCommandLineString() + "\n", ProcessOutputTypes.SYSTEM);
        if (preRunCmd.getWorkDirectory() != null) {
            notifyTextAvailable("Working Directory: " + preRunCmd.getWorkDirectory().getAbsolutePath() + "\n", ProcessOutputTypes.SYSTEM);
        }
        notifyTextAvailable("======================================================================\n\n", ProcessOutputTypes.SYSTEM);

        try {
            OSProcessHandler handler = new OSProcessHandler(preRunCmd);
            currentSubHandler = handler;
            Process preProc = handler.getProcess();
            if (preProc != null) {
                WindowsJobObjectManager.assignProcess(preProc);
            }
            handler.addProcessListener(new ProcessAdapter() {
                @Override
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    if (rawOutputConsumer != null) {
                        try {
                            rawOutputConsumer.accept(event.getText());
                        } catch (Throwable ignored) {}
                    }
                    if (outputFilter == null || outputType == ProcessOutputTypes.SYSTEM) {
                        notifyTextAvailable(event.getText(), outputType);
                    } else {
                        outputFilter.processChunk(event.getText(), outputType, line -> notifyTextAvailable(line, outputType));
                    }
                }

                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    if (outputFilter != null) {
                        outputFilter.flushRemaining(line -> notifyTextAvailable(line, ProcessOutputTypes.STDOUT));
                    }
                    int exitCode = event.getExitCode();
                    if (exitCode == 0) {
                        notifyTextAvailable("\n======================================================================\n", ProcessOutputTypes.SYSTEM);
                        notifyTextAvailable("[ConfigSwitcher] Pre-Run Build completed successfully (exit code 0).\n", ProcessOutputTypes.SYSTEM);
                        notifyTextAvailable("======================================================================\n\n", ProcessOutputTypes.SYSTEM);
                        if (onPreRunSuccess != null) {
                            try {
                                onPreRunSuccess.run();
                            } catch (Throwable t) {
                                ConfigSwitcherLog.warn(project, "Pre-run success callback failed: " + t.getMessage());
                            }
                        }
                        if (!terminating.get()) {
                            runAppStage();
                        }
                    } else {
                        boolean colorize = outputFilter != null && outputFilter.isColorizeErrorsEnabled();
                        String b1 = "\n======================================================================\n";
                        String b2 = "[ConfigSwitcher] Pre-Run Build failed with exit code " + exitCode + ". Aborting application launch.\n";
                        String b3 = "======================================================================\n";
                        notifyTextAvailable(colorize ? configswitcher.util.TerminalOutputFilter.paintRed(b1) : b1, ProcessOutputTypes.STDERR);
                        notifyTextAvailable(colorize ? configswitcher.util.TerminalOutputFilter.paintRed(b2) : b2, ProcessOutputTypes.STDERR);
                        notifyTextAvailable(colorize ? configswitcher.util.TerminalOutputFilter.paintRed(b3) : b3, ProcessOutputTypes.STDERR);
                        if (onPreRunFailure != null) {
                            try {
                                onPreRunFailure.run();
                            } catch (Throwable t) {
                                ConfigSwitcherLog.warn(project, "Pre-run failure callback failed: " + t.getMessage());
                            }
                        }
                        notifyProcessTerminated(exitCode);
                    }
                }
            });
            handler.startNotify();
        } catch (ExecutionException e) {
            notifyTextAvailable("[ConfigSwitcher] Failed to launch pre-run build: " + e.getMessage() + "\n", ProcessOutputTypes.STDERR);
            if (onPreRunFailure != null) {
                try {
                    onPreRunFailure.run();
                } catch (Throwable ignored) {}
            }
            notifyProcessTerminated(1);
        }
    }

    private void runAppStage() {
        notifyTextAvailable("======================================================================\n", ProcessOutputTypes.SYSTEM);
        notifyTextAvailable("[ConfigSwitcher] Launching Application: " + appCmd.getCommandLineString() + "\n", ProcessOutputTypes.SYSTEM);
        if (appCmd.getWorkDirectory() != null) {
            notifyTextAvailable("Working Directory: " + appCmd.getWorkDirectory().getAbsolutePath() + "\n", ProcessOutputTypes.SYSTEM);
        }
        notifyTextAvailable("======================================================================\n\n", ProcessOutputTypes.SYSTEM);

        try {
            OSProcessHandler handler = new OSProcessHandler(appCmd);
            currentSubHandler = handler;
            Process proc = handler.getProcess();
            if (proc != null) {
                WindowsJobObjectManager.assignProcess(proc);
                long pid = proc.pid();
                ConfigSwitcherLog.info(project, "Application stage started. Tracking Java process PID: " + pid);
                if (onAppProcessStarted != null) {
                    try {
                        onAppProcessStarted.accept(proc);
                    } catch (Throwable t) {
                        ConfigSwitcherLog.warn(project, "Failed to notify onAppProcessStarted: " + t.getMessage());
                    }
                }
            }
            handler.addProcessListener(new ProcessAdapter() {
                @Override
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    String text = event.getText();
                    if (rawOutputConsumer != null) {
                        try {
                            rawOutputConsumer.accept(text);
                        } catch (Throwable ignored) {}
                    }

                    if (outputFilter == null || outputType == ProcessOutputTypes.SYSTEM) {
                        notifyTextAvailable(text, outputType);
                    } else {
                        outputFilter.processChunk(text, outputType, line -> notifyTextAvailable(line, outputType));
                    }
                }

                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    if (outputFilter != null) {
                        outputFilter.flushRemaining(line -> notifyTextAvailable(line, ProcessOutputTypes.STDOUT));
                    }
                    int exitCode = event.getExitCode();
                    boolean isErr = exitCode != 0;
                    boolean colorize = isErr && outputFilter != null && outputFilter.isColorizeErrorsEnabled();
                    String msg = "[ConfigSwitcher] Application terminated with exit code " + exitCode + "\n";
                    notifyTextAvailable("\n======================================================================\n", ProcessOutputTypes.SYSTEM);
                    notifyTextAvailable(colorize ? configswitcher.util.TerminalOutputFilter.paintRed(msg) : msg, isErr ? ProcessOutputTypes.STDERR : ProcessOutputTypes.SYSTEM);
                    notifyTextAvailable("======================================================================\n", ProcessOutputTypes.SYSTEM);
                    if (onAppTerminatedWithExitCode != null) {
                        try {
                            onAppTerminatedWithExitCode.accept(exitCode);
                        } catch (Throwable t) {
                            ConfigSwitcherLog.warn(project, "App terminated exit code callback failed: " + t.getMessage());
                        }
                    }
                    if (onAppTerminated != null) {
                        try {
                            onAppTerminated.run();
                        } catch (Throwable t) {
                            ConfigSwitcherLog.warn(project, "App terminated callback failed: " + t.getMessage());
                        }
                    }
                    notifyProcessTerminated(exitCode);
                }
            });
            handler.startNotify();
        } catch (ExecutionException e) {
            String err = "[ConfigSwitcher] Failed to launch application: " + e.getMessage() + "\n";
            boolean colorize = outputFilter != null && outputFilter.isColorizeErrorsEnabled();
            notifyTextAvailable(colorize ? configswitcher.util.TerminalOutputFilter.paintRed(err) : err, ProcessOutputTypes.STDERR);
            if (onAppTerminatedWithExitCode != null) {
                try {
                    onAppTerminatedWithExitCode.accept(1);
                } catch (Throwable ignored) {}
            }
            if (onAppTerminated != null) {
                try {
                    onAppTerminated.run();
                } catch (Throwable ignored) {}
            }
            notifyProcessTerminated(1);
        }
    }

    @Override
    protected void destroyProcessImpl() {
        terminating.set(true);
        if (currentSubHandler != null && !currentSubHandler.isProcessTerminated()) {
            try {
                Process proc = currentSubHandler.getProcess();
                if (proc != null && proc.isAlive()) {
                    long pid = proc.pid();
                    ConfigSwitcherLog.info(project, "Terminating process tree in PipelineProcessHandler: PID " + pid);
                    killProcessTreeByPid(project, pid);
                    proc.toHandle().descendants().filter(ProcessHandle::isAlive).forEach(ph -> {
                        try {
                            ph.destroyForcibly();
                        } catch (Throwable ignored) {}
                    });
                    proc.destroyForcibly();
                    try {
                        proc.waitFor(2000, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Throwable ignored) {}
            currentSubHandler.destroyProcess();
        }
        notifyProcessTerminated(130);
    }

    public static void killProcessTreeByPid(long pid) {
        killProcessTreeByPid(null, pid);
    }

    public static void killProcessTreeByPid(@Nullable Project project, long pid) {
        if (pid <= 0) return;
        ConfigSwitcherLog.info(project, "Killing process tree for PID: " + pid);
        if (SystemInfo.isWindows) {
            try {
                Process p = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid)).start();
                p.waitFor(3, TimeUnit.SECONDS);
            } catch (Throwable t) {
                ConfigSwitcherLog.warn(project, "taskkill failed for PID " + pid + ": " + t.getMessage());
            }
        }
        try {
            ProcessHandle.of(pid).ifPresent(ph -> {
                ph.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                ph.destroyForcibly();
            });
        } catch (Throwable ignored) {}
    }

    @Override
    protected void detachProcessImpl() {
        destroyProcessImpl();
    }

    @Override
    public boolean detachIsDefault() {
        return false;
    }

    @Override
    public @Nullable OutputStream getProcessInput() {
        return currentSubHandler != null ? currentSubHandler.getProcessInput() : null;
    }

    @Nullable
    public OSProcessHandler getCurrentSubHandler() {
        return currentSubHandler;
    }
}
