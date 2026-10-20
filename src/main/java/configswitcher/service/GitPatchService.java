package configswitcher.service;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import configswitcher.model.GitPatchEntry;
import configswitcher.state.PluginSettingsState;
import configswitcher.util.ConfigNotifier;
import configswitcher.util.ConfigSwitcherLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service(Service.Level.PROJECT)
public final class GitPatchService implements Disposable {

    private final Project project;
    private final CopyOnWriteArrayList<GitPatchEntry> appliedPatchesJournal = new CopyOnWriteArrayList<>();

    public GitPatchService(@NotNull Project project) {
        this.project = project;
    }

    public static GitPatchService getInstance(@NotNull Project project) {
        return project.getService(GitPatchService.class);
    }

    @Nullable
    public static String getClipboardText() {
        try {
            CopyPasteManager copyPasteManager = CopyPasteManager.getInstance();
            if (copyPasteManager.areDataFlavorsAvailable(DataFlavor.stringFlavor)) {
                return (String) copyPasteManager.getContents(DataFlavor.stringFlavor);
            }
        } catch (Throwable ignored) {}

        try {
            java.awt.datatransfer.Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return (String) clipboard.getData(DataFlavor.stringFlavor);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    public static boolean isLikelyPatchText(@Nullable String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        return trimmed.contains("diff --git ") ||
                trimmed.contains("--- a/") ||
                trimmed.contains("+++ b/") ||
                trimmed.contains("Index: ") ||
                (trimmed.startsWith("--- ") && trimmed.contains("+++ "));
    }

    @NotNull
    public File resolvePatchFile(@NotNull String patchPath) {
        String trimmed = patchPath.trim();
        File file = new File(trimmed);
        if (file.isAbsolute()) {
            return file;
        }
        String basePath = project.getBasePath();
        if (basePath != null) {
            return new File(basePath, trimmed);
        }
        return file;
    }

    @NotNull
    public File resolveProjectFile(@NotNull String relPath) {
        File file = new File(relPath);
        if (file.isAbsolute()) {
            return file;
        }
        String basePath = project.getBasePath();
        if (basePath != null) {
            return new File(basePath, relPath);
        }
        return file;
    }

    private static final Pattern DIFF_GIT_PATTERN = Pattern.compile(
            "^diff --git\\s+(?:\"a/(.+?)\"|a/(\\S+))\\s+(?:\"b/(.+?)\"|b/(\\S+))$"
    );

    @NotNull
    public static String cleanPatchPath(@Nullable String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
            p = p.substring(1, p.length() - 1);
        }
        return p.replace('\\', '/');
    }

    @NotNull
    public static List<String> parsePatchTextForAffectedFiles(@Nullable String patchContent) {
        if (patchContent == null || patchContent.isBlank()) {
            return Collections.emptyList();
        }
        Set<String> files = new LinkedHashSet<>();
        for (String line : patchContent.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Index: ")) {
                String path = cleanPatchPath(trimmed.substring("Index: ".length()));
                if (!path.isEmpty()) {
                    files.add(path);
                }
            } else if (trimmed.startsWith("diff --git ")) {
                Matcher m = DIFF_GIT_PATTERN.matcher(trimmed);
                if (m.find()) {
                    String bPath = m.group(3) != null ? m.group(3) : m.group(4);
                    if (bPath != null && !bPath.equals("/dev/null") && !bPath.equals("dev/null")) {
                        files.add(cleanPatchPath(bPath));
                    } else {
                        String aPath = m.group(1) != null ? m.group(1) : m.group(2);
                        if (aPath != null && !aPath.equals("/dev/null") && !aPath.equals("dev/null")) {
                            files.add(cleanPatchPath(aPath));
                        }
                    }
                } else {
                    String[] tokens = trimmed.split("\\s+");
                    if (tokens.length >= 4) {
                        String bTok = cleanPatchPath(tokens[3]);
                        if (bTok.startsWith("b/")) bTok = bTok.substring(2);
                        if (!bTok.isEmpty() && !bTok.equals("/dev/null") && !bTok.equals("dev/null")) {
                            files.add(bTok);
                        }
                    }
                }
            } else if (trimmed.startsWith("+++ ")) {
                String path = trimmed.substring(4).trim();
                int tabIdx = path.indexOf('\t');
                if (tabIdx >= 0) path = path.substring(0, tabIdx).trim();
                path = cleanPatchPath(path);
                if (path.startsWith("b/")) path = path.substring(2);
                if (!path.isEmpty() && !path.equals("/dev/null") && !path.equals("dev/null")) {
                    files.add(path);
                }
            } else if (trimmed.startsWith("--- ")) {
                String path = trimmed.substring(4).trim();
                int tabIdx = path.indexOf('\t');
                if (tabIdx >= 0) path = path.substring(0, tabIdx).trim();
                path = cleanPatchPath(path);
                if (path.startsWith("a/")) path = path.substring(2);
                if (!path.isEmpty() && !path.equals("/dev/null") && !path.equals("dev/null")) {
                    files.add(path);
                }
            }
        }
        return new ArrayList<>(files);
    }

    @NotNull
    public List<String> getPatchAffectedFiles(@NotNull File patchFile) {
        Set<String> files = new LinkedHashSet<>();

        // 1. Try git apply --numstat (authoritative diff parsing via git)
        if (patchFile.exists()) {
            ProcessOutput output = runGitCommand("apply", "--numstat", patchFile.getAbsolutePath());
            if (output.getExitCode() == 0 && !output.getStdout().isBlank()) {
                for (String line : output.getStdout().lines().toList()) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    String[] parts = trimmed.split("\t");
                    if (parts.length >= 3) {
                        String rawPath = parts[2].trim();
                        rawPath = cleanPatchPath(rawPath);
                        if (rawPath.contains(" => ")) {
                            String[] renameParts = rawPath.split(" => ");
                            for (String rp : renameParts) {
                                String cleaned = cleanPatchPath(rp.replaceAll("[{}]", ""));
                                if (!cleaned.isEmpty()) {
                                    files.add(cleaned);
                                }
                            }
                        } else if (!rawPath.isEmpty()) {
                            files.add(rawPath);
                        }
                    }
                }
            }
        }

        // 2. Fallback to direct patch text parsing
        if (files.isEmpty() && patchFile.exists()) {
            try {
                String content = Files.readString(patchFile.toPath(), StandardCharsets.UTF_8);
                files.addAll(parsePatchTextForAffectedFiles(content));
            } catch (Exception e) {
                ConfigSwitcherLog.warn(project, "GitPatchService: Failed reading patch file for affected files: " + e.getMessage());
            }
        }

        return new ArrayList<>(files);
    }

    public synchronized void revertPatchAffectedFiles(@NotNull File patchFile) {
        List<String> affectedFiles = getPatchAffectedFiles(patchFile);
        if (affectedFiles.isEmpty()) {
            return;
        }

        ConfigSwitcherLog.info(project, "GitPatchService: Reverting changes in " + affectedFiles.size() +
                " file(s) affected by patch " + patchFile.getName() + " before applying: " + affectedFiles);

        for (String relPath : affectedFiles) {
            // Discard working tree and staged index changes for tracked file
            ProcessOutput checkoutOutput = runGitCommand("checkout", "HEAD", "--", relPath);
            if (checkoutOutput.getExitCode() == 0) {
                ConfigSwitcherLog.info(project, "GitPatchService: Reverted to HEAD: " + relPath);
            } else {
                // If git checkout HEAD failed, file might not be in HEAD (e.g. untracked file leftover from an un-reverted patch)
                File fileOnDisk = resolveProjectFile(relPath);
                if (fileOnDisk.exists()) {
                    ConfigSwitcherLog.info(project, "GitPatchService: Cleaning untracked patch file: " + relPath);
                    runGitCommand("clean", "-f", "--", relPath);
                    if (fileOnDisk.exists()) {
                        try {
                            Files.deleteIfExists(fileOnDisk.toPath());
                        } catch (Exception e) {
                            ConfigSwitcherLog.warn(project, "GitPatchService: Could not delete untracked file " + relPath + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        refreshProjectVfs();
    }

    @NotNull
    public File getPatchStorageDirectory() {
        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        String storageDirName = settings.patchStorageDir;
        if (storageDirName == null || storageDirName.isBlank()) {
            storageDirName = ".idea/patches";
        }

        File dir = new File(storageDirName);
        if (!dir.isAbsolute()) {
            String basePath = project.getBasePath();
            dir = basePath != null ? new File(basePath, storageDirName) : new File(storageDirName);
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    @NotNull
    public GitPatchEntry createPatchFromClipboard(
            @NotNull String patchName,
            @Nullable String profile,
            @Nullable String description
    ) throws Exception {
        String clipboardText = getClipboardText();
        if (clipboardText == null || clipboardText.isBlank()) {
            throw new IllegalArgumentException("Clipboard is empty or does not contain text.");
        }

        String fileName = patchName.trim();
        if (!fileName.endsWith(".patch") && !fileName.endsWith(".diff")) {
            fileName += ".patch";
        }

        File storageDir = getPatchStorageDirectory();
        File targetFile = new File(storageDir, fileName);

        Files.writeString(targetFile.toPath(), clipboardText, StandardCharsets.UTF_8);
        ConfigSwitcherLog.info(project, "GitPatchService: Saved clipboard patch to " + targetFile.getAbsolutePath());

        // Refresh VFS
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetFile);
        if (vf != null) {
            vf.refresh(false, false);
        }

        // Relative path calculation for portable project storage
        String relativePath;
        String basePath = project.getBasePath();
        if (basePath != null && targetFile.getAbsolutePath().startsWith(new File(basePath).getAbsolutePath())) {
            relativePath = new File(basePath).toPath().relativize(targetFile.toPath()).toString().replace('\\', '/');
        } else {
            relativePath = targetFile.getAbsolutePath();
        }

        GitPatchEntry entry = new GitPatchEntry(
                true,
                relativePath,
                description != null ? description : "",
                profile != null ? profile : ""
        );

        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        settings.gitPatches.removeIf(p -> p.getPatchPath().equalsIgnoreCase(entry.getPatchPath()));
        settings.gitPatches.add(entry);

        return entry;
    }

    public synchronized boolean applyConfiguredPatches(@NotNull String activeProfile) {
        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        List<GitPatchEntry> toApply = new ArrayList<>();
        for (GitPatchEntry entry : settings.gitPatches) {
            if (entry.isEnabled()) {
                toApply.add(entry);
            }
        }

        if (toApply.isEmpty()) {
            return true;
        }

        ConfigSwitcherLog.info(project, "GitPatchService: Applying " + toApply.size() + " patch(es) for active profile '" + activeProfile + "'");

        // Before applying patch, changes in files in patch should be reverted
        if (settings.revertPatchFilesBeforeApply) {
            for (GitPatchEntry entry : toApply) {
                File patchFile = resolvePatchFile(entry.getPatchPath());
                if (patchFile.exists()) {
                    revertPatchAffectedFiles(patchFile);
                }
            }
        }

        List<GitPatchEntry> appliedInThisBatch = new ArrayList<>();

        for (GitPatchEntry entry : toApply) {
            File patchFile = resolvePatchFile(entry.getPatchPath());
            if (!patchFile.exists()) {
                ConfigNotifier.notifyError(project, "Patch file not found: " + patchFile.getAbsolutePath());
                revertBatch(appliedInThisBatch);
                return false;
            }

            // 1. Dry run check
            ProcessOutput checkOutput = runGitCommand("apply", "--check", "--whitespace=nowarn", patchFile.getAbsolutePath());
            if (checkOutput.getExitCode() != 0) {
                if (settings.revertPatchFilesBeforeApply) {
                    revertPatchAffectedFiles(patchFile);
                    checkOutput = runGitCommand("apply", "--check", "--whitespace=nowarn", patchFile.getAbsolutePath());
                }
            }
            if (checkOutput.getExitCode() != 0) {
                String errMsg = checkOutput.getStderr().isBlank() ? checkOutput.getStdout() : checkOutput.getStderr();
                ConfigNotifier.notifyError(project, "Cannot apply patch '" + entry.getPatchPath() + "': " + errMsg.trim());
                revertBatch(appliedInThisBatch);
                return false;
            }

            // 2. Apply patch
            ProcessOutput applyOutput = runGitCommand("apply", "--whitespace=nowarn", patchFile.getAbsolutePath());
            if (applyOutput.getExitCode() != 0) {
                String errMsg = applyOutput.getStderr().isBlank() ? applyOutput.getStdout() : applyOutput.getStderr();
                ConfigNotifier.notifyError(project, "Failed applying patch '" + entry.getPatchPath() + "': " + errMsg.trim());
                revertBatch(appliedInThisBatch);
                return false;
            }

            appliedInThisBatch.add(entry);
            appliedPatchesJournal.add(entry);
            ConfigSwitcherLog.info(project, "GitPatchService: Successfully applied patch " + entry.getPatchPath());
        }

        refreshProjectVfs();
        ConfigNotifier.notifyInfo(project, "Applied " + appliedInThisBatch.size() + " git patch(es) for active profile '" + activeProfile + "'.");
        return true;
    }

    public synchronized int revertAppliedPatches() {
        if (appliedPatchesJournal.isEmpty()) {
            return 0;
        }

        ConfigSwitcherLog.info(project, "GitPatchService: Reverting " + appliedPatchesJournal.size() + " applied patch(es) in reverse order.");
        List<GitPatchEntry> toRevert = new ArrayList<>(appliedPatchesJournal);
        Collections.reverse(toRevert);

        int revertedCount = 0;
        for (GitPatchEntry entry : toRevert) {
            File patchFile = resolvePatchFile(entry.getPatchPath());
            if (patchFile.exists()) {
                ProcessOutput output = runGitCommand("apply", "-R", "--whitespace=nowarn", patchFile.getAbsolutePath());
                if (output.getExitCode() == 0) {
                    revertedCount++;
                    ConfigSwitcherLog.info(project, "GitPatchService: Successfully reverted patch " + entry.getPatchPath());
                } else {
                    ConfigSwitcherLog.warn(project, "GitPatchService: Warning reverting patch " + entry.getPatchPath() + ": " + output.getStderr());
                }
            }
            appliedPatchesJournal.remove(entry);
        }

        refreshProjectVfs();
        if (revertedCount > 0) {
            ConfigNotifier.notifyInfo(project, "Reverted " + revertedCount + " git patch(es). Working tree restored.");
        }
        return revertedCount;
    }

    private void revertBatch(@NotNull List<GitPatchEntry> batch) {
        Collections.reverse(batch);
        for (GitPatchEntry entry : batch) {
            File patchFile = resolvePatchFile(entry.getPatchPath());
            if (patchFile.exists()) {
                runGitCommand("apply", "-R", "--whitespace=nowarn", patchFile.getAbsolutePath());
            }
            appliedPatchesJournal.remove(entry);
        }
        refreshProjectVfs();
    }

    public boolean hasActivePatches() {
        return !appliedPatchesJournal.isEmpty();
    }

    public List<GitPatchEntry> getAppliedPatches() {
        return new ArrayList<>(appliedPatchesJournal);
    }

    private ProcessOutput runGitCommand(String... args) {
        GeneralCommandLine cmd = new GeneralCommandLine("git");
        String basePath = project.getBasePath();
        if (basePath != null) {
            cmd.setWorkDirectory(new File(basePath));
        }
        cmd.addParameters(args);
        try {
            return ExecUtil.execAndGetOutput(cmd, 10_000);
        } catch (Exception e) {
            ConfigSwitcherLog.error(project, "Git execution failed: " + e.getMessage(), e);
            ProcessOutput output = new ProcessOutput(1);
            output.appendStderr("Execution exception: " + e.getMessage());
            return output;
        }
    }

    private void refreshProjectVfs() {
        String basePath = project.getBasePath();
        if (basePath != null) {
            VirtualFile projectDir = LocalFileSystem.getInstance().findFileByPath(basePath);
            if (projectDir != null) {
                VfsUtil.markDirtyAndRefresh(true, true, true, projectDir);
            }
        }
    }

    @Override
    public void dispose() {
        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        if (settings.autoRevertPatches && hasActivePatches()) {
            revertAppliedPatches();
        }
    }
}
