package configswitcher.service;

import com.intellij.openapi.project.Project;
import configswitcher.model.GitPatchEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitPatchServiceTest {

    @Test
    void appliesOnlyTheSelectedPatchAndPreservesConflictingEdits(@TempDir Path directory) throws Exception {
        Process init = new ProcessBuilder("git", "init", "--quiet")
                .directory(directory.toFile()).inheritIO().start();
        assertEquals(0, init.waitFor());
        Path target = Files.writeString(directory.resolve("target.txt"), "before\n");
        Files.writeString(directory.resolve("selected.patch"), """
                diff --git a/target.txt b/target.txt
                --- a/target.txt
                +++ b/target.txt
                @@ -1 +1 @@
                -before
                +after
                """);
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> directory.toString();
                    case "getName" -> "GitPatchServiceTest";
                    case "isDisposed", "isDefault" -> false;
                    default -> null;
                });
        GitPatchService service = new GitPatchService(project);
        GitPatchEntry entry = new GitPatchEntry(false, "selected.patch", "", "other-profile");

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
}
