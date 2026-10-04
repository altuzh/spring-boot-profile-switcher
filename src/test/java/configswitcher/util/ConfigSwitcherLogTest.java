package configswitcher.util;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigSwitcherLogTest {

    private File tempBaseDir;

    @BeforeEach
    public void setUp() throws IOException {
        tempBaseDir = Files.createTempDirectory("cs-log-test").toFile();
        ConfigSwitcherLog.setTestLogDirectory(tempBaseDir);
        ConfigSwitcherLog.clearExecutionBuffer();
    }

    @AfterEach
    public void tearDown() throws IOException {
        ConfigSwitcherLog.setTestLogDirectory(null);
        ConfigSwitcherLog.clearExecutionBuffer();
        if (tempBaseDir != null && tempBaseDir.exists()) {
            Files.walk(tempBaseDir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
        }
    }

    private Project createMockProject(String name, boolean isDefault) {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return name;
                    }
                    if ("isDefault".equals(method.getName())) {
                        return isDefault;
                    }
                    if ("isDisposed".equals(method.getName())) {
                        return false;
                    }
                    return null;
                }
        );
    }

    @Test
    public void testProjectDirectoryNameSanitization() {
        assertEquals("default", ConfigSwitcherLog.getProjectDirectoryName(null));
        assertEquals("default", ConfigSwitcherLog.getProjectDirectoryName(createMockProject("Default", true)));
        assertEquals("my-spring-app", ConfigSwitcherLog.getProjectDirectoryName(createMockProject("my-spring-app", false)));
        assertEquals("My_App__v2.0_", ConfigSwitcherLog.getProjectDirectoryName(createMockProject("My App (v2.0)", false)));
        assertEquals("foo_bar_baz_123", ConfigSwitcherLog.getProjectDirectoryName(createMockProject("foo/bar\\baz:123", false)));
        assertEquals("project", ConfigSwitcherLog.getProjectDirectoryName(createMockProject("", false)));
        assertEquals("project", ConfigSwitcherLog.getProjectDirectoryName(createMockProject("   ", false)));
        assertEquals("project", ConfigSwitcherLog.getProjectDirectoryName(createMockProject(null, false)));
    }

    @Test
    public void testProjectLogDirectorySeparation() {
        Project projA = createMockProject("ProjectAlpha", false);
        Project projB = createMockProject("ProjectBeta", false);

        File dirA = ConfigSwitcherLog.getLogDirectory(projA);
        File dirB = ConfigSwitcherLog.getLogDirectory(projB);
        File dirDefault = ConfigSwitcherLog.getLogDirectory(null);

        assertNotEquals(dirA.getAbsolutePath(), dirB.getAbsolutePath());
        assertEquals("ProjectAlpha", dirA.getName());
        assertEquals("ProjectBeta", dirB.getName());
        assertEquals(tempBaseDir.getAbsolutePath(), dirDefault.getAbsolutePath());

        assertTrue(dirA.exists(), "Directory for ProjectAlpha should exist");
        assertTrue(dirB.exists(), "Directory for ProjectBeta should exist");

        File fileA = ConfigSwitcherLog.getLogFile(projA);
        File fileB = ConfigSwitcherLog.getLogFile(projB);

        assertEquals(new File(dirA, "spring-boot-profile-switcher.log").getAbsolutePath(), fileA.getAbsolutePath());
        assertEquals(new File(dirB, "spring-boot-profile-switcher.log").getAbsolutePath(), fileB.getAbsolutePath());
    }

    @Test
    public void testProjectLogWritingAndExecutionBufferIsolation() throws IOException {
        Project projA = createMockProject("ServiceAlpha", false);
        Project projB = createMockProject("ServiceBeta", false);

        ConfigSwitcherLog.info(projA, "Log message for Alpha");
        ConfigSwitcherLog.info(projB, "Log message for Beta");
        ConfigSwitcherLog.warn("Log message for global/default");

        // 1. In-memory execution buffers
        String logA = ConfigSwitcherLog.getCurrentExecutionLog(projA);
        String logB = ConfigSwitcherLog.getCurrentExecutionLog(projB);
        String globalLog = ConfigSwitcherLog.getCurrentExecutionLog();

        assertTrue(logA.contains("Log message for Alpha"));
        assertFalse(logA.contains("Log message for Beta"), "Project A log must not contain Project B messages");

        assertTrue(logB.contains("Log message for Beta"));
        assertFalse(logB.contains("Log message for Alpha"), "Project B log must not contain Project A messages");

        assertTrue(globalLog.contains("Log message for Alpha"));
        assertTrue(globalLog.contains("Log message for Beta"));
        assertTrue(globalLog.contains("Log message for global/default"));

        // 2. Physical log files on disk
        File fileA = ConfigSwitcherLog.getLogFile(projA);
        File fileB = ConfigSwitcherLog.getLogFile(projB);
        File fileDefault = ConfigSwitcherLog.getLogFile(null);

        assertTrue(fileA.exists());
        assertTrue(fileB.exists());
        assertTrue(fileDefault.exists());

        String fileContentA = Files.readString(fileA.toPath());
        String fileContentB = Files.readString(fileB.toPath());
        String fileContentDefault = Files.readString(fileDefault.toPath());

        assertTrue(fileContentA.contains("Log message for Alpha"));
        assertFalse(fileContentA.contains("Log message for Beta"));

        assertTrue(fileContentB.contains("Log message for Beta"));
        assertFalse(fileContentB.contains("Log message for Alpha"));

        assertTrue(fileContentDefault.contains("Log message for global/default"));
        assertFalse(fileContentDefault.contains("Log message for Alpha"));
        assertFalse(fileContentDefault.contains("Log message for Beta"));
    }

    @Test
    public void testClearExecutionBufferProjectIsolation() {
        Project projA = createMockProject("ServiceAlpha", false);
        Project projB = createMockProject("ServiceBeta", false);

        ConfigSwitcherLog.info(projA, "Alpha 1");
        ConfigSwitcherLog.info(projB, "Beta 1");

        ConfigSwitcherLog.clearExecutionBuffer(projA);
        assertTrue(ConfigSwitcherLog.getCurrentExecutionLog(projA).isEmpty());
        assertTrue(ConfigSwitcherLog.getCurrentExecutionLog(projB).contains("Beta 1"));
    }
}
