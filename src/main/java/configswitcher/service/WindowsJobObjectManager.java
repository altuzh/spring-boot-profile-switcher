package configswitcher.service;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.SystemInfo;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import configswitcher.util.ConfigSwitcherLog;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Manages a Windows Job Object configured with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}.
 * <p>
 * When child processes (such as {@code java.exe} executing {@code target/server.jar}) are assigned
 * to this Job Object, the Windows kernel guarantees that when IntelliJ IDEA terminates for ANY reason
 * (normal exit, JVM crash, Task Manager kill, or {@code taskkill /F}), all processes inside the Job Object
 * are immediately and forcibly terminated by the OS kernel. This prevents hanging Java processes
 * from holding file locks on application jars.
 */
public final class WindowsJobObjectManager {

    public interface Kernel32Job extends StdCallLibrary {
        Kernel32Job INSTANCE = Native.load("kernel32", Kernel32Job.class, W32APIOptions.DEFAULT_OPTIONS);

        HANDLE CreateJobObject(Pointer lpJobAttributes, String lpName);
        boolean SetInformationJobObject(HANDLE hJob, int JobObjectInformationClass, Pointer lpJobObjectInformation, int cbJobObjectInformationLength);
        boolean AssignProcessToJobObject(HANDLE hJob, HANDLE hProcess);
        HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);
        boolean TerminateJobObject(HANDLE hJob, int uExitCode);
        boolean CloseHandle(HANDLE hObject);
        int GetLastError();
    }

    private static volatile HANDLE jobHandle = null;
    private static volatile boolean initialized = false;
    private static final Object LOCK = new Object();

    // Win32 constants
    // Limit flag: JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    // JobObjectInfoClass: JobObjectExtendedLimitInformation = 9
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    // Process access rights: PROCESS_SET_QUOTA (0x0100) | PROCESS_TERMINATE (0x0001)
    private static final int PROCESS_SET_QUOTA_AND_TERMINATE = 0x0101;
    private static final int PROCESS_ALL_ACCESS = 0x1F0FFF;

    private WindowsJobObjectManager() {}

    /**
     * Ensures the Windows Job Object is initialized with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}.
     */
    public static void ensureInitialized() {
        if (!SystemInfo.isWindows) {
            return;
        }
        if (initialized && jobHandle != null) {
            return;
        }

        synchronized (LOCK) {
            if (initialized && jobHandle != null) {
                return;
            }

            try {
                if (System.getProperty("jna.boot.library.path") == null) {
                    try {
                        String libPath = PathManager.getLibPath();
                        File jnaAmd64 = new File(libPath, "jna/amd64");
                        if (jnaAmd64.exists()) {
                            System.setProperty("jna.boot.library.path", jnaAmd64.getAbsolutePath());
                        }
                    } catch (Throwable ignored) {}
                }

                Kernel32Job k32 = Kernel32Job.INSTANCE;
                HANDLE hJob = k32.CreateJobObject(null, null);
                if (hJob == null || Pointer.nativeValue(hJob.getPointer()) == 0) {
                    ConfigSwitcherLog.warn("CreateJobObject failed with error code: " + k32.GetLastError());
                    return;
                }

                // Sizing for JOBOBJECT_EXTENDED_LIMIT_INFORMATION: 144 bytes on 64-bit, 112 bytes on 32-bit
                int size = Native.POINTER_SIZE == 8 ? 144 : 112;
                Memory mem = new Memory(size);
                mem.clear();
                // BasicLimitInformation.LimitFlags is located at byte offset 16 (0x10)
                mem.setInt(16, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);

                boolean setOk = k32.SetInformationJobObject(hJob, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, mem, size);
                if (!setOk) {
                    ConfigSwitcherLog.warn("SetInformationJobObject failed with error code: " + k32.GetLastError());
                    k32.CloseHandle(hJob);
                    return;
                }

                jobHandle = hJob;
                initialized = true;
                ConfigSwitcherLog.info("Windows Job Object successfully initialized with KILL_ON_JOB_CLOSE limit.");

                // Register JVM shutdown hook as an additional safety net for graceful IDEA shutdown
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    terminateAllInJob();
                }, "ConfigSwitcher-WindowsJobObject-ShutdownHook"));

            } catch (Throwable t) {
                ConfigSwitcherLog.warn("Failed to initialize Windows Job Object: " + t.getMessage());
            }
        }
    }

    /**
     * Assigns a running OS process to the Windows Job Object.
     * When IDEA exits or is forcibly terminated, Windows automatically kills this process.
     */
    public static boolean assignProcess(long pid) {
        if (!SystemInfo.isWindows || pid <= 0) {
            return false;
        }

        ensureInitialized();
        HANDLE hJob = jobHandle;
        if (hJob == null) {
            return false;
        }

        try {
            Kernel32Job k32 = Kernel32Job.INSTANCE;
            HANDLE hProc = k32.OpenProcess(PROCESS_SET_QUOTA_AND_TERMINATE, false, (int) pid);
            if (hProc == null || Pointer.nativeValue(hProc.getPointer()) == 0) {
                hProc = k32.OpenProcess(PROCESS_ALL_ACCESS, false, (int) pid);
            }

            if (hProc == null || Pointer.nativeValue(hProc.getPointer()) == 0) {
                ConfigSwitcherLog.warn("OpenProcess failed for PID " + pid + ", error code: " + k32.GetLastError());
                return false;
            }

            try {
                boolean assignOk = k32.AssignProcessToJobObject(hJob, hProc);
                if (assignOk) {
                    ConfigSwitcherLog.info("Assigned process PID " + pid + " to Windows Job Object (KILL_ON_JOB_CLOSE).");
                    return true;
                } else {
                    int err = k32.GetLastError();
                    ConfigSwitcherLog.warn("AssignProcessToJobObject failed for PID " + pid + ", error code: " + err);
                    return false;
                }
            } finally {
                k32.CloseHandle(hProc);
            }
        } catch (Throwable t) {
            ConfigSwitcherLog.warn("Failed to assign PID " + pid + " to Windows Job Object: " + t.getMessage());
            return false;
        }
    }

    /**
     * Assigns a java.lang.Process to the Windows Job Object.
     */
    public static boolean assignProcess(@Nullable Process process) {
        if (process != null && process.isAlive()) {
            return assignProcess(process.pid());
        }
        return false;
    }

    /**
     * Forcibly terminates all processes assigned to the Windows Job Object.
     */
    public static void terminateAllInJob() {
        if (!SystemInfo.isWindows) return;
        HANDLE hJob = jobHandle;
        if (hJob != null) {
            try {
                Kernel32Job k32 = Kernel32Job.INSTANCE;
                ConfigSwitcherLog.info("Terminating all processes in Windows Job Object...");
                k32.TerminateJobObject(hJob, 1);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Closes the Windows Job Object handle, which automatically triggers the Windows kernel
     * to terminate all processes in the job (due to KILL_ON_JOB_CLOSE).
     */
    public static void closeJobObject() {
        if (!SystemInfo.isWindows) return;
        synchronized (LOCK) {
            HANDLE hJob = jobHandle;
            if (hJob != null) {
                try {
                    Kernel32Job.INSTANCE.CloseHandle(hJob);
                } catch (Throwable ignored) {}
                jobHandle = null;
                initialized = false;
            }
        }
    }

    public static boolean isInitialized() {
        return initialized && jobHandle != null;
    }
}
