package configswitcher.model;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class ConfigFileModel {
    private final VirtualFile virtualFile;
    private final String fileName;
    private final String profile;
    private final boolean isDefault;
    private final String relativePath;
    private final String moduleName;

    public ConfigFileModel(
            @Nullable VirtualFile virtualFile,
            @NotNull String fileName,
            @NotNull String profile,
            boolean isDefault,
            @NotNull String relativePath,
            @Nullable String moduleName
    ) {
        this.virtualFile = virtualFile;
        this.fileName = fileName;
        this.profile = profile;
        this.isDefault = isDefault;
        this.relativePath = relativePath;
        this.moduleName = moduleName;
    }

    public ConfigFileModel(
            @Nullable VirtualFile virtualFile,
            @NotNull String profile,
            boolean isDefault,
            @NotNull String relativePath,
            @Nullable String moduleName
    ) {
        this(virtualFile, virtualFile != null ? virtualFile.getName() : "", profile, isDefault, relativePath, moduleName);
    }

    @Nullable
    public VirtualFile getVirtualFile() {
        return virtualFile;
    }

    @NotNull
    public String getFileName() {
        return fileName;
    }

    @NotNull
    public String getProfile() {
        return profile;
    }

    public boolean isDefault() {
        return isDefault;
    }

    @NotNull
    public String getRelativePath() {
        return relativePath;
    }

    @Nullable
    public String getModuleName() {
        return moduleName;
    }

    @NotNull
    public String getDisplayName() {
        return isDefault ? "default (" + fileName + ")" : profile + " (" + fileName + ")";
    }

    @NotNull
    public String getFullPath() {
        return virtualFile != null ? virtualFile.getPath() : relativePath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigFileModel that = (ConfigFileModel) o;
        return isDefault == that.isDefault &&
                Objects.equals(fileName, that.fileName) &&
                Objects.equals(profile, that.profile) &&
                Objects.equals(relativePath, that.relativePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, profile, isDefault, relativePath);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
