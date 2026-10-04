package configswitcher.mesh.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import configswitcher.mesh.model.MeshRequestEntry;
import configswitcher.mesh.model.MeshRequestStatus;
import configswitcher.state.PluginSettingsState;
import configswitcher.util.ConfigSwitcherLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service(Service.Level.PROJECT)
public final class MeshCaptureService implements Disposable {

    public interface MeshCaptureListener {
        void onEntryAdded(@NotNull MeshRequestEntry entry);
        void onEntryUpdated(@NotNull MeshRequestEntry entry);
        void onCleared();
    }

    private final Project project;
    private final MeshOutputAnalyzer analyzer;
    private final List<MeshRequestEntry> entries = Collections.synchronizedList(new ArrayList<>());
    private final List<MeshCaptureListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean paused = false;

    public MeshCaptureService(@Nullable Project project) {
        this.project = project;
        this.analyzer = new MeshOutputAnalyzer(this);
    }

    public static MeshCaptureService getInstance(@NotNull Project project) {
        return project.getService(MeshCaptureService.class);
    }

    public @NotNull MeshOutputAnalyzer getAnalyzer() {
        return analyzer;
    }

    public void addListener(@NotNull MeshCaptureListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull MeshCaptureListener listener) {
        listeners.remove(listener);
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void clear() {
        entries.clear();
        analyzer.clear();
        if (project != null && !project.isDisposed()) {
            try {
                configswitcher.service.SessionLogManager.getInstance(project).saveMeshSessionJson("[]");
            } catch (Throwable ignored) {}
        }
        notifyCleared();
    }

    public @NotNull List<MeshRequestEntry> getEntries() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public int getEntryCount() {
        return entries.size();
    }

    public @Nullable MeshRequestEntry findEntryByTimestamp(@Nullable String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return null;
        synchronized (entries) {
            for (MeshRequestEntry e : entries) {
                if (timestamp.equals(e.getTimestamp())) {
                    return e;
                }
            }
        }
        return null;
    }

    public static boolean isSameTimestamp(@NotNull MeshRequestEntry a, @NotNull MeshRequestEntry b) {
        if (a.getTimestamp() != null && a.getTimestamp().equals(b.getTimestamp())) {
            return true;
        }
        return a.getEpochMillis() > 0 && b.getEpochMillis() > 0 && a.getEpochMillis() == b.getEpochMillis();
    }

    public void onEntryCreated(@NotNull MeshRequestEntry entry) {
        if (paused) return;

        int maxHistory = 500;
        if (project != null) {
            try {
                maxHistory = PluginSettingsState.getInstance(project).getState().meshMaxHistory;
                if (maxHistory <= 0) maxHistory = 500;
            } catch (Throwable ignored) {}
        }

        synchronized (entries) {
            // Guarantee records are unique by timestamp
            for (int i = 0; i < entries.size(); i++) {
                MeshRequestEntry existing = entries.get(i);
                if (isSameTimestamp(existing, entry)) {
                    mergeEntry(existing, entry);
                    notifyUpdated(existing);
                    scheduleSaveSessionJson();
                    return;
                }
            }

            while (entries.size() >= maxHistory) {
                entries.remove(0);
            }
            entries.add(entry);
        }

        notifyAdded(entry);
        scheduleSaveSessionJson();
    }

    private void mergeEntry(@NotNull MeshRequestEntry target, @NotNull MeshRequestEntry source) {
        if (target.getEndpoint() == null && source.getEndpoint() != null) {
            target.setEndpoint(source.getEndpoint());
        }
        if (target.getHttpStatus() == null && source.getHttpStatus() != null) {
            target.setHttpStatus(source.getHttpStatus());
        }
        if (target.getStatus() == MeshRequestStatus.PENDING && source.getStatus() != MeshRequestStatus.PENDING) {
            target.setStatus(source.getStatus());
        }
        if (target.getDurationMs() == null && source.getDurationMs() != null) {
            target.setDurationMs(source.getDurationMs());
        }
        if (target.getFormattedQuery() == null && source.getFormattedQuery() != null) {
            target.setFormattedQuery(source.getFormattedQuery());
        }
        if (target.getRawQuery() == null && source.getRawQuery() != null) {
            target.setRawQuery(source.getRawQuery());
        }
        if (target.getFormattedVariables() == null && source.getFormattedVariables() != null) {
            target.setFormattedVariables(source.getFormattedVariables());
        }
        if (target.getRawVariables() == null && source.getRawVariables() != null) {
            target.setRawVariables(source.getRawVariables());
        }
        if (target.getFormattedResponse() == null && source.getFormattedResponse() != null) {
            target.setFormattedResponse(source.getFormattedResponse());
        }
        if (target.getRawResponse() == null && source.getRawResponse() != null) {
            target.setRawResponse(source.getRawResponse());
        }
        if (target.getTraceId() == null && source.getTraceId() != null) {
            target.setTraceId(source.getTraceId());
        }
        if (target.getSpanId() == null && source.getSpanId() != null) {
            target.setSpanId(source.getSpanId());
        }
        if (target.getUserId() == null && source.getUserId() != null) {
            target.setUserId(source.getUserId());
        }
        if (target.getErrorMessage() == null && source.getErrorMessage() != null) {
            target.setErrorMessage(source.getErrorMessage());
        }
        if (target.getErrorType() == configswitcher.mesh.model.MeshErrorType.NONE && source.getErrorType() != configswitcher.mesh.model.MeshErrorType.NONE) {
            target.setErrorType(source.getErrorType());
        }
        if (target.getRootCauseMessage() == null && source.getRootCauseMessage() != null) {
            target.setRootCauseMessage(source.getRootCauseMessage());
        }
        if (target.getRawStackTrace() == null && source.getRawStackTrace() != null) {
            target.setRawStackTrace(source.getRawStackTrace());
        }
        if (source.getRawLogLines() != null) {
            for (String line : source.getRawLogLines()) {
                target.addRawLogLine(line);
            }
        }
    }

    public void onEntryUpdated(@NotNull MeshRequestEntry entry) {
        if (paused) return;
        notifyUpdated(entry);
        scheduleSaveSessionJson();
    }

    private void scheduleSaveSessionJson() {
        if (project == null || project.isDisposed()) return;
        try {
            com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                try {
                    if (project != null && !project.isDisposed()) {
                        String json = exportToJson();
                        configswitcher.service.SessionLogManager.getInstance(project).saveMeshSessionJson(json);
                    }
                } catch (Throwable ignored) {}
            }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}
    }

    private void notifyAdded(@NotNull MeshRequestEntry entry) {
        var app = ApplicationManager.getApplication();
        if (app != null) {
            app.invokeLater(() -> {
                for (MeshCaptureListener l : listeners) {
                    try {
                        l.onEntryAdded(entry);
                    } catch (Throwable t) {
                        ConfigSwitcherLog.warn(project, "MeshCaptureListener onEntryAdded error: " + t.getMessage());
                    }
                }
            });
        } else {
            for (MeshCaptureListener l : listeners) {
                try {
                    l.onEntryAdded(entry);
                } catch (Throwable t) {
                    ConfigSwitcherLog.warn(project, "MeshCaptureListener onEntryAdded error: " + t.getMessage());
                }
            }
        }
    }

    private void notifyUpdated(@NotNull MeshRequestEntry entry) {
        var app = ApplicationManager.getApplication();
        if (app != null) {
            app.invokeLater(() -> {
                for (MeshCaptureListener l : listeners) {
                    try {
                        l.onEntryUpdated(entry);
                    } catch (Throwable t) {
                        ConfigSwitcherLog.warn(project, "MeshCaptureListener onEntryUpdated error: " + t.getMessage());
                    }
                }
            });
        } else {
            for (MeshCaptureListener l : listeners) {
                try {
                    l.onEntryUpdated(entry);
                } catch (Throwable t) {
                    ConfigSwitcherLog.warn(project, "MeshCaptureListener onEntryUpdated error: " + t.getMessage());
                }
            }
        }
    }

    private void notifyCleared() {
        var app = ApplicationManager.getApplication();
        if (app != null) {
            app.invokeLater(() -> {
                for (MeshCaptureListener l : listeners) {
                    try {
                        l.onCleared();
                    } catch (Throwable t) {
                        ConfigSwitcherLog.warn(project, "MeshCaptureListener onCleared error: " + t.getMessage());
                    }
                }
            });
        } else {
            for (MeshCaptureListener l : listeners) {
                try {
                    l.onCleared();
                } catch (Throwable t) {
                    ConfigSwitcherLog.warn(project, "MeshCaptureListener onCleared error: " + t.getMessage());
                }
            }
        }
    }

    @NotNull
    public String generateCurlCommand(@NotNull MeshRequestEntry entry) {
        String endpoint = entry.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            try {
                endpoint = PluginSettingsState.getInstance(project).getState().meshDefaultEndpointUrl;
            } catch (Throwable ignored) {}
            if (endpoint == null || endpoint.isBlank()) {
                endpoint = "http://mesh-java.test.ecp/graphql";
            }
        }

        JsonObject bodyObj = new JsonObject();
        String q = entry.getRawQuery();
        bodyObj.addProperty("query", q != null ? q : "");

        String rawVars = entry.getRawVariables();
        if (rawVars != null && !rawVars.isBlank()) {
            try {
                bodyObj.add("variables", com.google.gson.JsonParser.parseString(DataSetJsonConverter.toPrettyJson(rawVars)));
            } catch (Exception e) {
                bodyObj.add("variables", new JsonObject());
            }
        } else {
            bodyObj.add("variables", new JsonObject());
        }

        String jsonPayload = new Gson().toJson(bodyObj);
        // Escape single quotes for bash
        String escapedJson = jsonPayload.replace("'", "'\\''");

        StringBuilder sb = new StringBuilder();
        sb.append("curl -X POST '").append(endpoint).append("' \\\n");
        sb.append("  -H 'Content-Type: application/json' \\\n");
        sb.append("  -H 'Accept: application/json' \\\n");

        if (entry.getTraceId() != null && !entry.getTraceId().isBlank()) {
            sb.append("  -H 'X-B3-TraceId: ").append(entry.getTraceId()).append("' \\\n");
        }
        if (entry.getSpanId() != null && !entry.getSpanId().isBlank()) {
            sb.append("  -H 'X-B3-SpanId: ").append(entry.getSpanId()).append("' \\\n");
        }

        sb.append("  --data-raw '").append(escapedJson).append("'");
        return sb.toString();
    }

    @NotNull
    public String exportToJson() {
        JsonArray array = new JsonArray();
        synchronized (entries) {
            for (MeshRequestEntry e : entries) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", e.getId());
                obj.addProperty("timestamp", e.getTimestamp());
                obj.addProperty("status", e.getStatus().name());
                obj.addProperty("httpStatus", e.getHttpStatus());
                obj.addProperty("operationType", e.getOperationType().name());
                obj.addProperty("operationName", e.getOperationName());
                obj.addProperty("rootField", e.getRootField());
                obj.addProperty("endpoint", e.getEndpoint());
                obj.addProperty("durationMs", e.getDurationMs());
                obj.addProperty("traceId", e.getTraceId());
                obj.addProperty("spanId", e.getSpanId());
                obj.addProperty("userId", e.getUserId());
                obj.addProperty("threadName", e.getThreadName());
                obj.addProperty("query", e.getFormattedQuery());
                obj.addProperty("variables", e.getFormattedVariables());
                obj.addProperty("response", e.getFormattedResponse());
                obj.addProperty("errorMessage", e.getErrorMessage());
                obj.addProperty("errorType", e.getErrorType() != null ? e.getErrorType().name() : null);
                obj.addProperty("rootCauseMessage", e.getRootCauseMessage());
                obj.addProperty("errorCode", e.getErrorCode());
                obj.addProperty("errorPath", e.getErrorPath());
                array.add(obj);
            }
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(array);
    }

    public @Nullable MeshRequestEntry findPreviousSuccessfulEntry(@NotNull MeshRequestEntry current) {
        synchronized (entries) {
            String op = current.getOperationName();
            String root = current.getRootField();
            for (int i = entries.size() - 1; i >= 0; i--) {
                MeshRequestEntry candidate = entries.get(i);
                if (candidate.getId() < current.getId() && candidate.getStatus() == configswitcher.mesh.model.MeshRequestStatus.SUCCESS) {
                    if (op != null && !op.isEmpty() && op.equals(candidate.getOperationName())) {
                        return candidate;
                    }
                    if (root != null && !root.isEmpty() && root.equals(candidate.getRootField())) {
                        return candidate;
                    }
                    if (op == null && root == null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void dispose() {
        entries.clear();
        listeners.clear();
        analyzer.clear();
    }
}
