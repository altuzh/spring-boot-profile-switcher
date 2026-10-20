package configswitcher.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import configswitcher.ui.ColorDotIcon;
import configswitcher.util.ConfigSwitcherLog;
import configswitcher.util.TerminalOutputFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service that tracks application startup stages and health from stdout/stderr logs.
 * Stages detected:
 * 0. Building... (Blue)
 * 1. Tomcat started on port(s): <port> (Grey)
 * 2. WatchDir is started (Yellow)
 * 3. Application started (Green)
 * 4. ERROR (Red) - displayed only if app was stopped
 */
@Service(Service.Level.PROJECT)
public final class AppStatusService implements Disposable {

    public enum AppStartupStage {
        IDLE,
        BUILDING,         // 0 - blue
        STARTING,
        TOMCAT_STARTED,   // 1 - grey
        WATCHDIR_STARTED, // 2 - yellow
        STARTED,          // 3 - green
        ERROR             // 4 - red
    }

    public static final Color COLOR_GREY = new JBColor(new Color(0x757575), new Color(0x9E9E9E));
    public static final Color COLOR_YELLOW = new JBColor(new Color(0xC68A00), new Color(0xE5C07B));
    public static final Color COLOR_GREEN = new JBColor(new Color(0x2E7D32), new Color(0x629755));
    public static final Color COLOR_RED = new JBColor(new Color(0xC62828), new Color(0xE06C75));
    public static final Color COLOR_BLUE = new JBColor(new Color(0x1976D2), new Color(0x42A5F5));

    public record StatusInfo(
            @NotNull AppStartupStage stage,
            @NotNull String displayText,
            @NotNull String tooltipText,
            @NotNull Color color,
            @Nullable String port,
            @Nullable String detail
    ) {
        public Icon createIcon(int size) {
            return new ColorDotIcon(size, color);
        }
    }

    // Pattern 1: Tomcat started on port(s): 8080 or port 8080
    private static final Pattern TOMCAT_PORT_PATTERN = Pattern.compile(
            "Tomcat started on port(?:\\(s\\))?:?\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOMCAT_FALLBACK_PATTERN = Pattern.compile(
            "\\bTomcat (?:is )?started\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern 2: WatchDir is started
    private static final Pattern WATCHDIR_PATTERN = Pattern.compile(
            "\\bWatchDir (?:is )?started\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern 3: Spring Boot / Application started
    private static final Pattern SPRING_STARTED_PATTERN = Pattern.compile(
            "\\bStarted\\s+\\w+\\s+in\\s+[\\d.]+\\s+seconds\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern APP_STARTED_FALLBACK_PATTERN = Pattern.compile(
            "\\b(?:Spring Boot|Application|Service)\\s+(?:was|is)?\\s*started\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern APP_PORT_PATTERN = Pattern.compile(
            "(?:port(?:\\(s\\))?:?\\s*|localhost:)(\\d{2,5})\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final Project project;
    private final StringBuilder chunkBuffer = new StringBuilder();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private volatile AppStartupStage currentStage = AppStartupStage.IDLE;
    private volatile String currentPort = null;
    private volatile String currentDetail = null;
    private volatile boolean internalIsRunning = false;
    private volatile boolean hasError = false;
    private volatile String lastErrorDetail = null;

    public AppStatusService() {
        this(null);
    }

    public AppStatusService(@Nullable Project project) {
        this.project = project;
    }

    public static AppStatusService getInstance(@NotNull Project project) {
        return project.getService(AppStatusService.class);
    }

    public boolean isRunning() {
        if (!internalIsRunning) {
            return false;
        }
        if (project != null && !project.isDisposed()) {
            try {
                return configswitcher.service.AppRunManager.getInstance(project).isAppRunning();
            } catch (Throwable ignored) {}
        }
        return internalIsRunning;
    }

    public synchronized void setRunning(boolean running) {
        this.internalIsRunning = running;
    }

    public void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    @NotNull
    public StatusInfo getCurrentStatus() {
        AppStartupStage stage = this.currentStage;
        String port = this.currentPort;
        String detail = this.currentDetail;

        // Only show ERROR in info if the app is stopped
        if (isRunning() && stage == AppStartupStage.ERROR) {
            if (currentPort != null) {
                stage = AppStartupStage.TOMCAT_STARTED;
            } else {
                stage = AppStartupStage.STARTING;
            }
        }

        return switch (stage) {
            case IDLE -> new StatusInfo(
                    AppStartupStage.IDLE,
                    "Stopped",
                    "Application not running",
                    COLOR_GREY,
                    null,
                    null
            );
            case BUILDING -> new StatusInfo(
                    AppStartupStage.BUILDING,
                    "Building...",
                    "0. Building... (Pre-run build stage)",
                    COLOR_BLUE,
                    null,
                    detail
            );
            case STARTING -> new StatusInfo(
                    AppStartupStage.STARTING,
                    "Starting...",
                    "Application is starting up",
                    COLOR_GREY,
                    null,
                    detail
            );
            case TOMCAT_STARTED -> new StatusInfo(
                    AppStartupStage.TOMCAT_STARTED,
                    port != null ? "Tomcat: " + port : "Tomcat started",
                    port != null ? "1. Tomcat started on port(s): " + port : "1. Tomcat started",
                    COLOR_GREY,
                    port,
                    detail
            );
            case WATCHDIR_STARTED -> new StatusInfo(
                    AppStartupStage.WATCHDIR_STARTED,
                    "WatchDir started",
                    "2. WatchDir is started" + (port != null ? " (Tomcat: " + port + ")" : ""),
                    COLOR_YELLOW,
                    port,
                    detail
            );
            case STARTED -> new StatusInfo(
                    AppStartupStage.STARTED,
                    port != null ? "Started: " + port : "Started",
                    "3. Application started" + (port != null ? " (port " + port + ")" : ""),
                    COLOR_GREEN,
                    port,
                    detail
            );
            case ERROR -> new StatusInfo(
                    AppStartupStage.ERROR,
                    "ERROR",
                    "4. ERROR" + (detail != null && !detail.isBlank() ? ": " + detail : ""),
                    COLOR_RED,
                    port,
                    detail
            );
        };
    }

    public synchronized void setStage(@NotNull AppStartupStage stage, @Nullable String detail) {
        if (stage == AppStartupStage.BUILDING || stage == AppStartupStage.STARTING ||
                stage == AppStartupStage.TOMCAT_STARTED || stage == AppStartupStage.WATCHDIR_STARTED ||
                stage == AppStartupStage.STARTED) {
            this.internalIsRunning = true;
        } else if (stage == AppStartupStage.ERROR || stage == AppStartupStage.IDLE) {
            this.internalIsRunning = false;
        }
        if (stage == AppStartupStage.ERROR) {
            this.hasError = true;
            this.lastErrorDetail = detail;
        }
        if (this.currentStage == stage && java.util.Objects.equals(this.currentDetail, detail)) {
            return;
        }
        this.currentStage = stage;
        this.currentDetail = detail;
        ConfigSwitcherLog.info(project, "AppStatusService: Transitioned to stage " + stage + (detail != null ? " (" + detail + ")" : ""));
        notifyChanged();
    }

    public synchronized void setStage(@NotNull AppStartupStage stage) {
        setStage(stage, null);
    }

    public synchronized void reset() {
        this.currentStage = AppStartupStage.IDLE;
        this.currentPort = null;
        this.currentDetail = null;
        this.internalIsRunning = true;
        this.hasError = false;
        this.lastErrorDetail = null;
        this.chunkBuffer.setLength(0);
        notifyChanged();
    }

    public synchronized void onAppStopped() {
        this.internalIsRunning = false;
        this.hasError = false;
        this.lastErrorDetail = null;
        this.currentStage = AppStartupStage.IDLE;
        this.currentPort = null;
        this.currentDetail = null;
        this.chunkBuffer.setLength(0);
        notifyChanged();
    }

    public synchronized void onAppTerminated(int exitCode) {
        this.internalIsRunning = false;
        if (exitCode != 0 || this.hasError) {
            this.currentStage = AppStartupStage.ERROR;
            if (this.lastErrorDetail != null && !this.lastErrorDetail.isBlank()) {
                this.currentDetail = this.lastErrorDetail;
            } else {
                this.currentDetail = "Process terminated with exit code " + exitCode;
            }
        } else {
            this.currentStage = AppStartupStage.IDLE;
            this.currentPort = null;
            this.currentDetail = null;
        }
        this.chunkBuffer.setLength(0);
        notifyChanged();
    }

    public synchronized void onPreRunFailed(@Nullable String reason) {
        this.internalIsRunning = false;
        this.hasError = true;
        this.lastErrorDetail = reason;
        this.currentStage = AppStartupStage.ERROR;
        this.currentDetail = reason != null ? reason : "Pre-run build failed";
        this.chunkBuffer.setLength(0);
        notifyChanged();
    }

    public synchronized void processChunk(@Nullable String chunk) {
        if (chunk == null || chunk.isEmpty()) return;

        chunkBuffer.append(chunk);

        int newlineIndex;
        while ((newlineIndex = chunkBuffer.indexOf("\n")) != -1) {
            String line = chunkBuffer.substring(0, newlineIndex);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            chunkBuffer.delete(0, newlineIndex + 1);

            processLine(line);
        }
    }

    private void processLine(@NotNull String line) {
        if (line.isBlank()) return;

        // 1. Check for ERROR
        if (isError(line)) {
            String shortError = extractShortError(line);
            this.hasError = true;
            this.lastErrorDetail = shortError;
            ConfigSwitcherLog.warn(project, "AppStatusService: Error detected in log: " + shortError);
            if (!isRunning()) {
                this.currentStage = AppStartupStage.ERROR;
                this.currentDetail = shortError;
                notifyChanged();
            }
            return;
        }

        // 2. Check for Spring Boot / Application started (Stage 3 - Green)
        if (SPRING_STARTED_PATTERN.matcher(line).find() || APP_STARTED_FALLBACK_PATTERN.matcher(line).find()) {
            this.currentStage = AppStartupStage.STARTED;
            Matcher pm = APP_PORT_PATTERN.matcher(line);
            if (pm.find()) {
                String extracted = pm.group(1);
                if (extracted != null && !extracted.isBlank()) {
                    this.currentPort = extracted;
                }
            }
            ConfigSwitcherLog.info(project, "AppStatusService: Application started detected" + (currentPort != null ? " (port " + currentPort + ")" : ""));
            notifyChanged();
            return;
        }

        // 3. Check for WatchDir is started (Stage 2 - Yellow)
        if (currentStage != AppStartupStage.STARTED && WATCHDIR_PATTERN.matcher(line).find()) {
            this.currentStage = AppStartupStage.WATCHDIR_STARTED;
            ConfigSwitcherLog.info(project, "AppStatusService: WatchDir started detected");
            notifyChanged();
            return;
        }

        // 4. Check for Tomcat started on port(s): <port> (Stage 1 - Grey)
        if (currentStage != AppStartupStage.STARTED && currentStage != AppStartupStage.WATCHDIR_STARTED) {
            Matcher m = TOMCAT_PORT_PATTERN.matcher(line);
            if (m.find()) {
                this.currentPort = m.group(1);
                this.currentStage = AppStartupStage.TOMCAT_STARTED;
                ConfigSwitcherLog.info(project, "AppStatusService: Tomcat port " + currentPort + " detected");
                notifyChanged();
            } else if (TOMCAT_FALLBACK_PATTERN.matcher(line).find()) {
                this.currentStage = AppStartupStage.TOMCAT_STARTED;
                ConfigSwitcherLog.info(project, "AppStatusService: Tomcat started detected (no port)");
                notifyChanged();
            }
        }
    }

    public static boolean isError(@NotNull String line) {
        return TerminalOutputFilter.isErrorLine(line);
    }

    public static @NotNull String extractShortError(@NotNull String line) {
        String trimmed = line.trim();
        int errIdx = trimmed.indexOf("ERROR");
        if (errIdx != -1) {
            int colonIdx = trimmed.indexOf(" : ", errIdx);
            if (colonIdx != -1 && colonIdx + 3 < trimmed.length()) {
                String msg = trimmed.substring(colonIdx + 3).trim();
                if (!msg.isEmpty()) {
                    return msg.length() > 60 ? msg.substring(0, 57) + "..." : msg;
                }
            }
        }
        return trimmed.length() > 60 ? trimmed.substring(0, 57) + "..." : trimmed;
    }

    private void notifyChanged() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable ignored) {}
        }
        try {
            com.intellij.ide.ActivityTracker.getInstance().inc();
        } catch (Throwable ignored) {}
    }

    @Override
    public void dispose() {
        listeners.clear();
        chunkBuffer.setLength(0);
    }
}
