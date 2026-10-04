package configswitcher.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class GitPatchEntry {
    public boolean enabled = true;
    public String patchPath = "";
    public String description = "";
    public String applicableProfile = "";

    public GitPatchEntry() {
    }

    public GitPatchEntry(boolean enabled, @NotNull String patchPath, @NotNull String description, @Nullable String applicableProfile) {
        this.enabled = enabled;
        this.patchPath = patchPath;
        this.description = description;
        this.applicableProfile = applicableProfile != null ? applicableProfile : "";
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
    public String getApplicableProfile() {
        return applicableProfile;
    }

    public void setApplicableProfile(@Nullable String applicableProfile) {
        this.applicableProfile = applicableProfile != null ? applicableProfile : "";
    }

    public boolean matchesProfile(@NotNull String currentProfile) {
        if (!enabled) return false;
        if (applicableProfile == null || applicableProfile.isBlank()) return true;
        String[] targets = applicableProfile.toLowerCase().split(",");
        String[] actives = currentProfile.toLowerCase().split(",");
        for (String t : targets) {
            String trimmedTarget = t.trim();
            if (trimmedTarget.isEmpty()) continue;
            for (String a : actives) {
                if (trimmedTarget.equalsIgnoreCase(a.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GitPatchEntry that = (GitPatchEntry) o;
        return enabled == that.enabled &&
                Objects.equals(patchPath, that.patchPath) &&
                Objects.equals(description, that.description) &&
                Objects.equals(applicableProfile, that.applicableProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, patchPath, description, applicableProfile);
    }

    @Override
    public String toString() {
        String profileInfo = applicableProfile.isBlank() ? "all" : applicableProfile;
        return (enabled ? "[v] " : "[ ] ") + patchPath + " (" + profileInfo + ")";
    }
}
