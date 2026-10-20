package configswitcher.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import configswitcher.util.ConfigSwitcherLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Manages persistent application session logging.
 * Replaces and rotates session.log to session.prev.log on each application launch/relaunch,
 * streams raw application stdout/stderr directly into session.log,
 * and provides OS file manager integration to open the folder with the log.
 */
@Service(Service.Level.PROJECT)
public final class SessionLogManager implements Disposable {

    public static final String SESSION_LOG_NAME = "session.log";
    public static final String PREV_SESSION_LOG_NAME = "session.prev.log";
    public static final String MESH_SESSION_JSON_NAME = "mesh-session.json";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Project project;
    private BufferedWriter currentWriter = null;
    private long currentSessionStartTime = 0;
    private long bytesWrittenInCurrentSession = 0;

    private static File testLogDirectory = null;

    public SessionLogManager(@Nullable Project project) {
        this.project = project;
    }

    public static SessionLogManager getInstance(@NotNull Project project) {
        return project.getService(SessionLogManager.class);
    }

    public static void setTestLogDirectory(@Nullable File dir) {
        testLogDirectory = dir;
    }

    public @NotNull File getLogDirectory() {
        if (testLogDirectory != null) {
            if (project == null) {
                if (!testLogDirectory.exists()) {
                    testLogDirectory.mkdirs();
                }
                return testLogDirectory;
            }
            try {
                if (project.isDefault()) {
                    if (!testLogDirectory.exists()) {
                        testLogDirectory.mkdirs();
                    }
                    return testLogDirectory;
                }
            } catch (Throwable ignored) {}

            File projectDir = new File(testLogDirectory, ConfigSwitcherLog.getProjectDirectoryName(project));
            if (!projectDir.exists()) {
                projectDir.mkdirs();
            }
            return projectDir;
        }
        return ConfigSwitcherLog.getLogDirectory(project);
    }

    public @NotNull File getSessionLogFile() {
        return new File(getLogDirectory(), SESSION_LOG_NAME);
    }

    public @NotNull File getPreviousSessionLogFile() {
        return new File(getLogDirectory(), PREV_SESSION_LOG_NAME);
    }

    public @NotNull File getMeshSessionJsonFile() {
        return new File(getLogDirectory(), MESH_SESSION_JSON_NAME);
    }

    /**
     * Starts a new session log.
     * If session.log already exists with data, it is backed up to session.prev.log before being replaced.
     */
    public synchronized void startNewSession(@Nullable String profile) {
        long now = System.currentTimeMillis();
        // Guard against duplicate rapid restarts within 800ms if no data was written yet
        if (currentWriter != null && (now - currentSessionStartTime) < 800 && bytesWrittenInCurrentSession == 0) {
            return;
        }

        closeCurrentWriter();

        File dir = getLogDirectory();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File sessionFile = getSessionLogFile();
        File prevFile = getPreviousSessionLogFile();

        // Rotate session.log -> session.prev.log if sessionFile has content
        if (sessionFile.exists() && sessionFile.length() > 0) {
            try {
                if (prevFile.exists()) {
                    prevFile.delete();
                }
                Files.move(sessionFile.toPath(), prevFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) {
                ConfigSwitcherLog.warn(project, "Failed to rotate session.log to session.prev.log: " + t.getMessage());
            }
        }

        currentSessionStartTime = now;
        bytesWrittenInCurrentSession = 0;

        try {
            FileOutputStream fos = new FileOutputStream(sessionFile, false); // Truncate / replace on relaunch
            currentWriter = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));

            String banner = buildSessionStartBanner(profile);
            currentWriter.write(banner);
            currentWriter.flush();
            bytesWrittenInCurrentSession += banner.length();
        } catch (Throwable t) {
            ConfigSwitcherLog.error(project, "Failed to initialize session.log: " + t.getMessage(), t);
        }
    }

    /**
     * Appends stdout or stderr text to session.log.
     */
    public synchronized void appendOutput(@Nullable String text) {
        if (text == null || text.isEmpty()) return;

        if (currentWriter == null) {
            startNewSession(null);
        }

        if (currentWriter != null) {
            try {
                currentWriter.write(text);
                currentWriter.flush();
                bytesWrittenInCurrentSession += text.length();
            } catch (Throwable t) {
                ConfigSwitcherLog.warn(project, "Failed to write to session.log: " + t.getMessage());
            }
        }
    }

    /**
     * Ends the current session and writes a termination footer.
     */
    public synchronized void endSession(int exitCode) {
        if (currentWriter != null) {
            try {
                String footer = buildSessionEndBanner(exitCode);
                currentWriter.write(footer);
                currentWriter.flush();
            } catch (Throwable ignored) {
            } finally {
                closeCurrentWriter();
            }
        }
    }

    /**
     * Saves the current GraphQL Mesh captured entries JSON into mesh-session.json in the log folder.
     */
    public synchronized void saveMeshSessionJson(@NotNull String json) {
        File meshFile = getMeshSessionJsonFile();
        try (FileOutputStream fos = new FileOutputStream(meshFile, false);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             BufferedWriter bw = new BufferedWriter(osw)) {
            bw.write(json);
            bw.flush();
        } catch (Throwable t) {
            ConfigSwitcherLog.warn(project, "Failed to write mesh-session.json: " + t.getMessage());
        }
    }

    /**
     * Reads session.log (or session.prev.log if session.log is empty) and feeds all lines into MeshOutputAnalyzer.
     * Returns the number of lines processed.
     */
    public int scanSessionLogInAnalyzer() {
        if (project == null || project.isDisposed()) return 0;
        File file = getSessionLogFile();
        if (!file.exists() || file.length() == 0) {
            file = getPreviousSessionLogFile();
        }
        if (!file.exists() || file.length() == 0) {
            return 0;
        }

        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            var analyzer = configswitcher.mesh.service.MeshCaptureService.getInstance(project).getAnalyzer();
            String line;
            while ((line = reader.readLine()) != null) {
                analyzer.processLine(line);
                count++;
            }
        } catch (Throwable t) {
            ConfigSwitcherLog.warn(project, "Failed to scan session log: " + t.getMessage());
        }
        return count;
    }

    /**
     * Opens the folder containing session.log in the OS file manager,
     * highlighting session.log if supported.
     */
    public void openLogFolder() {
        File sessionFile = getSessionLogFile();
        if (sessionFile.exists()) {
            ConfigSwitcherLog.openLogDirectory(project, sessionFile);
        } else {
            ConfigSwitcherLog.openLogDirectory(project, null);
        }
    }

    private synchronized void closeCurrentWriter() {
        if (currentWriter != null) {
            try {
                currentWriter.flush();
                currentWriter.close();
            } catch (Throwable ignored) {}
            currentWriter = null;
        }
    }

    private @NotNull String buildSessionStartBanner(@Nullable String profile) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String projName = (project != null && !project.isDisposed()) ? project.getName() : "Unknown";
        String prof = (profile != null && !profile.isBlank()) ? profile : "default";

        return "================================================================================\n" +
                "Application Session Log\n" +
                "Session Started : " + timestamp + "\n" +
                "Project         : " + projName + "\n" +
                "Active Profile  : " + prof + "\n" +
                "================================================================================\n\n";
    }

    private @NotNull String buildSessionEndBanner(int exitCode) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        return "\n================================================================================\n" +
                "Session Terminated: " + timestamp + " (Exit Code: " + exitCode + ")\n" +
                "================================================================================\n";
    }

    @Override
    public void dispose() {
        closeCurrentWriter();
    }
}
