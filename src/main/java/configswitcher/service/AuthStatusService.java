package configswitcher.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.ActivityTracker;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.concurrency.AppExecutorUtil;
import configswitcher.state.PluginSettingsState;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service(Service.Level.PROJECT)
public final class AuthStatusService implements Disposable {
    public enum Status { CHECKING, ACTIVE, RECONNECTING, DISABLED, ABSENT, UNKNOWN }

    public static final int SERVICE_PORT = 80;

    public record Settings(String service, String namespace) {
        public boolean configured() {
            return !service.isBlank() && !namespace.isBlank();
        }
    }

    private volatile Status status = Status.CHECKING;
    private volatile Settings settings;
    private ScheduledFuture<?> monitor;
    private boolean disposed;

    public AuthStatusService(Project project) {
        settingsChanged(project.getService(PluginSettingsState.class).getState());
    }

    public Settings getSettings() {
        return settings;
    }

    public Status getStatus() {
        return status;
    }

    public synchronized void settingsChanged(PluginSettingsState.State state) {
        Settings updated = new Settings(state.authServiceName, state.authNamespace);
        boolean shouldPoll = state.reportAuthState && updated.configured();
        if (disposed || (updated.equals(settings) && shouldPoll == (monitor != null))) return;
        if (monitor != null) monitor.cancel(false);
        monitor = null;
        settings = updated;
        status = shouldPoll ? Status.CHECKING : Status.UNKNOWN;
        if (shouldPoll) {
            monitor = AppExecutorUtil.getAppScheduledExecutorService()
                    .scheduleWithFixedDelay(() -> poll(updated), 0, 3, TimeUnit.SECONDS);
        }
        ActivityTracker.getInstance().inc();
    }

    public synchronized void refresh() {
        if (monitor == null) return;
        Settings target = settings;
        AppExecutorUtil.getAppExecutorService().execute(() -> poll(target));
    }

    private void poll(Settings target) {
        Status updated = readLensStatus(target);
        synchronized (this) {
            if (monitor != null && settings == target && status != updated) {
                status = updated;
                ActivityTracker.getInstance().inc();
            }
        }
    }

    static Status readLensStatus(Settings target) {
        try {
            Path root = configRoot();
            Set<String> apps = new HashSet<>();
            try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
                processes.forEach(p -> p.info().command().ifPresent(command -> {
                    String name = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
                    switch (name) {
                        case "openlens", "openlens.exe" -> apps.add("OpenLens");
                        case "lens", "lens.exe" -> apps.add("Lens");
                    }
                }));
            }
            if (apps.isEmpty()) return Status.UNKNOWN;
            EnumSet<Status> matches = EnumSet.noneOf(Status.class);
            for (String app : apps) {
                Path home = root.resolve(app);
                Path modern = home.resolve("persisted-state/port-forward");
                if (Files.isDirectory(modern)) {
                    readStore(modern, "value", target, matches);
                } else {
                    readStore(home.resolve("lens-local-storage"), "port_forwards", target, matches);
                }
            }
            if (matches.isEmpty()) return Status.ABSENT;
            return matches.size() == 1 ? matches.iterator().next() : Status.UNKNOWN;
        } catch (IOException | RuntimeException e) {
            return Status.UNKNOWN;
        }
    }

    private static Path configRoot() {
        if (SystemInfo.isWindows) return Path.of(System.getenv("APPDATA"));
        Path home = Path.of(System.getProperty("user.home"));
        if (SystemInfo.isMac) return home.resolve("Library/Application Support");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return xdg == null || xdg.isBlank() ? home.resolve(".config") : Path.of(xdg);
    }

    static void readStore(Path directory, String key, Settings target, Set<Status> matches) throws IOException {
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                if (file.getFileName().toString().equals("app.json")) continue;
                try (Reader reader = Files.newBufferedReader(file)) {
                    JsonElement rows = JsonParser.parseReader(reader).getAsJsonObject().get(key);
                    if (rows == null) continue;
                    for (JsonElement row : rows.getAsJsonArray()) {
                        JsonObject item = row.getAsJsonObject();
                        if ("service".equals(field(item, "kind")) && target.service().equals(field(item, "name"))
                                && target.namespace().equals(field(item, "namespace"))
                                && Integer.toString(SERVICE_PORT).equals(field(item, "port"))) {
                            matches.add(switch (field(item, "status")) {
                                case "Active" -> Status.ACTIVE;
                                case "Reconnecting" -> Status.RECONNECTING;
                                case "Disabled" -> Status.DISABLED;
                                default -> Status.UNKNOWN;
                            });
                        }
                    }
                }
            }
        }
    }

    private static String field(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        if (monitor != null) monitor.cancel(false);
        monitor = null;
    }
}
