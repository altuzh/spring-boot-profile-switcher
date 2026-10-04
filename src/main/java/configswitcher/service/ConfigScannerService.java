package configswitcher.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.Topic;
import configswitcher.model.ConfigFileModel;
import configswitcher.state.PluginSettingsState;
import configswitcher.util.ConfigSwitcherLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service(Service.Level.PROJECT)
public final class ConfigScannerService implements Disposable {

    @FunctionalInterface
    public interface ConfigChangeListener {
        void onConfigsChanged(List<ConfigFileModel> configs);
    }

    public static final Topic<ConfigChangeListener> TOPIC =
            Topic.create("ConfigChangeListener", ConfigChangeListener.class);

    private static final Pattern APPLICATION_CONFIG_PATTERN =
            Pattern.compile("^application(?:-(?<profile>[a-zA-Z0-9_.-]+))?\\.(?:ya?ml)$", Pattern.CASE_INSENSITIVE);

    private final Project project;
    private final CopyOnWriteArrayList<ConfigFileModel> cachedConfigs = new CopyOnWriteArrayList<>();

    public ConfigScannerService(@NotNull Project project) {
        this.project = project;

        // Subscribe to VFS changes to update configurations list dynamically
        MessageBusConnection connection = project.getMessageBus().connect(this);
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                boolean hasConfigChanges = false;
                for (VFileEvent event : events) {
                    VirtualFile file = event.getFile();
                    if (file != null && isConfigFile(file.getName())) {
                        hasConfigChanges = true;
                        break;
                    }
                }
                if (hasConfigChanges) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!project.isDisposed()) {
                            refreshConfigurations();
                        }
                    });
                }
            }
        });

        // Initial scan
        refreshConfigurations();
    }

    public static ConfigScannerService getInstance(@NotNull Project project) {
        return project.getService(ConfigScannerService.class);
    }

    @Nullable
    public static String parseProfileName(@NotNull String fileName) {
        Matcher matcher = APPLICATION_CONFIG_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            String profile = matcher.group("profile");
            return profile != null ? profile : "default";
        }
        return null;
    }

    public static boolean isConfigFile(@NotNull String fileName) {
        return APPLICATION_CONFIG_PATTERN.matcher(fileName).matches();
    }

    @NotNull
    public List<ConfigFileModel> getConfigurations() {
        if (cachedConfigs.isEmpty()) {
            refreshConfigurations();
        }
        return new ArrayList<>(cachedConfigs);
    }

    @NotNull
    public synchronized List<ConfigFileModel> refreshConfigurations() {
        ConfigSwitcherLog.info(project, "ConfigScanner: Refreshing configurations for project " + project.getName());
        List<ConfigFileModel> result = new ArrayList<>();
        Set<String> visitedPaths = new HashSet<>();

        // 1. Scan standard resources directory across all project modules
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            VirtualFile[] sourceRoots = rootManager.getSourceRoots();
            for (VirtualFile sourceRoot : sourceRoots) {
                scanDirectoryRecursively(sourceRoot, module.getName(), result, visitedPaths);
            }
        }

        // 2. Scan project content roots (fallback/additional discovery for src/main/resources)
        VirtualFile[] projectRoots = ProjectRootManager.getInstance(project).getContentRoots();
        for (VirtualFile root : projectRoots) {
            VirtualFile resourcesDir = root.findFileByRelativePath("src/main/resources");
            if (resourcesDir != null && resourcesDir.isDirectory()) {
                scanDirectoryRecursively(resourcesDir, null, result, visitedPaths);
            }
        }

        // 3. Scan user-configured additional directories
        PluginSettingsState.State settings = PluginSettingsState.getInstance(project).getState();
        String projectBaseDir = project.getBasePath();
        for (String dirPath : settings.additionalDirectories) {
            VirtualFile dir;
            if (projectBaseDir != null && !dirPath.startsWith("/") && !dirPath.contains(":")) {
                dir = LocalFileSystem.getInstance().findFileByPath(projectBaseDir + "/" + dirPath);
            } else {
                dir = LocalFileSystem.getInstance().findFileByPath(dirPath);
            }
            if (dir != null && dir.isDirectory()) {
                scanDirectoryRecursively(dir, null, result, visitedPaths);
            }
        }

        // Sort configs: "default" first, then alphabetically by profile
        result.sort((a, b) -> {
            if (a.isDefault() != b.isDefault()) {
                return a.isDefault() ? -1 : 1;
            }
            return a.getProfile().compareToIgnoreCase(b.getProfile());
        });

        cachedConfigs.clear();
        cachedConfigs.addAll(result);

        ConfigSwitcherLog.info(project, "ConfigScanner: Found " + result.size() + " configuration file(s).");

        // Notify subscribers of the change
        project.getMessageBus().syncPublisher(TOPIC).onConfigsChanged(new ArrayList<>(cachedConfigs));

        return new ArrayList<>(cachedConfigs);
    }

    private void scanDirectoryRecursively(
            @NotNull VirtualFile directory,
            @Nullable String moduleName,
            @NotNull List<ConfigFileModel> result,
            @NotNull Set<String> visitedPaths
    ) {
        if (!directory.isDirectory()) return;

        VirtualFile[] children = directory.getChildren();
        if (children == null) return;

        String projectBasePath = project.getBasePath();

        for (VirtualFile child : children) {
            if (child.isDirectory()) {
                String dirName = child.getName();
                if (!"target".equals(dirName) && !"build".equals(dirName) && !"out".equals(dirName) && !dirName.startsWith(".")) {
                    scanDirectoryRecursively(child, moduleName, result, visitedPaths);
                }
            } else {
                String profile = parseProfileName(child.getName());
                if (profile != null && visitedPaths.add(child.getPath())) {
                    String relativePath;
                    if (projectBasePath != null && child.getPath().startsWith(projectBasePath)) {
                        relativePath = child.getPath().substring(projectBasePath.length());
                        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
                            relativePath = relativePath.substring(1);
                        }
                    } else {
                        relativePath = child.getName();
                    }
                    boolean isDefault = "default".equalsIgnoreCase(profile) ||
                            "application.yml".equalsIgnoreCase(child.getName()) ||
                            "application.yaml".equalsIgnoreCase(child.getName());

                    result.add(new ConfigFileModel(
                            child,
                            child.getName(),
                            profile,
                            isDefault,
                            relativePath,
                            moduleName
                    ));
                }
            }
        }
    }

    @Nullable
    public ConfigFileModel findConfigByProfile(@NotNull String profile) {
        for (ConfigFileModel config : getConfigurations()) {
            if (config.getProfile().equalsIgnoreCase(profile)) {
                return config;
            }
        }
        return null;
    }

    @Nullable
    public ConfigFileModel getBaseConfigFile() {
        List<ConfigFileModel> configs = getConfigurations();
        for (ConfigFileModel config : configs) {
            if (config.isDefault()) return config;
        }
        for (ConfigFileModel config : configs) {
            if ("application.yml".equalsIgnoreCase(config.getFileName()) || "application.yaml".equalsIgnoreCase(config.getFileName())) {
                return config;
            }
        }
        return null;
    }

    @Override
    public void dispose() {
        cachedConfigs.clear();
    }
}
