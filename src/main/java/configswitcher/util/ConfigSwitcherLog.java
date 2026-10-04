package configswitcher.util;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedicated logger and log manager for the Spring Boot Profile Switcher plugin.
 * Provides project-specific rotating file logging for troubleshooting, in-memory execution capture,
 * and support for opening project log directories in OS file manager.
 */
public class ConfigSwitcherLog {
    public static final String LOG_DIR_NAME = "spring-boot-profile-switcher";
    public static final String LOG_FILE_NAME = "spring-boot-profile-switcher.log";
    private static final long MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final int MAX_ROTATED_FILES = 3;
    private static final int MAX_BUFFER_CHARS = 300_000;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final StringBuilder currentExecutionBuffer = new StringBuilder();
    private static final Map<String, StringBuilder> projectExecutionBuffers = new ConcurrentHashMap<>();
    private static final Logger IDEA_LOGGER = Logger.getInstance(ConfigSwitcherLog.class);

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    public static String sanitize(@Nullable String text) {
        if (text == null) return "";
        String s = text.replaceAll("(?i)(password|secret|token)[:=]\\s*\\S+", "$1=***MASKED***");
        s = s.replaceAll("(?i)(Bearer\\s+)[a-zA-Z0-9_\\-\\.]+", "$1***MASKED***");
        return s;
    }

    private static File testLogDirectory = null;

    public static void setTestLogDirectory(@Nullable File dir) {
        testLogDirectory = dir;
    }

    @NotNull
    public static File getBaseLogDirectory() {
        if (testLogDirectory != null) {
            if (!testLogDirectory.exists()) {
                testLogDirectory.mkdirs();
            }
            return testLogDirectory;
        }

        File dir = null;
        try {
            String ideaLog = PathManager.getLogPath();
            if (ideaLog != null && !ideaLog.isBlank()) {
                File candidate = new File(ideaLog, LOG_DIR_NAME);
                if (candidate.exists() || candidate.mkdirs()) {
                    dir = candidate;
                }
            }
        } catch (Throwable ignored) {}

        if (dir == null) {
            File userHome = new File(System.getProperty("user.home"), ".spring-boot-profile-switcher" + File.separator + "logs");
            if (userHome.exists() || userHome.mkdirs()) {
                dir = userHome;
            } else {
                dir = new File(System.getProperty("java.io.tmpdir"), "spring-boot-profile-switcher-logs");
                dir.mkdirs();
            }
        }

        return dir;
    }

    @NotNull
    public static String getProjectDirectoryName(@Nullable Project project) {
        if (project == null) {
            return "default";
        }
        try {
            if (project.isDefault()) {
                return "default";
            }
        } catch (Throwable ignored) {}

        String name = null;
        try {
            name = project.getName();
        } catch (Throwable ignored) {}

        if (name == null || name.isBlank()) {
            name = "project";
        }
        String sanitized = name.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.isBlank() ? "project" : sanitized;
    }

    @NotNull
    public static File getLogDirectory(@Nullable Project project) {
        File baseDir = getBaseLogDirectory();
        if (project == null) {
            return baseDir;
        }
        try {
            if (project.isDefault()) {
                return baseDir;
            }
        } catch (Throwable ignored) {}

        File projectLogDir = new File(baseDir, getProjectDirectoryName(project));
        if (!projectLogDir.exists()) {
            projectLogDir.mkdirs();
        }
        return projectLogDir;
    }

    @NotNull
    public static File getLogDirectory() {
        return getLogDirectory(null);
    }

    @NotNull
    public static File getLogFile(@Nullable Project project) {
        return new File(getLogDirectory(project), LOG_FILE_NAME);
    }

    @NotNull
    public static File getLogFile() {
        return getLogFile(null);
    }

    public static synchronized void log(@Nullable Project project, @NotNull Level level, @NotNull String message, @Nullable Throwable throwable) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String threadName = Thread.currentThread().getName();
        String sanitizedMessage = sanitize(message);

        String logLine = String.format("[%s] [%s] [%s] %s%n",
                timestamp,
                threadName,
                level.name(),
                sanitizedMessage
        );

        String projKey = getProjectDirectoryName(project);
        StringBuilder projBuf = projectExecutionBuffers.computeIfAbsent(projKey, k -> new StringBuilder());
        if (projBuf.length() < MAX_BUFFER_CHARS) {
            projBuf.append(logLine);
            if (throwable != null) {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                projBuf.append(sw);
            }
        }

        if (currentExecutionBuffer.length() < MAX_BUFFER_CHARS) {
            currentExecutionBuffer.append(logLine);
            if (throwable != null) {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                currentExecutionBuffer.append(sw);
            }
        }

        switch (level) {
            case DEBUG -> IDEA_LOGGER.debug(sanitizedMessage);
            case INFO -> IDEA_LOGGER.info(sanitizedMessage);
            case WARN -> {
                if (throwable != null) IDEA_LOGGER.warn(sanitizedMessage, throwable);
                else IDEA_LOGGER.warn(sanitizedMessage);
            }
            case ERROR -> {
                if (throwable != null) IDEA_LOGGER.error(sanitizedMessage, throwable);
                else IDEA_LOGGER.error(sanitizedMessage);
            }
        }

        writeToFile(project, logLine, throwable);
    }

    public static void log(@NotNull Level level, @NotNull String message, @Nullable Throwable throwable) {
        log(null, level, message, throwable);
    }

    public static void debug(@NotNull String message) {
        log(null, Level.DEBUG, message, null);
    }

    public static void debug(@Nullable Project project, @NotNull String message) {
        log(project, Level.DEBUG, message, null);
    }

    public static void info(@NotNull String message) {
        log(null, Level.INFO, message, null);
    }

    public static void info(@Nullable Project project, @NotNull String message) {
        log(project, Level.INFO, message, null);
    }

    public static void warn(@NotNull String message) {
        log(null, Level.WARN, message, null);
    }

    public static void warn(@Nullable Project project, @NotNull String message) {
        log(project, Level.WARN, message, null);
    }

    public static void warn(@NotNull String message, @Nullable Throwable throwable) {
        log(null, Level.WARN, message, throwable);
    }

    public static void warn(@Nullable Project project, @NotNull String message, @Nullable Throwable throwable) {
        log(project, Level.WARN, message, throwable);
    }

    public static void error(@NotNull String message) {
        log(null, Level.ERROR, message, null);
    }

    public static void error(@Nullable Project project, @NotNull String message) {
        log(project, Level.ERROR, message, null);
    }

    public static void error(@NotNull String message, @Nullable Throwable throwable) {
        log(null, Level.ERROR, message, throwable);
    }

    public static void error(@Nullable Project project, @NotNull String message, @Nullable Throwable throwable) {
        log(project, Level.ERROR, message, throwable);
    }

    private static synchronized void writeToFile(@Nullable Project project, @NotNull String logLine, @Nullable Throwable throwable) {
        try {
            File logFile = getLogFile(project);
            rotateIfNeeded(logFile);

            try (FileOutputStream fos = new FileOutputStream(logFile, true);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                 BufferedWriter bw = new BufferedWriter(osw)) {

                bw.write(logLine);
                if (throwable != null) {
                    StringWriter sw = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(sw));
                    bw.write(sw.toString());
                }
                bw.flush();
            }
        } catch (Throwable ignored) {}
    }

    private static void rotateIfNeeded(@NotNull File logFile) {
        if (!logFile.exists() || logFile.length() < MAX_LOG_SIZE_BYTES) {
            return;
        }

        try {
            File dir = logFile.getParentFile();
            File oldest = new File(dir, LOG_FILE_NAME + "." + MAX_ROTATED_FILES);
            if (oldest.exists()) {
                oldest.delete();
            }

            for (int i = MAX_ROTATED_FILES - 1; i >= 1; i--) {
                File current = new File(dir, LOG_FILE_NAME + "." + i);
                if (current.exists()) {
                    File next = new File(dir, LOG_FILE_NAME + "." + (i + 1));
                    current.renameTo(next);
                }
            }

            File rotated1 = new File(dir, LOG_FILE_NAME + ".1");
            logFile.renameTo(rotated1);
        } catch (Throwable ignored) {}
    }

    public static synchronized String getCurrentExecutionLog(@Nullable Project project) {
        String key = getProjectDirectoryName(project);
        StringBuilder buf = projectExecutionBuffers.get(key);
        return buf != null ? buf.toString() : "";
    }

    public static synchronized String getCurrentExecutionLog() {
        return currentExecutionBuffer.toString();
    }

    public static synchronized void clearExecutionBuffer(@Nullable Project project) {
        String key = getProjectDirectoryName(project);
        StringBuilder buf = projectExecutionBuffers.get(key);
        if (buf != null) {
            buf.setLength(0);
        }
    }

    public static synchronized void clearExecutionBuffer() {
        currentExecutionBuffer.setLength(0);
        projectExecutionBuffers.clear();
    }

    public static boolean openLogDirectory() {
        return openLogDirectory(null, null);
    }

    public static boolean openLogDirectory(@Nullable File fileToSelect) {
        return openLogDirectory(null, fileToSelect);
    }

    public static boolean openLogDirectory(@Nullable Project project) {
        return openLogDirectory(project, null);
    }

    public static boolean openLogDirectory(@Nullable Project project, @Nullable File fileToSelect) {
        File dir = (fileToSelect != null && fileToSelect.exists()) ? fileToSelect.getParentFile() : getLogDirectory(project);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Run asynchronously off EDT to ensure UI responsiveness
        new Thread(() -> {
            String os = System.getProperty("os.name", "").toLowerCase();
            try {
                if (fileToSelect != null && fileToSelect.exists()) {
                    if (os.contains("win")) {
                        new ProcessBuilder("explorer.exe", "/select," + fileToSelect.getAbsolutePath()).start();
                        return;
                    } else if (os.contains("mac")) {
                        new ProcessBuilder("open", "-R", fileToSelect.getAbsolutePath()).start();
                        return;
                    }
                }
            } catch (Throwable ignored) {}

            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(dir);
                    return;
                }
            } catch (Throwable ignored) {}

            try {
                if (os.contains("win")) {
                    new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", dir.getAbsolutePath()).start();
                } else {
                    new ProcessBuilder("xdg-open", dir.getAbsolutePath()).start();
                }
            } catch (Throwable ignored) {}
        }, "ConfigSwitcher-OpenLogDir").start();

        return true;
    }
}
