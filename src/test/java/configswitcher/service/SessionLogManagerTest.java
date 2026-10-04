package configswitcher.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

public class SessionLogManagerTest {

    private File tempDir;
    private SessionLogManager manager;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("session-log-test").toFile();
        SessionLogManager.setTestLogDirectory(tempDir);
        manager = new SessionLogManager(null);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (manager != null) {
            manager.dispose();
        }
        SessionLogManager.setTestLogDirectory(null);
        if (tempDir != null && tempDir.exists()) {
            Files.walk(tempDir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void testSessionCreationAndOutput() throws IOException {
        manager.startNewSession("dev");

        File sessionFile = manager.getSessionLogFile();
        assertTrue(sessionFile.exists(), "session.log should exist");

        manager.appendOutput("First line of output\n");
        manager.appendOutput("Second line of output\n");

        String content = Files.readString(sessionFile.toPath());
        assertTrue(content.contains("Application Session Log"));
        assertTrue(content.contains("Active Profile  : dev"));
        assertTrue(content.contains("First line of output"));
        assertTrue(content.contains("Second line of output"));
    }

    @Test
    public void testReplaceOnRelaunchAndBackup() throws IOException, InterruptedException {
        // 1. Session 1
        manager.startNewSession("dev");
        manager.appendOutput("Session 1 log data\n");

        File sessionFile = manager.getSessionLogFile();
        File prevFile = manager.getPreviousSessionLogFile();

        assertTrue(sessionFile.exists());
        assertFalse(prevFile.exists());

        // Wait slightly to exceed rapid-restart guard
        Thread.sleep(850);

        // 2. Session 2 (Relaunch)
        manager.startNewSession("prod");
        manager.appendOutput("Session 2 log data\n");

        // Verify session.log has been replaced with Session 2
        String currentContent = Files.readString(sessionFile.toPath());
        assertTrue(currentContent.contains("Active Profile  : prod"));
        assertTrue(currentContent.contains("Session 2 log data"));
        assertFalse(currentContent.contains("Session 1 log data"), "Current session.log should not contain Session 1 data");

        // Verify session.prev.log contains Session 1
        assertTrue(prevFile.exists(), "session.prev.log should exist after relaunch");
        String prevContent = Files.readString(prevFile.toPath());
        assertTrue(prevContent.contains("Active Profile  : dev"));
        assertTrue(prevContent.contains("Session 1 log data"));
    }

    @Test
    public void testEndSessionFooter() throws IOException {
        manager.startNewSession("local");
        manager.appendOutput("Working...\n");
        manager.endSession(0);

        File sessionFile = manager.getSessionLogFile();
        String content = Files.readString(sessionFile.toPath());
        assertTrue(content.contains("Session Terminated"));
        assertTrue(content.contains("Exit Code: 0"));
    }

    @Test
    public void testSaveMeshSessionJson() throws IOException {
        String testJson = "[{\"id\": 1, \"query\": \"query GetUser { user { id } }\"}]";
        manager.saveMeshSessionJson(testJson);

        File meshFile = manager.getMeshSessionJsonFile();
        assertTrue(meshFile.exists(), "mesh-session.json should exist");

        String content = Files.readString(meshFile.toPath());
        assertEquals(testJson, content);
    }

    @Test
    public void testMultiProjectLogIsolation() throws IOException {
        com.intellij.openapi.project.Project projA = (com.intellij.openapi.project.Project) java.lang.reflect.Proxy.newProxyInstance(
                com.intellij.openapi.project.Project.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.project.Project.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return "ProjectOne";
                    if ("isDefault".equals(method.getName())) return false;
                    if ("isDisposed".equals(method.getName())) return false;
                    if (method.getReturnType().equals(boolean.class)) return false;
                    return null;
                }
        );
        com.intellij.openapi.project.Project projB = (com.intellij.openapi.project.Project) java.lang.reflect.Proxy.newProxyInstance(
                com.intellij.openapi.project.Project.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.project.Project.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return "ProjectTwo";
                    if ("isDefault".equals(method.getName())) return false;
                    if ("isDisposed".equals(method.getName())) return false;
                    if (method.getReturnType().equals(boolean.class)) return false;
                    return null;
                }
        );

        SessionLogManager managerA = new SessionLogManager(projA);
        SessionLogManager managerB = new SessionLogManager(projB);

        try {
            managerA.startNewSession("dev-a");
            managerB.startNewSession("dev-b");

            managerA.appendOutput("Data from Project One\n");
            managerB.appendOutput("Data from Project Two\n");

            File logA = managerA.getSessionLogFile();
            File logB = managerB.getSessionLogFile();

            assertNotEquals(logA.getAbsolutePath(), logB.getAbsolutePath());
            assertEquals("ProjectOne", logA.getParentFile().getName());
            assertEquals("ProjectTwo", logB.getParentFile().getName());

            String contentA = Files.readString(logA.toPath());
            String contentB = Files.readString(logB.toPath());

            assertTrue(contentA.contains("Data from Project One"));
            assertFalse(contentA.contains("Data from Project Two"));

            assertTrue(contentB.contains("Data from Project Two"));
            assertFalse(contentB.contains("Data from Project One"));
        } finally {
            managerA.dispose();
            managerB.dispose();
        }
    }
}
