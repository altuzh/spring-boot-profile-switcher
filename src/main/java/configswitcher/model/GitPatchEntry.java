package configswitcher.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class GitPatchEntry {
    public boolean enabled = true;
    public String patchPath = "";
    public String description = "";
    public String parentBranch = "";

    public GitPatchEntry() {
    }

    public GitPatchEntry(boolean enabled, @NotNull String patchPath, @NotNull String description, @Nullable String parentBranch) {
        this.enabled = enabled;
        this.patchPath = patchPath;
        this.description = description;
        setParentBranch(parentBranch);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @NotNull
    public String getPatchPath() {
        return patchPath;
    }

    public void setPatchPath(@NotNull String patchPath) {
        this.patchPath = patchPath;
    }

    @NotNull
    public String getDescription() {
        return description;
    }

    public void setDescription(@NotNull String description) {
        this.description = description;
    }

    @NotNull
    public String getParentBranch() {
        return parentBranch != null ? parentBranch : "";
    }

    public void setParentBranch(@Nullable String parentBranch) {
        this.parentBranch = parentBranch != null ? parentBranch : "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GitPatchEntry that = (GitPatchEntry) o;
        return enabled == that.enabled &&
                Objects.equals(patchPath, that.patchPath) &&
                Objects.equals(description, that.description) &&
                getParentBranch().equals(that.getParentBranch());
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, patchPath, description, getParentBranch());
    }

    @Override
    public String toString() {
        String branchInfo = getParentBranch().isBlank() ? "all" : getParentBranch();
        return (enabled ? "[v] " : "[ ] ") + patchPath + " (" + branchInfo + ")";
    }
}
