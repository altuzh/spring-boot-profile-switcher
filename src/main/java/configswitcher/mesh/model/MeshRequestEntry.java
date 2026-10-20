package configswitcher.mesh.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MeshRequestEntry {
    private final int id;
    private final String timestamp;
    private final long epochMillis;

    private String traceId;
    private String spanId;
    private String appName;
    private String threadName;
    private String pid;
    private String userId;

    private MeshOperationType operationType = MeshOperationType.UNKNOWN;
    private String operationName;
    private String rootField;
    private String endpoint;

    private String rawQuery;
    private String formattedQuery;
    private String rawVariables;
    private String formattedVariables;

    private MeshRequestStatus status = MeshRequestStatus.PENDING;
    private String httpStatus;
    private Long durationMs;

    private String rawResponse;
    private String formattedResponse;
    private long responseSizeBytes = 0;
    private String errorMessage;

    private String logLevel = "INFO";
    private MeshErrorType errorType = MeshErrorType.NONE;
    private String rootCauseMessage;
    private String errorCode;
    private String errorPath;
    private String rawStackTrace;

    private final List<String> rawLogLines = Collections.synchronizedList(new ArrayList<>());

    public MeshRequestEntry(int id, @NotNull String timestamp, long epochMillis) {
        this.id = id;
        this.timestamp = timestamp;
        this.epochMillis = epochMillis;
    }

    public int getId() {
        return id;
    }

    public @NotNull String getTimestamp() {
        return timestamp;
    }

    public @NotNull String getFormattedTime() {
        if (timestamp != null && !timestamp.isBlank()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2}:\\d{2}:\\d{2})").matcher(timestamp);
            if (m.find()) {
                return m.group(1);
            }
        }
        if (epochMillis > 0) {
            try {
                return java.time.Instant.ofEpochMilli(epochMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            } catch (Exception ignored) {}
        }
        return timestamp != null ? timestamp : "-";
    }

    public long getEpochMillis() {
        return epochMillis;
    }

    public @Nullable String getTraceId() {
        return traceId;
    }

    public void setTraceId(@Nullable String traceId) {
        this.traceId = traceId;
    }

    public @Nullable String getSpanId() {
        return spanId;
    }

    public void setSpanId(@Nullable String spanId) {
        this.spanId = spanId;
    }

    public @Nullable String getAppName() {
        return appName;
    }

    public void setAppName(@Nullable String appName) {
        this.appName = appName;
    }

    public @Nullable String getThreadName() {
        return threadName;
    }

    public void setThreadName(@Nullable String threadName) {
        this.threadName = threadName;
    }

    public @Nullable String getPid() {
        return pid;
    }

    public void setPid(@Nullable String pid) {
        this.pid = pid;
    }

    public @Nullable String getUserId() {
        return userId;
    }

    public void setUserId(@Nullable String userId) {
        this.userId = userId;
    }

    public @NotNull MeshOperationType getOperationType() {
        if ((operationType == null || operationType == MeshOperationType.UNKNOWN) && rawQuery != null && !rawQuery.isBlank()) {
            MeshOperationType detected = MeshOperationType.fromQueryText(rawQuery);
            if (detected != MeshOperationType.UNKNOWN) {
                this.operationType = detected;
            }
        }
        return operationType != null ? operationType : MeshOperationType.UNKNOWN;
    }

    public void setOperationType(@NotNull MeshOperationType operationType) {
        this.operationType = operationType;
    }

    public @Nullable String getOperationName() {
        return operationName;
    }

    public void setOperationName(@Nullable String operationName) {
        this.operationName = operationName;
    }

    public @Nullable String getRootField() {
        return rootField;
    }

    public void setRootField(@Nullable String rootField) {
        this.rootField = rootField;
    }

    public @NotNull String getDisplayOperation() {
        if (operationName != null && !operationName.isBlank()) {
            return operationName;
        }
        if (rootField != null && !rootField.isBlank()) {
            return rootField;
        }
        return "anonymous " + operationType.name().toLowerCase();
    }

    public @Nullable String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(@Nullable String endpoint) {
        this.endpoint = endpoint;
    }

    public @Nullable String getRawQuery() {
        return rawQuery;
    }

    public void setRawQuery(@Nullable String rawQuery) {
        this.rawQuery = rawQuery;
        if (rawQuery != null && (operationType == null || operationType == MeshOperationType.UNKNOWN)) {
            MeshOperationType detected = MeshOperationType.fromQueryText(rawQuery);
            if (detected != MeshOperationType.UNKNOWN) {
                this.operationType = detected;
            }
        }
    }

    public @Nullable String getFormattedQuery() {
        return formattedQuery != null ? formattedQuery : rawQuery;
    }

    public void setFormattedQuery(@Nullable String formattedQuery) {
        this.formattedQuery = formattedQuery;
    }

    public @Nullable String getRawVariables() {
        return rawVariables;
    }

    public void setRawVariables(@Nullable String rawVariables) {
        this.rawVariables = rawVariables;
    }

    public @Nullable String getFormattedVariables() {
        return formattedVariables != null ? formattedVariables : rawVariables;
    }

    public void setFormattedVariables(@Nullable String formattedVariables) {
        this.formattedVariables = formattedVariables;
    }

    public @NotNull MeshRequestStatus getStatus() {
        return status;
    }

    public void setStatus(@NotNull MeshRequestStatus status) {
        this.status = status;
    }

    public @Nullable String getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(@Nullable String httpStatus) {
        this.httpStatus = httpStatus;
    }

    public @Nullable Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(@Nullable Long durationMs) {
        this.durationMs = durationMs;
    }

    public @NotNull String getFormattedDuration() {
        if (durationMs == null) {
            return status == MeshRequestStatus.PENDING ? "..." : "-";
        }
        if (durationMs < 1000) {
            return durationMs + " ms";
        }
        return String.format("%.2f s", durationMs / 1000.0);
    }

    public @Nullable String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(@Nullable String rawResponse) {
        this.rawResponse = rawResponse;
        if (rawResponse != null) {
            this.responseSizeBytes = rawResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
    }

    public @Nullable String getFormattedResponse() {
        return formattedResponse != null ? formattedResponse : rawResponse;
    }

    public void setFormattedResponse(@Nullable String formattedResponse) {
        this.formattedResponse = formattedResponse;
    }

    public long getResponseSizeBytes() {
        return responseSizeBytes;
    }

    public void setResponseSizeBytes(long responseSizeBytes) {
        this.responseSizeBytes = responseSizeBytes;
    }

    public @NotNull String getFormattedSize() {
        if (responseSizeBytes <= 0) return "-";
        if (responseSizeBytes < 1024) return responseSizeBytes + " B";
        if (responseSizeBytes < 1024 * 1024) return String.format("%.1f KB", responseSizeBytes / 1024.0);
        return String.format("%.2f MB", responseSizeBytes / (1024.0 * 1024.0));
    }

    public @Nullable String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(@Nullable String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public @NotNull MeshErrorType getErrorType() {
        return errorType != null ? errorType : MeshErrorType.NONE;
    }

    public void setErrorType(@NotNull MeshErrorType errorType) {
        this.errorType = errorType;
    }

    public @Nullable String getRootCauseMessage() {
        return rootCauseMessage;
    }

    public void setRootCauseMessage(@Nullable String rootCauseMessage) {
        this.rootCauseMessage = rootCauseMessage;
    }

    public @Nullable String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(@Nullable String errorCode) {
        this.errorCode = errorCode;
    }

    public @Nullable String getErrorPath() {
        return errorPath;
    }

    public void setErrorPath(@Nullable String errorPath) {
        this.errorPath = errorPath;
    }

    public @Nullable String getRawStackTrace() {
        return rawStackTrace;
    }

    public void setRawStackTrace(@Nullable String rawStackTrace) {
        this.rawStackTrace = rawStackTrace;
    }

    public boolean hasError() {
        return status == MeshRequestStatus.ERROR || (errorType != null && errorType != MeshErrorType.NONE) || (errorMessage != null && !errorMessage.isBlank());
    }

    public boolean isMeshRecord() {
        MeshOperationType op = getOperationType();
        return op == MeshOperationType.QUERY || op == MeshOperationType.MUTATION;
    }

    public @NotNull List<String> getRawLogLines() {
        synchronized (rawLogLines) {
            return new ArrayList<>(rawLogLines);
        }
    }

    public void addRawLogLine(@NotNull String line) {
        rawLogLines.add(line);
    }

    public @NotNull String getLogLevel() {
        return logLevel != null ? logLevel : "INFO";
    }

    public void setLogLevel(@Nullable String logLevel) {
        this.logLevel = logLevel != null ? logLevel.toUpperCase() : "INFO";
    }

    public boolean matchesSearch(@Nullable String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase().trim();
        if (String.valueOf(id).contains(q)) return true;
        if (logLevel != null && logLevel.toLowerCase().contains(q)) return true;
        if (operationName != null && operationName.toLowerCase().contains(q)) return true;
        if (rootField != null && rootField.toLowerCase().contains(q)) return true;
        if (traceId != null && traceId.toLowerCase().contains(q)) return true;
        if (spanId != null && spanId.toLowerCase().contains(q)) return true;
        if (userId != null && userId.toLowerCase().contains(q)) return true;
        if (threadName != null && threadName.toLowerCase().contains(q)) return true;
        if (endpoint != null && endpoint.toLowerCase().contains(q)) return true;
        if (httpStatus != null && httpStatus.toLowerCase().contains(q)) return true;
        if (rawQuery != null && rawQuery.toLowerCase().contains(q)) return true;
        if (rawResponse != null && rawResponse.toLowerCase().contains(q)) return true;
        if (errorMessage != null && errorMessage.toLowerCase().contains(q)) return true;
        if (rootCauseMessage != null && rootCauseMessage.toLowerCase().contains(q)) return true;
        if (errorCode != null && errorCode.toLowerCase().contains(q)) return true;
        if (errorPath != null && errorPath.toLowerCase().contains(q)) return true;
        if (errorType != null && errorType.name().toLowerCase().contains(q)) return true;
        if (errorType != null && errorType.getDisplayName().toLowerCase().contains(q)) return true;
        if (rawStackTrace != null && rawStackTrace.toLowerCase().contains(q)) return true;
        return false;
    }
}
