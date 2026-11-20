package configswitcher.service;

import com.intellij.openapi.project.Project;
import configswitcher.model.GitPatchEntry;
import configswitcher.state.PluginSettingsState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitPatchServiceTest {

    @Test
    void appliesOnlyTheSelectedPatchAndPreservesConflictingEdits(@TempDir Path directory) throws Exception {
        git(directory, "init", "--quiet");
        Path target = writeTestPatch(directory);
        GitPatchService service = createService(directory, new PluginSettingsState());
        GitPatchEntry entry = new GitPatchEntry(false, "selected.patch", "", "");

        assertTrue(service.applySelectedPatch(entry));
        assertEquals("after", Files.readString(target).stripTrailing());
        assertTrue(service.applySelectedPatch(new GitPatchEntry(true, "./selected.patch", "", "")));
        assertEquals(List.of(entry), service.getAppliedPatches());
        assertEquals(1, service.revertAppliedPatches());
        assertEquals("before", Files.readString(target).stripTrailing());

        Files.writeString(target, "local change\n");
        assertFalse(service.applySelectedPatch(entry));
        assertEquals("local change\n", Files.readString(target));
        assertTrue(service.getAppliedPatches().isEmpty());
    }

    @Test
    void manualApplyRechecksWorkingFilesAfterExternalRevert(@TempDir Path directory) throws Exception {
        git(directory, "init", "--quiet", "--initial-branch=deploy/uat");
        Path target = writeTestPatch(directory);
        GitPatchService service = createService(directory, new PluginSettingsState());
        for (String branch : List.of("", "refs/heads/deploy/uat", "refs/heads/other")) {
            Files.writeString(target, "before\n");
            GitPatchEntry selected = new GitPatchEntry(false, "selected.patch", "", branch);
            assertTrue(service.applySelectedPatch(selected));
            assertEquals("after", Files.readString(target).stripTrailing());
            assertEquals(List.of(selected), service.getAppliedPatches());
        }
        assertEquals(1, service.revertAppliedPatches());
    }

    @Test
    void filtersPreRunPatchesByBranchButAllowsManualApply(@TempDir Path directory) throws Exception {
        git(directory, "init", "--quiet", "--initial-branch=sibling");
        Path target = writeTestPatch(directory);
        git(directory, "add", "target.txt");
        git(directory, "commit", "--quiet", "-m", "Initial commit");
        git(directory, "switch", "--quiet", "-c", "parent");
        git(directory, "commit", "--quiet", "--allow-empty", "-m", "Parent commit");
        git(directory, "update-ref", "refs/remotes/origin/parent", "refs/heads/parent");
        git(directory, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/parent");

        PluginSettingsState settings = new PluginSettingsState();
        settings.getState().revertPatchFilesBeforeApply = false;
        GitPatchEntry entry = new GitPatchEntry(true, "selected.patch", "", "refs/heads/parent");
        settings.getState().gitPatches.add(entry);
        settings.getState().gitPatches.add(new GitPatchEntry(false, "disabled.patch", "", ""));
        GitPatchService service = createService(directory, settings);
        assertEquals(List.of("refs/heads/parent", "refs/heads/sibling", "refs/remotes/origin/parent"),
                service.listBranchRefs());

        assertTrue(service.applyConfiguredPatches());
        assertEquals(List.of(entry), service.getAppliedPatches());
        assertEquals(1, service.revertAppliedPatches());

        git(directory, "switch", "--quiet", "-c", "child");
        git(directory, "commit", "--quiet", "--allow-empty", "-m", "Child commit");
        assertTrue(service.applyConfiguredPatches());
        assertEquals(List.of(entry), service.getAppliedPatches());
        assertEquals(1, service.revertAppliedPatches());
        entry.setParentBranch("refs/remotes/origin/parent");
        assertTrue(service.applyConfiguredPatches());
        assertEquals(1, service.revertAppliedPatches());

        git(directory, "switch", "--quiet", "sibling");
        assertTrue(service.applyConfiguredPatches());
        assertEquals("before", Files.readString(target).stripTrailing());
        assertTrue(service.getAppliedPatches().isEmpty());

        assertTrue(service.applySelectedPatch(entry));
        assertEquals("after", Files.readString(target).stripTrailing());
        assertEquals(1, service.revertAppliedPatches());
    }

    private Path writeTestPatch(Path directory) throws Exception {
        Path target = Files.writeString(directory.resolve("target.txt"), "before\n");
        Files.writeString(directory.resolve("selected.patch"), """
                diff --git a/target.txt b/target.txt
                --- a/target.txt
                +++ b/target.txt
                @@ -1 +1 @@
                -before
                +after
                """);
        return target;
    }

    private GitPatchService createService(Path directory, PluginSettingsState settings) {
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> directory.toString();
                    case "getName" -> "GitPatchServiceTest";
                    case "isDisposed", "isDefault" -> false;
                    case "getService" -> args[0] == PluginSettingsState.class ? settings : null;
                    default -> null;
                });
        return new GitPatchService(project);
    }

    private void git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Patch Test",
                "-c", "user.email=patch-test@example.test", "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false"));
        command.addAll(List.of(args));
        Process git = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
        assertEquals(0, git.waitFor(), command.toString());
    }
}
