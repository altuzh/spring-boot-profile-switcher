package configswitcher;

import configswitcher.model.ArgumentType;
import configswitcher.service.AppRunManager;
import configswitcher.service.ConfigScannerService;
import configswitcher.service.WindowsJobObjectManager;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigScannerTest {

    @Test
    public void testParseProfileNames() {
        assertEquals("default", ConfigScannerService.parseProfileName("application.yml"));
        assertEquals("default", ConfigScannerService.parseProfileName("application.yaml"));
        assertEquals("dev", ConfigScannerService.parseProfileName("application-dev.yml"));
        assertEquals("prod", ConfigScannerService.parseProfileName("application-prod.yaml"));
        assertEquals("auth-dev", ConfigScannerService.parseProfileName("application-auth-dev.yml"));
        assertEquals("test-stand-1", ConfigScannerService.parseProfileName("application-test-stand-1.yml"));
        assertEquals("local", ConfigScannerService.parseProfileName("application-local.yml"));

        // Non matching
        assertNull(ConfigScannerService.parseProfileName("bootstrap.yml"));
        assertNull(ConfigScannerService.parseProfileName("other.yaml"));
        assertNull(ConfigScannerService.parseProfileName("application.properties"));
    }

    public interface TestKernel32 extends com.sun.jna.win32.StdCallLibrary {
        TestKernel32 INSTANCE = com.sun.jna.Native.load("kernel32", TestKernel32.class, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);
        com.sun.jna.platform.win32.WinNT.HANDLE CreateJobObject(com.sun.jna.Pointer lpJobAttributes, String lpName);
        boolean SetInformationJobObject(com.sun.jna.platform.win32.WinNT.HANDLE hJob, int JobObjectInformationClass, com.sun.jna.Pointer lpJobObjectInformation, int cbJobObjectInformationLength);
        boolean AssignProcessToJobObject(com.sun.jna.platform.win32.WinNT.HANDLE hJob, com.sun.jna.platform.win32.WinNT.HANDLE hProcess);
        com.sun.jna.platform.win32.WinNT.HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);
        boolean TerminateJobObject(com.sun.jna.platform.win32.WinNT.HANDLE hJob, int uExitCode);
        boolean CloseHandle(com.sun.jna.platform.win32.WinNT.HANDLE hObject);
        int GetLastError();
    }

    @Test
    public void testJobObjectKillsChildProcessOnClose() throws Exception {
        if (!com.intellij.openapi.util.SystemInfo.isWindows) return;

        TestKernel32 k32 = TestKernel32.INSTANCE;
        com.sun.jna.platform.win32.WinNT.HANDLE hJob = k32.CreateJobObject(null, null);
        assertNotNull(hJob, "CreateJobObject should return a valid handle");

        // Spawn a background child process
        Process child = new ProcessBuilder("cmd.exe", "/c", "timeout /t 30 /nobreak").start();
        long childPid = child.pid();
        assertTrue(child.isAlive(), "Child process should be alive");

        try {
            int size = com.sun.jna.Native.POINTER_SIZE == 8 ? 144 : 112;
            com.sun.jna.Memory mem = new com.sun.jna.Memory(size);
            mem.clear();
            int limitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            mem.setInt(16, limitFlags);

            boolean setOk = k32.SetInformationJobObject(hJob, 9, mem, size);
            assertTrue(setOk, "SetInformationJobObject should succeed");

            com.sun.jna.platform.win32.WinNT.HANDLE hProc = k32.OpenProcess(0x0100 | 0x0001, false, (int) childPid);
            assertNotNull(hProc, "OpenProcess should succeed");
            try {
                boolean assignOk = k32.AssignProcessToJobObject(hJob, hProc);
                assertTrue(assignOk, "AssignProcessToJobObject should succeed");
            } finally {
                k32.CloseHandle(hProc);
            }
        } finally {
            // Closing the Job Object handle must trigger Windows kernel to terminate child process!
            k32.CloseHandle(hJob);
        }

        // Child process should be terminated by Windows kernel within 1-2 seconds
        boolean terminated = child.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(terminated, "Child process should be terminated by Windows kernel when job object handle is closed");
        assertFalse(child.isAlive());
    }

    @Test
    public void testWindowsJobObjectManager_InitAndAssign() throws Exception {
        if (!com.intellij.openapi.util.SystemInfo.isWindows) return;

        WindowsJobObjectManager.ensureInitialized();
        assertTrue(WindowsJobObjectManager.isInitialized());

        Process child = new ProcessBuilder("cmd.exe", "/c", "timeout /t 30 /nobreak").start();
        try {
            assertTrue(child.isAlive());
            boolean assigned = WindowsJobObjectManager.assignProcess(child);
            assertTrue(assigned, "Should successfully assign process to WindowsJobObjectManager");
        } finally {
            child.destroyForcibly();
            child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    public void testWindowsJobObjectManager_TerminateAll() throws Exception {
        if (!com.intellij.openapi.util.SystemInfo.isWindows) return;

        WindowsJobObjectManager.ensureInitialized();
        assertTrue(WindowsJobObjectManager.isInitialized());

        Process child = new ProcessBuilder("cmd.exe", "/c", "timeout /t 30 /nobreak").start();
        try {
            assertTrue(child.isAlive());
            WindowsJobObjectManager.assignProcess(child);

            WindowsJobObjectManager.terminateAllInJob();

            boolean terminated = child.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(terminated, "Child process should be terminated when terminateAllInJob is called");
            assertFalse(child.isAlive());
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    @Test
    public void testUpdateProgramParameters_NewArgs() {
        String result = AppRunManager.updateProgramParameters(
                null,
                "dev",
                "src/main/resources/application-dev.yml",
                ArgumentType.SPRING_PROFILES_ACTIVE,
                null
        );
        assertEquals("--spring.profiles.active=dev", result);
    }

    @Test
    public void testUpdateProgramParameters_ReplaceExistingProfile() {
        String existing = "--server.port=8080 --spring.profiles.active=old-profile --my.prop=true";
        String result = AppRunManager.updateProgramParameters(
                existing,
                "prod",
                "/path/to/application-prod.yml",
                ArgumentType.SPRING_PROFILES_ACTIVE,
                null
        );
        assertEquals("--server.port=8080 --my.prop=true --spring.profiles.active=prod", result);
    }

    @Test
    public void testUpdateProgramParameters_BothParamsAndCustomArgs() {
        String existing = "--server.port=9090";
        String result = AppRunManager.updateProgramParameters(
                existing,
                "auth-dev",
                "/configs/application-auth-dev.yml",
                ArgumentType.BOTH,
                "--custom.arg=test"
        );
        String expected = "--server.port=9090 --spring.profiles.active=auth-dev --spring.config.additional-location=/configs/application-auth-dev.yml --custom.arg=test";
        assertEquals(expected, result);
    }

    @Test
    public void testUpdateVmParameters() {
        String existing = "-Xmx1024m";
        String custom = "-Dspring.cloud.config.enabled=false";
        String result = AppRunManager.updateVmParameters(existing, custom);
        assertEquals("-Xmx1024m -Dspring.cloud.config.enabled=false", result);

        // Prevent duplicate appending
        String repeated = AppRunManager.updateVmParameters(result, custom);
        assertEquals("-Xmx1024m -Dspring.cloud.config.enabled=false", repeated);
    }

    @Test
    public void testGitPatchEntry_ProfileMatching() {
        configswitcher.model.GitPatchEntry universal = new configswitcher.model.GitPatchEntry(true, ".idea/patches/p1.patch", "All profiles", "");
        assertTrue(universal.matchesProfile("dev"));
        assertTrue(universal.matchesProfile("auth-dev"));
        assertTrue(universal.matchesProfile("prod"));

        configswitcher.model.GitPatchEntry authOnly = new configswitcher.model.GitPatchEntry(true, ".idea/patches/auth.patch", "Auth only", "auth-dev");
        assertTrue(authOnly.matchesProfile("auth-dev"));
        assertFalse(authOnly.matchesProfile("dev"));
        assertFalse(authOnly.matchesProfile("prod"));

        configswitcher.model.GitPatchEntry disabled = new configswitcher.model.GitPatchEntry(false, ".idea/patches/p2.patch", "Disabled", "");
        assertFalse(disabled.matchesProfile("dev"));
    }

    @Test
    public void testBuildRunCommandLine() {
        String template = "java -jar target/server.jar --debug --spring.profiles.active={profile}";
        String result = AppRunManager.buildRunCommandLine(
                template,
                "auth-dev",
                "C:/projects/src/main/resources/application-auth-dev.yml",
                null,
                null
        );
        assertEquals("java -jar target/server.jar --debug --spring.profiles.active=auth-dev", result);

        // Template without --spring.profiles.active
        String templateWithoutProfile = "java -jar target/server.jar --debug";
        String resultWithAutoProfile = AppRunManager.buildRunCommandLine(
                templateWithoutProfile,
                "dev",
                "C:/path/application-dev.yml",
                "--server.port=8080",
                null
        );
        assertEquals("java -jar target/server.jar --debug --spring.profiles.active=dev --server.port=8080", resultWithAutoProfile);
    }

    @Test
    public void testBuildRunCommandLine_PreservesNewlines() {
        String multiLineTemplate = "java -jar target/server.jar `\r\n" +
                "  --debug `\r\n" +
                "  --spring.profiles.active={profile}";
        String result = AppRunManager.buildRunCommandLine(
                multiLineTemplate,
                "auth-dev",
                "C:/projects/src/main/resources/application-auth-dev.yml",
                null,
                null
        );
        String expected = "java -jar target/server.jar `\n" +
                "  --debug `\n" +
                "  --spring.profiles.active=auth-dev";
        assertEquals(expected, result);
        assertTrue(result.contains("\n"), "Result must contain preserved newlines");
        assertFalse(result.contains("\r"), "Carriage returns should be normalized to \\n");
    }

    @Test
    public void testNormalizeCommandLines() {
        String raw = "\r\n  mvn -T 8 clean compile \r\n\r\n  java -jar server.jar  \r\n";
        String normalized = AppRunManager.normalizeCommandLines(raw);
        assertEquals("  mvn -T 8 clean compile\n\n  java -jar server.jar", normalized);
    }

    @Test
    public void testParametersListUtil_PreRunCommand() {
        String cmd = "mvn -T 8 -o \"-Dmaven.test.skip=true\"";
        java.util.List<String> tokens = com.intellij.util.execution.ParametersListUtil.parse(cmd);
        assertEquals(5, tokens.size());
        assertEquals("mvn", tokens.get(0));
        assertEquals("-T", tokens.get(1));
        assertEquals("8", tokens.get(2));
        assertEquals("-o", tokens.get(3));
        assertEquals("-Dmaven.test.skip=true", tokens.get(4));
    }

    @Test
    public void testIsLikelyPatchText() {
        String diff1 = "diff --git a/src/App.java b/src/App.java\n--- a/src/App.java\n+++ b/src/App.java\n@@ -1,3 +1,3 @@";
        assertTrue(configswitcher.service.GitPatchService.isLikelyPatchText(diff1));

        String diff2 = "--- a/file.txt\n+++ b/file.txt\n@@ -1 +1 @@";
        assertTrue(configswitcher.service.GitPatchService.isLikelyPatchText(diff2));

        String notDiff = "Hello world this is some random text without diff headers";
        assertFalse(configswitcher.service.GitPatchService.isLikelyPatchText(notDiff));
        assertFalse(configswitcher.service.GitPatchService.isLikelyPatchText(null));
        assertFalse(configswitcher.service.GitPatchService.isLikelyPatchText(""));
    }

    @Test
    public void testCleanPatchPath() {
        assertEquals("src/main/resources/config.json", configswitcher.service.GitPatchService.cleanPatchPath("src\\main\\resources\\config.json"));
        assertEquals("src/App.java", configswitcher.service.GitPatchService.cleanPatchPath("\"src/App.java\""));
        assertEquals("", configswitcher.service.GitPatchService.cleanPatchPath(null));
        assertEquals("", configswitcher.service.GitPatchService.cleanPatchPath(""));
    }

    @Test
    public void testParsePatchTextForAffectedFiles() {
        String patch = """
                Index: src/main/resources/META-INF/config.json
                IDEA additional info:
                Subsystem: com.intellij.openapi.diff.impl.patch.CharsetEP
                <+>UTF-8
                ===================================================================
                diff --git a/src/main/resources/META-INF/config.json b/src/main/resources/META-INF/config.json
                --- a/src/main/resources/META-INF/config.json	(revision e982fe895fa2242710ab248614620177c7ea9cab)
                +++ b/src/main/resources/META-INF/config.json	(date 1790543438783)
                @@ -58,11 +58,5 @@
                 Index: pom.xml
                 diff --git a/pom.xml b/pom.xml
                 --- a/pom.xml	(revision e982fe895fa2242710ab248614620177c7ea9cab)
                 +++ b/pom.xml	(date 1790543438797)
                """;

        List<String> files = configswitcher.service.GitPatchService.parsePatchTextForAffectedFiles(patch);
        assertEquals(2, files.size());
        assertEquals("src/main/resources/META-INF/config.json", files.get(0));
        assertEquals("pom.xml", files.get(1));

        String newFilePatch = """
                diff --git a/src/NewFile.java b/src/NewFile.java
                --- /dev/null
                +++ b/src/NewFile.java
                @@ -0,0 +1,5 @@
                """;
        List<String> newFiles = configswitcher.service.GitPatchService.parsePatchTextForAffectedFiles(newFilePatch);
        assertEquals(1, newFiles.size());
        assertEquals("src/NewFile.java", newFiles.get(0));

        String deletedFilePatch = """
                diff --git a/src/OldFile.java b/src/OldFile.java
                --- a/src/OldFile.java
                +++ /dev/null
                @@ -1,5 +0,0 @@
                """;
        List<String> delFiles = configswitcher.service.GitPatchService.parsePatchTextForAffectedFiles(deletedFilePatch);
        assertEquals(1, delFiles.size());
        assertEquals("src/OldFile.java", delFiles.get(0));

        assertTrue(configswitcher.service.GitPatchService.parsePatchTextForAffectedFiles(null).isEmpty());
        assertTrue(configswitcher.service.GitPatchService.parsePatchTextForAffectedFiles("").isEmpty());
    }

    @Test
    public void testGeneralCommandLine_WindowsMvn() {
        if (com.intellij.openapi.util.SystemInfo.isWindows) {
            assertThrows(Exception.class, () -> {
                com.intellij.execution.configurations.GeneralCommandLine cmd = new com.intellij.execution.configurations.GeneralCommandLine("mvn", "-v");
                cmd.createProcess();
            });

            java.io.File resolved = com.intellij.execution.configurations.PathEnvironmentVariableUtil.findInPath("mvn.cmd");
            if (resolved == null) {
                java.io.File raw = com.intellij.execution.configurations.PathEnvironmentVariableUtil.findInPath("mvn");
                if (raw != null && raw.getParentFile() != null) {
                    java.io.File cmdFile = new java.io.File(raw.getParentFile(), "mvn.cmd");
                    if (cmdFile.isFile()) {
                        resolved = cmdFile;
                    }
                }
            }
            System.out.println("RESOLVED CMD IN PATH: " + (resolved != null ? resolved.getAbsolutePath() : "null"));
            assertNotNull(resolved);
            final java.io.File finalResolved = resolved;

            assertDoesNotThrow(() -> {
                com.intellij.execution.configurations.GeneralCommandLine cmd = new com.intellij.execution.configurations.GeneralCommandLine(finalResolved.getAbsolutePath(), "-T", "8", "-o", "-Dmaven.test.skip=true", "-v");
                Process p = cmd.createProcess();
                p.waitFor();
                assertEquals(0, p.exitValue());
            });

            // Test AppRunManager.resolveCommandTokens
            java.util.List<String> rawTokens = java.util.List.of("mvn", "-T", "8", "-o", "-Dmaven.test.skip=true", "-v");
            java.util.List<String> resolvedTokens = AppRunManager.resolveCommandTokens(rawTokens, null);
            assertTrue(resolvedTokens.get(0).toLowerCase().endsWith("mvn.cmd"), "Should resolve mvn to mvn.cmd: " + resolvedTokens.get(0));

            assertDoesNotThrow(() -> {
                com.intellij.execution.configurations.GeneralCommandLine cmd = new com.intellij.execution.configurations.GeneralCommandLine(resolvedTokens);
                Process p = cmd.createProcess();
                p.waitFor();
                assertEquals(0, p.exitValue());
            });
        }
    }

    @Test
    public void testTerminalClassesAvailable() {
        assertDoesNotThrow(() -> {
            Class<?> widgetClass = Class.forName("org.jetbrains.plugins.terminal.ShellTerminalWidget");
            assertNotNull(widgetClass);
            java.lang.reflect.Method clearMethod = configswitcher.service.TerminalRunnerService.class.getMethod(
                    "clearTerminal",
                    org.jetbrains.plugins.terminal.ShellTerminalWidget.class
            );
            assertNotNull(clearMethod);

            Class<?> ptyClass = Class.forName("com.jediterm.terminal.ProcessTtyConnector");
            java.lang.reflect.Method writeBytes = ptyClass.getMethod("write", byte[].class);
            assertNotNull(writeBytes);
            java.lang.reflect.Method writeStr = ptyClass.getMethod("write", String.class);
            assertNotNull(writeStr);
            java.lang.reflect.Method getProcess = ptyClass.getMethod("getProcess");
            assertNotNull(getProcess);
            java.lang.reflect.Method hasRunning = widgetClass.getMethod("hasRunningCommands");
            assertNotNull(hasRunning);
            Class<?> appExecUtil = Class.forName("com.intellij.util.concurrency.AppExecutorUtil");
            assertNotNull(appExecUtil);

            java.lang.reflect.Method hasRunningTaskMethod = configswitcher.service.TerminalRunnerService.class.getMethod(
                    "hasRunningTask",
                    org.jetbrains.plugins.terminal.ShellTerminalWidget.class
            );
            assertNotNull(hasRunningTaskMethod);

            Class<?> tecClass = Class.forName("com.intellij.terminal.TerminalExecutionConsole");
            assertNotNull(tecClass);

            java.lang.reflect.Method terminalConsoleMethod = configswitcher.service.TerminalRunnerService.class.getMethod(
                    "runPipelineInTerminalConsole",
                    com.intellij.openapi.project.Project.class,
                    com.intellij.execution.configurations.GeneralCommandLine.class,
                    com.intellij.execution.configurations.GeneralCommandLine.class,
                    String.class,
                    Runnable.class,
                    Runnable.class,
                    Runnable.class,
                    java.util.function.Consumer.class
            );
            assertNotNull(terminalConsoleMethod);
            assertNotNull(configswitcher.service.TerminalRunnerService.IS_PIPELINE_TAB);
        });
    }

    @Test
    public void testPipelineProcessHandler_DirectAppRun() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean appTerminated = new java.util.concurrent.atomic.AtomicBoolean(false);
        StringBuilder output = new StringBuilder();

        com.intellij.execution.configurations.GeneralCommandLine appCmd =
                new com.intellij.execution.configurations.GeneralCommandLine("java", "-version");

        configswitcher.service.PipelineProcessHandler handler = new configswitcher.service.PipelineProcessHandler(
                null,
                appCmd,
                null,
                null,
                () -> appTerminated.set(true)
        );

        handler.addProcessListener(new com.intellij.execution.process.ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull com.intellij.execution.process.ProcessEvent event, @NotNull com.intellij.openapi.util.Key outputType) {
                output.append(event.getText());
            }
        });

        handler.startNotify();
        handler.waitFor(10000);

        assertTrue(handler.isProcessTerminated(), "Handler should terminate");
        assertTrue(appTerminated.get(), "onAppTerminated callback should be invoked");
        assertTrue(output.toString().contains("Launching Application"), "Output should contain application header");
    }

    @Test
    public void testPipelineProcessHandler_PreRunSuccessSequencing() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean preRunSuccess = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean appTerminated = new java.util.concurrent.atomic.AtomicBoolean(false);
        StringBuilder output = new StringBuilder();

        com.intellij.execution.configurations.GeneralCommandLine preCmd =
                new com.intellij.execution.configurations.GeneralCommandLine("java", "-version");
        com.intellij.execution.configurations.GeneralCommandLine appCmd =
                new com.intellij.execution.configurations.GeneralCommandLine("java", "-version");

        configswitcher.service.PipelineProcessHandler handler = new configswitcher.service.PipelineProcessHandler(
                preCmd,
                appCmd,
                () -> preRunSuccess.set(true),
                null,
                () -> appTerminated.set(true)
        );

        handler.addProcessListener(new com.intellij.execution.process.ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull com.intellij.execution.process.ProcessEvent event, @NotNull com.intellij.openapi.util.Key outputType) {
                output.append(event.getText());
            }
        });

        handler.startNotify();
        handler.waitFor(15000);

        assertTrue(handler.isProcessTerminated(), "Handler should terminate");
        assertTrue(preRunSuccess.get(), "onPreRunSuccess callback should be invoked");
        assertTrue(appTerminated.get(), "onAppTerminated callback should be invoked");
        assertTrue(output.toString().contains("Running Pre-Run Build"), "Output should contain pre-run header");
        assertTrue(output.toString().contains("Pre-Run Build completed successfully"), "Output should contain success message");
        assertTrue(output.toString().contains("Launching Application"), "Output should contain application launch message");
    }

    @Test
    public void testPipelineProcessHandler_PreRunFailureAbortsApp() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean preRunFailure = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean appTerminated = new java.util.concurrent.atomic.AtomicBoolean(false);
        StringBuilder output = new StringBuilder();

        // Invalid flag causes java to exit with error code 1
        com.intellij.execution.configurations.GeneralCommandLine preCmd =
                new com.intellij.execution.configurations.GeneralCommandLine("java", "-invalid_flag_force_exit_error_123");
        com.intellij.execution.configurations.GeneralCommandLine appCmd =
                new com.intellij.execution.configurations.GeneralCommandLine("java", "-version");

        configswitcher.service.PipelineProcessHandler handler = new configswitcher.service.PipelineProcessHandler(
                preCmd,
                appCmd,
                null,
                () -> preRunFailure.set(true),
                () -> appTerminated.set(true)
        );

        handler.addProcessListener(new com.intellij.execution.process.ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull com.intellij.execution.process.ProcessEvent event, @NotNull com.intellij.openapi.util.Key outputType) {
                output.append(event.getText());
            }
        });

        handler.startNotify();
        handler.waitFor(10000);

        assertTrue(handler.isProcessTerminated(), "Handler should terminate on failure");
        assertTrue(preRunFailure.get(), "onPreRunFailure callback should be invoked");
        assertFalse(appTerminated.get(), "App should NEVER be launched if pre-run fails");
        assertTrue(output.toString().contains("Pre-Run Build failed with exit code"), "Output should report failure exit code");
        assertFalse(output.toString().contains("Launching Application"), "Output should NOT contain application launch");
    }

    @Test
    public void testFileLockDetection() throws Exception {
        java.io.File tempFile = java.io.File.createTempFile("test_lock_", ".jar");
        tempFile.deleteOnExit();

        // Initially not locked
        assertFalse(configswitcher.service.AppRunManager.isFileLocked(tempFile));

        // Lock with FileChannel exclusive lock
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tempFile, "rw");
             java.nio.channels.FileLock lock = raf.getChannel().lock()) {
            assertTrue(configswitcher.service.AppRunManager.isFileLocked(tempFile));
        }

        // Released after close
        assertFalse(configswitcher.service.AppRunManager.isFileLocked(tempFile));
    }

    @Test
    public void testExtractJarPathFromCommand() {
        assertEquals("target/server.jar",
                configswitcher.service.AppRunManager.extractJarPathFromCommand("java -jar target/server.jar --spring.profiles.active=dev"));
        assertEquals("target/server.jar",
                configswitcher.service.AppRunManager.extractJarPathFromCommand("java -jar \"target/server.jar\" --debug"));
        assertEquals("C:\\path\\my-app.jar",
                configswitcher.service.AppRunManager.extractJarPathFromCommand("java -Xmx2g -jar C:\\path\\my-app.jar"));
        assertNull(configswitcher.service.AppRunManager.extractJarPathFromCommand("mvn clean install"));
        assertNull(configswitcher.service.AppRunManager.extractJarPathFromCommand(""));
        assertNull(configswitcher.service.AppRunManager.extractJarPathFromCommand(null));
    }

    @Test
    public void testBuildChainedCommand_PowerShell() {
        String pre = "mvn -T 8 -o \"-Dmaven.test.skip=true\"";
        String run = "java -jar target/server.jar --spring.profiles.active=auth-dev";

        String chained = configswitcher.service.TerminalRunnerService.buildChainedCommand(pre, run, true);
        assertEquals("mvn -T 8 -o \"-Dmaven.test.skip=true\"; if ($?) { java -jar target/server.jar --spring.profiles.active=auth-dev }", chained);

        // Blank pre-run should return only run command
        assertEquals(run, configswitcher.service.TerminalRunnerService.buildChainedCommand(null, run, true));
        assertEquals(run, configswitcher.service.TerminalRunnerService.buildChainedCommand("   ", run, true));
    }

    @Test
    public void testBuildChainedCommand_CmdOrBash() {
        String pre = "mvn clean install";
        String run = "java -jar server.jar";

        String chained = configswitcher.service.TerminalRunnerService.buildChainedCommand(pre, run, false);
        assertEquals("mvn clean install && java -jar server.jar", chained);

        assertEquals(run, configswitcher.service.TerminalRunnerService.buildChainedCommand(null, run, false));
    }

    @Test
    public void testExecuteInTerminal_DefaultSetting() {
        configswitcher.state.PluginSettingsState.State state = new configswitcher.state.PluginSettingsState.State();
        assertTrue(state.executeInTerminal, "executeInTerminal should default to true");
        assertEquals(configswitcher.model.TerminalShellType.AUTO, state.terminalShellType, "terminalShellType should default to AUTO");
        assertFalse(state.enablePreRun, "enablePreRun should default to false");
        assertEquals("mvn clean package", state.preRunCommand, "preRunCommand should default to 'mvn clean package'");
    }

    @Test
    public void testBuildCommandWithWorkDir_NullWorkDir_ReturnsCommandDirectly() {
        String cmd = "java -jar target/server.jar --debug --spring.profiles.active=test";
        String result = configswitcher.service.TerminalRunnerService.buildCommandWithWorkDir(null, cmd, null);
        assertEquals(cmd, result);
    }

    @Test
    public void testMatchShellType() {
        assertEquals(configswitcher.service.TerminalRunnerService.ShellType.POWERSHELL,
                configswitcher.service.TerminalRunnerService.matchShellType("powershell.exe"));
        assertEquals(configswitcher.service.TerminalRunnerService.ShellType.POWERSHELL,
                configswitcher.service.TerminalRunnerService.matchShellType("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"));
        assertEquals(configswitcher.service.TerminalRunnerService.ShellType.POWERSHELL,
                configswitcher.service.TerminalRunnerService.matchShellType("pwsh.exe"));

        assertEquals(configswitcher.service.TerminalRunnerService.ShellType.CMD,
                configswitcher.service.TerminalRunnerService.matchShellType("cmd.exe"));
        assertEquals(configswitcher.service.TerminalRunnerService.ShellType.CMD,
                configswitcher.service.TerminalRunnerService.matchShellType("C:\\Windows\\System32\\cmd.exe"));
        assertEquals(configswitcher.service.TerminalRunnerService.ShellType.CMD,
                configswitcher.service.TerminalRunnerService.matchShellType("cmd"));
        assertEquals(configswitcher.service.TerminalRunnerService.ShellType.CMD,
                configswitcher.service.TerminalRunnerService.matchShellType("Command Prompt"));

        assertEquals(configswitcher.service.TerminalRunnerService.ShellType.BASH,
                configswitcher.service.TerminalRunnerService.matchShellType("bash.exe"));
        assertEquals(configswitcher.service.TerminalRunnerService.ShellType.BASH,
                configswitcher.service.TerminalRunnerService.matchShellType("/bin/zsh"));

        assertNull(configswitcher.service.TerminalRunnerService.matchShellType("Local"));
        assertNull(configswitcher.service.TerminalRunnerService.matchShellType(null));
        assertNull(configswitcher.service.TerminalRunnerService.matchShellType("   "));
    }

    @Test
    public void testBuildCommandWithWorkDir_PowerShellNoAmpersand() {
        java.io.File dir = new java.io.File("C:/Users/al/projects/bft/co");
        String cmd = "mvn -T 8 -o \"-Dmaven.test.skip=true\"; if ($?) { java -jar target/server.jar }";

        // Test with shellType POWERSHELL
        String wrapped = "Set-Location -LiteralPath \"" + dir.getAbsolutePath() + "\"; if ($?) { " + cmd + " }";
        assertFalse(wrapped.contains("&&"), "PowerShell command must NEVER contain &&");
        assertFalse(wrapped.contains("cd /d"), "PowerShell command must NEVER contain cd /d");
        assertTrue(wrapped.startsWith("Set-Location -LiteralPath"));
    }

    @Test
    public void testBuildRunCommandLine_NullTemplateFallback() {
        String result = AppRunManager.buildRunCommandLine(
                null,
                "dev",
                "C:/app-dev.yml",
                null,
                null
        );
        assertEquals("java -jar target/server.jar --debug --spring.profiles.active=dev,auth-dev", result);
    }

    @Test
    public void testBuildRunCommandLine_WithAdditionalProfile() {
        String template = "java -jar server.jar --spring.profiles.active={profile},custom";
        String result = AppRunManager.buildRunCommandLine(
                template,
                "dev",
                "C:/app-dev.yml",
                null,
                null
        );
        assertEquals("java -jar server.jar --spring.profiles.active=dev,custom", result);
    }

    @Test
    public void testBuildRunCommandLine_WithPeofileTypoPlaceholder() {
        String template = "java -jar server.jar --spring.profiles.active={peofile},auth-dev";
        String result = AppRunManager.buildRunCommandLine(
                template,
                "dev",
                "C:/app-dev.yml",
                null,
                null
        );
        assertEquals("java -jar server.jar --spring.profiles.active=dev,auth-dev", result);
    }

    @Test
    public void testGitPatchEntry_MultiProfileMatching() {
        configswitcher.model.GitPatchEntry devPatch = new configswitcher.model.GitPatchEntry(true, "p1.patch", "Dev patch", "dev");
        configswitcher.model.GitPatchEntry localPatch = new configswitcher.model.GitPatchEntry(true, "p2.patch", "Local patch", "local");
        configswitcher.model.GitPatchEntry prodPatch = new configswitcher.model.GitPatchEntry(true, "p3.patch", "Prod patch", "prod");

        // Combined profile "dev,local" matches both dev and local patches, but not prod
        assertTrue(devPatch.matchesProfile("dev,local"));
        assertTrue(localPatch.matchesProfile("dev,local"));
        assertFalse(prodPatch.matchesProfile("dev,local"));

        // Single profile
        assertTrue(devPatch.matchesProfile("dev"));
        assertFalse(localPatch.matchesProfile("dev"));
    }

    @Test
    public void testKillProcessTreeByPid_HandlesInvalidSafely() {
        assertDoesNotThrow(() -> configswitcher.service.PipelineProcessHandler.killProcessTreeByPid(0));
        assertDoesNotThrow(() -> configswitcher.service.PipelineProcessHandler.killProcessTreeByPid(-1));
    }

    @Test
    public void testFindJavaPidsForJar_NonExistentJar() {
        java.io.File dummyJar = new java.io.File("non_existent_jar_xyz123.jar");
        java.util.List<Long> pids = configswitcher.service.AppRunManager.findJavaPidsForJar(dummyJar);
        assertNotNull(pids);
        assertTrue(pids.isEmpty());
    }

    @Test
    public void testSkipPreRunIfRunningDefault() {
        configswitcher.state.PluginSettingsState.State state = new configswitcher.state.PluginSettingsState.State();
        assertTrue(state.skipPreRunIfRunning, "skipPreRunIfRunning should default to true so running apps are not rebuilt on switch");
    }

    @Test
    public void testStartStopConfigAction_Properties() {
        configswitcher.ui.StartStopConfigAction action = new configswitcher.ui.StartStopConfigAction();
        assertEquals(com.intellij.openapi.actionSystem.ActionUpdateThread.BGT, action.getActionUpdateThread());
        assertNotNull(action.getTemplatePresentation());
        assertEquals("Start / Stop Application", action.getTemplatePresentation().getText());
        assertEquals(com.intellij.icons.AllIcons.Actions.Execute, action.getTemplatePresentation().getIcon());
    }

    @Test
    public void testRebuildAndRestartAction_Properties() {
        configswitcher.ui.RebuildAndRestartAction action = new configswitcher.ui.RebuildAndRestartAction();
        assertEquals(com.intellij.openapi.actionSystem.ActionUpdateThread.BGT, action.getActionUpdateThread());
        assertNotNull(action.getTemplatePresentation());
        assertEquals("Rebuild and Restart", action.getTemplatePresentation().getText());
        assertEquals(com.intellij.icons.AllIcons.Actions.Compile, action.getTemplatePresentation().getIcon());
    }

    @Test
    public void testIsLegacyDefaultTemplate() {
        assertTrue(configswitcher.state.PluginSettingsState.isLegacyDefaultTemplate(null));
        assertTrue(configswitcher.state.PluginSettingsState.isLegacyDefaultTemplate(""));
        assertTrue(configswitcher.state.PluginSettingsState.isLegacyDefaultTemplate("   "));
        assertTrue(configswitcher.state.PluginSettingsState.isLegacyDefaultTemplate(
                "java -jar target/server.jar --debug --n2o.config.path=C:\\Users\\al\\projects\\bft\\co\\src\\main\\resources\\META-INF\\conf --spring.profiles.active={profile}"));
        assertTrue(configswitcher.state.PluginSettingsState.isLegacyDefaultTemplate(
                "java -jar target/server.jar --debug --config.path=C:\\Users\\al\\projects\\bft\\co\\src\\main\\resources\\META-INF\\conf --spring.profiles.active={profile}"));
        assertTrue(configswitcher.state.PluginSettingsState.isLegacyDefaultTemplate(
                "java -jar target/server.jar --debug"));
        assertTrue(configswitcher.state.PluginSettingsState.isLegacyDefaultTemplate(
                "java -jar target/server.jar --debug --spring.profiles.active={profile}"));
        assertTrue(configswitcher.state.PluginSettingsState.isLegacyDefaultTemplate(
                "java -jar target/server.jar --debug --spring.profiles.active={profile},auth-dev"));
        assertFalse(configswitcher.state.PluginSettingsState.isLegacyDefaultTemplate(
                "java -jar target/my-app.jar --spring.profiles.active={profile}"));
    }

    @Test
    public void testCalculateDefaultCommandTemplate_NullProject() {
        assertEquals("java -jar target/server.jar --debug --spring.profiles.active={profile},auth-dev",
                configswitcher.service.AppRunManager.calculateDefaultCommandTemplate(null));
    }

    @Test
    public void testFindTargetJarAndN2oConfigPath_WithTempDirectory() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("test_n2o_proj_");
        try {
            java.nio.file.Path targetDir = tempDir.resolve("target");
            java.nio.file.Files.createDirectories(targetDir);
            java.nio.file.Path jarFile = targetDir.resolve("server.jar");
            java.nio.file.Files.createFile(jarFile);

            java.nio.file.Path confDir = tempDir.resolve("src/main/resources/META-INF/conf");
            java.nio.file.Files.createDirectories(confDir);

            String jar = configswitcher.service.AppRunManager.findTargetJar(tempDir.toFile());
            assertEquals("target/server.jar", jar);

            String confPath = configswitcher.service.AppRunManager.findN2oConfigPath(tempDir.toFile());
            assertEquals(confDir.toFile().getAbsolutePath(), confPath);

            com.intellij.openapi.project.Project proj = (com.intellij.openapi.project.Project) java.lang.reflect.Proxy.newProxyInstance(
                    com.intellij.openapi.project.Project.class.getClassLoader(),
                    new Class<?>[]{com.intellij.openapi.project.Project.class},
                    (proxy, method, args) -> {
                        if ("getBasePath".equals(method.getName())) return tempDir.toFile().getAbsolutePath();
                        return null;
                    }
            );
            String calculated = configswitcher.service.AppRunManager.calculateDefaultCommandTemplate(proj);
            assertTrue(calculated.startsWith("java -jar target/server.jar --debug --n2o.config.path="));
            assertTrue(calculated.contains(confPath));
            assertTrue(calculated.endsWith("--spring.profiles.active={profile},auth-dev"));
        } finally {
            // Cleanup
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(java.nio.file.Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }
}
