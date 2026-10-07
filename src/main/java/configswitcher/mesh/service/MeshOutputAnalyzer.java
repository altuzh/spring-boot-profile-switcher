package configswitcher.mesh.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import configswitcher.mesh.model.MeshOperationType;
import configswitcher.mesh.model.MeshRequestEntry;
import configswitcher.mesh.model.MeshRequestStatus;
import configswitcher.util.ConfigSwitcherLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MeshOutputAnalyzer {

    private static final DateTimeFormatter LOG_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Matches standard Spring Boot log line:
    // 2026-09-27 01:06:26.848  INFO [co,c1ff8fe5b6cd26a2,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] n.n.f.b.g.GraphQlDataProviderEngine      : Execute GraphQL query: ...
    private static final Pattern LOG_LINE_PATTERN = Pattern.compile(
            "^(?<timestamp>\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+" +
            "(?<level>[A-Z]+)\\s+" +
            "(?:\\[(?<app>[^,\\s]+),(?<traceId>[^,\\s]+),(?<spanId>[^,\\]\\s]+)\\])?\\s*" +
            "(?<pid>\\d+)?\\s*" +
            "(?:---\\s*\\[(?<thread>[^\\]]+)\\])?\\s*" +
            "(?<logger>[^:]+)\\s*:\\s*" +
            "(?<message>.*)$"
    );

    private static final Pattern HTTP_POST_PATTERN = Pattern.compile(
            "HTTP POST\\s+(?<url>https?://[^\\s]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HTTP_RESPONSE_PATTERN = Pattern.compile(
            "Response\\s+(?<code>\\d{3})\\s+(?<text>[A-Za-z_ ]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WRITING_JSON_PATTERN = Pattern.compile(
            "Writing\\s+\\[\\{(?:variables=(?<vars>\\{[^}]*\\}|null|\\[\\]),?\\s*)?(?:query=)?(?<query>[\\s\\S]*)\\}\\]\\s+as\\s+\"application/json\"",
            Pattern.CASE_INSENSITIVE
    );

    private final MeshCaptureService captureService;
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);

    private final Map<String, MeshRequestEntry> pendingByTrace = new ConcurrentHashMap<>();
    private final Map<String, MeshRequestEntry> pendingByThread = new ConcurrentHashMap<>();
    private final Map<String, MeshRequestEntry> activeQueryByThread = new ConcurrentHashMap<>();

    private final StringBuilder lineBuffer = new StringBuilder();

    public MeshOutputAnalyzer(@NotNull MeshCaptureService captureService) {
        this.captureService = captureService;
    }

    public synchronized void processChunk(@Nullable String chunk) {
        if (chunk == null || chunk.isEmpty()) return;

        lineBuffer.append(chunk);
        int newlineIdx;
        while ((newlineIdx = lineBuffer.indexOf("\n")) >= 0) {
            String line = lineBuffer.substring(0, newlineIdx);
            lineBuffer.delete(0, newlineIdx + 1);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            processLine(line);
        }
    }

    public synchronized void processLine(@NotNull String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;

        // 1. Check if line is a JSON formatted log line (Logstash / ECP / NDJSON)
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && (trimmed.contains("\"message\"") || trimmed.contains("\"msg\""))) {
            if (processJsonLogLine(trimmed, line)) {
                return;
            }
        }

        // 2. Check standard Spring Boot / Logback text log line
        Matcher logMatcher = LOG_LINE_PATTERN.matcher(line);
        if (logMatcher.matches()) {
            String timestampStr = logMatcher.group("timestamp");
            String level = logMatcher.group("level");
            String app = logMatcher.group("app");
            String traceId = logMatcher.group("traceId");
            String spanId = logMatcher.group("spanId");
            String pid = logMatcher.group("pid");
            String thread = logMatcher.group("thread");
            String logger = logMatcher.group("logger");
            String message = logMatcher.group("message");

            // Close active multi-line query on this thread if a new log statement starts
            if (thread != null) {
                activeQueryByThread.remove(thread);
            }

            long epochMillis = parseEpochMillis(timestampStr);
            handleLogMessage(line, timestampStr, epochMillis, level, app, traceId, spanId, pid, thread, logger, message, null, null);
        } else {
            // Continuation line of multi-line payload/query
            handleContinuationLine(line);
        }
    }

    private boolean processJsonLogLine(@NotNull String jsonStr, @NotNull String fullLine) {
        try {
            JsonElement el = JsonParser.parseString(jsonStr);
            if (!el.isJsonObject()) return false;
            JsonObject obj = el.getAsJsonObject();

            String message = getJsonString(obj, "message", "msg");
            if (message == null) return false;

            String rawTimestamp = getJsonString(obj, "timestamp", "@timestamp", "time");
            String timestampStr = rawTimestamp != null ? formatDisplayTimestamp(rawTimestamp) :
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            long epochMillis = rawTimestamp != null ? parseEpochMillis(rawTimestamp) : System.currentTimeMillis();

            String level = getJsonString(obj, "level", "severity");
            if (level == null) level = "INFO";

            String thread = getJsonString(obj, "threadName", "thread", "thread_name");
            String logger = getJsonString(obj, "module", "logger", "logger_name");
            String service = getJsonString(obj, "service", "serviceName", "appName", "app");
            if ("SERVICE_NAME_IS_UNDEFINED".equalsIgnoreCase(service)) {
                service = null;
            }

            String traceId = getJsonString(obj, "traceId", "trace_id");
            String spanId = getJsonString(obj, "spanId", "span_id");

            String userId = null;
            if (obj.has("context") && obj.get("context").isJsonObject()) {
                JsonObject contextObj = obj.getAsJsonObject("context");
                if (traceId == null && contextObj.has("tracingContext") && contextObj.get("tracingContext").isJsonObject()) {
                    JsonObject tracing = contextObj.getAsJsonObject("tracingContext");
                    traceId = getJsonString(tracing, "traceId", "trace_id");
                    if (spanId == null) {
                        spanId = getJsonString(tracing, "spanId", "span_id");
                    }
                }
                if (contextObj.has("authContext") && contextObj.get("authContext").isJsonObject()) {
                    JsonObject auth = contextObj.getAsJsonObject("authContext");
                    userId = getJsonString(auth, "userId", "user_id");
                }
            }
            if (userId == null) {
                userId = getJsonString(obj, "userId", "user_id");
            }

            String exceptionStr = getJsonString(obj, "Exception", "exception", "stack_trace", "stackTrace", "throwable");

            // Close active multi-line query on this thread if a new log statement starts
            if (thread != null) {
                activeQueryByThread.remove(thread);
            }

            handleLogMessage(fullLine, timestampStr, epochMillis, level, service, traceId, spanId, null, thread, logger, message, userId, exceptionStr);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private static String getJsonString(@NotNull JsonObject obj, String... keys) {
        for (String k : keys) {
            if (obj.has(k) && !obj.get(k).isJsonNull()) {
                try {
                    String val = obj.get(k).getAsString();
                    if (val != null && !val.isBlank()) {
                        return val.trim();
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private void handleLogMessage(
            @NotNull String fullLine,
            @NotNull String timestampStr,
            long epochMillis,
            @NotNull String level,
            @Nullable String app,
            @Nullable String traceId,
            @Nullable String spanId,
            @Nullable String pid,
            @Nullable String thread,
            @Nullable String logger,
            @NotNull String message,
            @Nullable String userId,
            @Nullable String exceptionStr
    ) {
        // 1. Check for Query Start: "Execute GraphQL query: ..."
        int execIdx = message.indexOf("Execute GraphQL query:");
        if (execIdx >= 0) {
            String initialQuery = message.substring(execIdx + "Execute GraphQL query:".length()).trim();

            // Check if record with this timestamp was already captured
            MeshRequestEntry existing = captureService.findEntryByTimestamp(timestampStr);
            if (existing != null) {
                if (traceId != null) {
                    pendingByTrace.put(traceId, existing);
                }
                if (thread != null) {
                    pendingByThread.put(thread, existing);
                    activeQueryByThread.put(thread, existing);
                }
                return;
            }

            MeshRequestEntry entry = new MeshRequestEntry(sequenceCounter.incrementAndGet(), timestampStr, epochMillis);
            entry.setAppName(app);
            entry.setTraceId(traceId);
            entry.setSpanId(spanId);
            entry.setPid(pid);
            entry.setThreadName(thread);
            entry.setUserId(userId);
            entry.setLogLevel(level);
            entry.addRawLogLine(fullLine);

            entry.setRawQuery(initialQuery);
            entry.setOperationType(MeshOperationType.fromQueryText(initialQuery));
            entry.setOperationName(GraphQLFormatter.extractOperationName(initialQuery));
            entry.setRootField(GraphQLFormatter.extractRootField(initialQuery));

            if (traceId != null) {
                pendingByTrace.put(traceId, entry);
            }
            if (thread != null) {
                pendingByThread.put(thread, entry);
                activeQueryByThread.put(thread, entry);
            }

            captureService.onEntryCreated(entry);
            return;
        }

        // Correlate with existing pending entry
        MeshRequestEntry pending = findPendingEntry(traceId, thread);
        if (pending != null && userId != null && pending.getUserId() == null) {
            pending.setUserId(userId);
        }

        // 2. Check for RestTemplate HTTP POST
        Matcher httpPostMatcher = HTTP_POST_PATTERN.matcher(message);
        if (httpPostMatcher.find()) {
            String url = httpPostMatcher.group("url");
            if (pending != null) {
                pending.setEndpoint(url);
                pending.addRawLogLine(fullLine);
                captureService.onEntryUpdated(pending);
            }
            return;
        }

        // 3. Check for RestTemplate payload: "Writing [{variables={}, query=...}] as "application/json""
        // or standalone query/mutation
        if ((message.contains("Writing [") && message.contains("application/json")) ||
                (pending == null && (message.contains("query=") || message.contains("mutation=") ||
                        (message.startsWith("{") && (message.contains("\"query\"") || message.contains("\"mutation\"")))))) {
            if (pending != null) {
                pending.addRawLogLine(fullLine);
                parseWritingJsonPayload(pending, message);
                captureService.onEntryUpdated(pending);
            } else if (message.contains("query=") || message.contains("mutation=") ||
                    (message.startsWith("{") && (message.contains("\"query\"") || message.contains("\"mutation\"")))) {
                // Standalone query detected without preceding "Execute GraphQL query"
                MeshRequestEntry existing = captureService.findEntryByTimestamp(timestampStr);
                if (existing != null) {
                    if (traceId != null) pendingByTrace.put(traceId, existing);
                    if (thread != null) pendingByThread.put(thread, existing);
                    return;
                }

                MeshRequestEntry entry = new MeshRequestEntry(sequenceCounter.incrementAndGet(), timestampStr, epochMillis);
                entry.setAppName(app);
                entry.setTraceId(traceId);
                entry.setSpanId(spanId);
                entry.setPid(pid);
                entry.setThreadName(thread);
                entry.setUserId(userId);
                entry.setLogLevel(level);
                entry.addRawLogLine(fullLine);

                parseWritingJsonPayload(entry, message);
                if (traceId != null) pendingByTrace.put(traceId, entry);
                if (thread != null) pendingByThread.put(thread, entry);

                captureService.onEntryCreated(entry);
            }
            return;
        }

        // 4. Check for RestTemplate Response: "Response 200 OK"
        Matcher httpRespMatcher = HTTP_RESPONSE_PATTERN.matcher(message);
        if (httpRespMatcher.find()) {
            if (pending != null) {
                String code = httpRespMatcher.group("code");
                String text = httpRespMatcher.group("text");
                String httpStatus = code + " " + text;
                pending.setHttpStatus(httpStatus);
                pending.addRawLogLine(fullLine);

                long duration = epochMillis - pending.getEpochMillis();
                if (duration >= 0) {
                    pending.setDurationMs(duration);
                }

                if (pending.getFormattedQuery() == null && pending.getRawQuery() != null) {
                    pending.setFormattedQuery(GraphQLFormatter.format(pending.getRawQuery()));
                    if (pending.getOperationName() == null) {
                        pending.setOperationName(GraphQLFormatter.extractOperationName(pending.getRawQuery()));
                    }
                    if (pending.getRootField() == null) {
                        pending.setRootField(GraphQLFormatter.extractRootField(pending.getRawQuery()));
                    }
                }

                if (code.startsWith("5")) {
                    pending.setStatus(MeshRequestStatus.ERROR);
                    pending.setErrorType(configswitcher.mesh.model.MeshErrorType.HTTP_5XX);
                    pending.setErrorCode(code);
                    pending.setRootCauseMessage("HTTP Server Error: " + httpStatus);
                    pending.setErrorMessage("HTTP Error: " + httpStatus);
                    pending.setLogLevel("ERROR");
                } else if (code.startsWith("4")) {
                    pending.setStatus(MeshRequestStatus.ERROR);
                    pending.setErrorType(configswitcher.mesh.model.MeshErrorType.HTTP_4XX);
                    pending.setErrorCode(code);
                    pending.setRootCauseMessage("HTTP Client Error: " + httpStatus);
                    pending.setErrorMessage("HTTP Error: " + httpStatus);
                    if (!"ERROR".equalsIgnoreCase(pending.getLogLevel())) {
                        pending.setLogLevel("WARN");
                    }
                } else if (code.startsWith("2")) {
                    if (pending.getStatus() == MeshRequestStatus.PENDING) {
                        pending.setStatus(MeshRequestStatus.SUCCESS);
                    }
                }
                captureService.onEntryUpdated(pending);
            }
            return;
        }

        // 5. Check for Query Result: "Query result: ..."
        int qrIdx = message.indexOf("Query result:");
        if (qrIdx >= 0) {
            String resultText = message.substring(qrIdx + "Query result:".length()).trim();
            if (pending != null) {
                pending.addRawLogLine(fullLine);
                pending.setRawResponse(resultText);

                long duration = epochMillis - pending.getEpochMillis();
                if (duration >= 0) {
                    pending.setDurationMs(duration);
                }

                // Format JSON response
                String prettyJson = DataSetJsonConverter.toPrettyJson(resultText);
                pending.setFormattedResponse(prettyJson);

                // Format GraphQL query if not yet done
                if (pending.getRawQuery() != null) {
                    pending.setFormattedQuery(GraphQLFormatter.format(pending.getRawQuery()));
                    if (pending.getOperationName() == null) {
                        pending.setOperationName(GraphQLFormatter.extractOperationName(pending.getRawQuery()));
                    }
                    if (pending.getRootField() == null) {
                        pending.setRootField(GraphQLFormatter.extractRootField(pending.getRawQuery()));
                    }
                }

                // Status determination
                if (resultText.contains("\"errors\"") || resultText.contains("errors=[{") || resultText.contains("errors={")) {
                    pending.setStatus(MeshRequestStatus.ERROR);
                    extractAndClassifyGraphQLErrors(pending, resultText);
                } else if (pending.getStatus() == MeshRequestStatus.PENDING) {
                    pending.setStatus(MeshRequestStatus.SUCCESS);
                    if (pending.getHttpStatus() == null) {
                        pending.setHttpStatus("200 OK");
                    }
                }

                cleanupPendingEntry(pending);
                captureService.onEntryUpdated(pending);
            }
            return;
        }

        // 6. Check for Exception / Error lines
        if (level.equalsIgnoreCase("ERROR") || exceptionStr != null || message.contains("GraphQlException") || message.contains("RestClientResponseException")
                || message.contains("SocketTimeoutException") || message.contains("ConnectException") || message.contains("Connection refused")) {
            if (pending != null) {
                pending.addRawLogLine(fullLine);
                pending.setStatus(MeshRequestStatus.ERROR);
                pending.setLogLevel("ERROR");
                if (exceptionStr != null) {
                    pending.setRawStackTrace(exceptionStr);
                }
                String errSource = exceptionStr != null ? (message + "\n" + exceptionStr) : message;
                classifyException(pending, errSource, fullLine);
                captureService.onEntryUpdated(pending);
            } else {
                // Standalone error log (Tomcat servlet error, Spring Boot exception, unhandled backend exception, etc.)
                MeshRequestEntry existing = captureService.findEntryByTimestamp(timestampStr);
                if (existing != null) {
                    if (traceId != null) pendingByTrace.put(traceId, existing);
                    if (thread != null) pendingByThread.put(thread, existing);
                    return;
                }

                MeshRequestEntry entry = new MeshRequestEntry(sequenceCounter.incrementAndGet(), timestampStr, epochMillis);
                entry.setAppName(app);
                entry.setTraceId(traceId);
                entry.setSpanId(spanId);
                entry.setPid(pid);
                entry.setThreadName(thread);
                entry.setUserId(userId);
                entry.setLogLevel("ERROR");
                entry.setStatus(MeshRequestStatus.ERROR);
                entry.setOperationType(MeshOperationType.UNKNOWN);

                String simpleLogger = extractSimpleLogger(logger);
                entry.setOperationName(simpleLogger != null ? simpleLogger : "Backend Error");
                entry.setRootField(extractFirstLine(message));
                entry.addRawLogLine(fullLine);

                if (exceptionStr != null) {
                    entry.setRawStackTrace(exceptionStr);
                }
                String errSource = exceptionStr != null ? (message + "\n" + exceptionStr) : message;
                classifyException(entry, errSource, fullLine);

                if (traceId != null) pendingByTrace.put(traceId, entry);
                if (thread != null) pendingByThread.put(thread, entry);

                captureService.onEntryCreated(entry);
            }
            return;
        }

        // 7. Check for Warning lines
        if (level.equalsIgnoreCase("WARN") || level.equalsIgnoreCase("WARNING")) {
            if (pending != null && pending.getStatus() != MeshRequestStatus.ERROR) {
                pending.addRawLogLine(fullLine);
                pending.setLogLevel("WARN");
                captureService.onEntryUpdated(pending);
            }
        }
    }

    public static void extractAndClassifyGraphQLErrors(@NotNull MeshRequestEntry entry, @NotNull String rawResult) {
        String msg = null;
        String code = null;
        String path = null;

        Matcher jsonMsg = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"").matcher(rawResult);
        if (jsonMsg.find()) {
            msg = jsonMsg.group(1).trim();
        } else {
            Matcher toStringMsg = Pattern.compile("message=(.+?)(?:,\\s*[a-zA-Z0-9_]+=|\\}\\]|\\})").matcher(rawResult);
            if (toStringMsg.find()) {
                msg = toStringMsg.group(1).trim();
            }
        }

        Matcher jsonCode = Pattern.compile("\"(?:code|classification)\"\\s*:\\s*\"([^\"]+)\"").matcher(rawResult);
        if (jsonCode.find()) {
            code = jsonCode.group(1).trim();
        } else {
            Matcher toStringCode = Pattern.compile("(?:code|classification)=([a-zA-Z0-9_\\-]+)").matcher(rawResult);
            if (toStringCode.find()) {
                code = toStringCode.group(1).trim();
            }
        }

        Matcher pathMatcher = Pattern.compile("(?i)[\"']?path[\"']?\\s*[:=]\\s*\\[([^\\]]+)\\]").matcher(rawResult);
        if (pathMatcher.find()) {
            String rawPath = pathMatcher.group(1).replace("\"", "").replace("'", "").trim();
            String[] parts = rawPath.split("\\s*,\\s*");
            path = String.join(" / ", parts);
        }

        if (msg == null || msg.isBlank()) {
            msg = "GraphQL response returned error status";
        }

        entry.setRootCauseMessage(msg);
        entry.setErrorCode(code != null ? code : "GRAPHQL_ERROR");
        entry.setErrorPath(path);
        entry.setErrorMessage("GraphQL response contains errors: " + msg);

        if ((code != null && (code.contains("VALIDATION") || code.contains("SYNTAX") || code.contains("BAD_USER_INPUT")))
                || msg.toLowerCase().contains("validation")
                || msg.toLowerCase().contains("syntax")
                || msg.toLowerCase().contains("cannot query field")) {
            entry.setErrorType(configswitcher.mesh.model.MeshErrorType.GRAPHQL_VALIDATION_ERROR);
        } else {
            entry.setErrorType(configswitcher.mesh.model.MeshErrorType.GRAPHQL_SERVER_ERROR);
        }
    }

    public static void classifyException(@NotNull MeshRequestEntry entry, @NotNull String message, @NotNull String fullLine) {
        String rootCause = extractRootCauseMessage(message);
        String exceptionName = extractExceptionName(message);

        String combined = (message + " " + fullLine).toLowerCase();

        if (combined.contains("timeout") || combined.contains("timed out") || combined.contains("connectexception")
                || combined.contains("connection refused") || combined.contains("unavailable") || combined.contains("getsockopt")) {
            entry.setErrorType(configswitcher.mesh.model.MeshErrorType.NETWORK_TIMEOUT);
            entry.setErrorCode("TIMEOUT");
            entry.setRootCauseMessage(rootCause);
            entry.setErrorMessage(extractFirstLine(message));
        } else if (combined.contains("401") || combined.contains("unauthorized") || combined.contains("403") || combined.contains("forbidden")) {
            entry.setErrorType(configswitcher.mesh.model.MeshErrorType.HTTP_4XX);
            entry.setErrorCode(combined.contains("401") ? "401" : (combined.contains("403") ? "403" : "4XX"));
            entry.setRootCauseMessage(rootCause);
            entry.setErrorMessage(extractFirstLine(message));
        } else if (combined.contains("500") || combined.contains("502") || combined.contains("503") || combined.contains("504")) {
            entry.setErrorType(configswitcher.mesh.model.MeshErrorType.HTTP_5XX);
            entry.setErrorCode(combined.contains("500") ? "500" : (combined.contains("503") ? "503" : "5XX"));
            entry.setRootCauseMessage(rootCause);
            entry.setErrorMessage(extractFirstLine(message));
        } else {
            entry.setErrorType(configswitcher.mesh.model.MeshErrorType.APP_EXCEPTION);
            entry.setErrorCode(exceptionName != null ? exceptionName : "APP_EXCEPTION");
            entry.setRootCauseMessage(rootCause);
            entry.setErrorMessage(extractFirstLine(message));
        }

        if (entry.getRawStackTrace() == null || entry.getRawStackTrace().isBlank()) {
            if (fullLine.contains("\tat ") || message.contains("\tat ")) {
                entry.setRawStackTrace(fullLine.contains("\tat ") ? fullLine : message);
            }
        }
    }

    public static @NotNull String extractRootCauseMessage(@NotNull String text) {
        int lastCausedBy = text.lastIndexOf("Caused by:");
        if (lastCausedBy >= 0) {
            String tail = text.substring(lastCausedBy + "Caused by:".length()).trim();
            int nextNewline = tail.indexOf('\n');
            String line = nextNewline >= 0 ? tail.substring(0, nextNewline).trim() : tail.trim();
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (!line.isEmpty()) {
                return line;
            }
        }
        return extractFirstLine(text);
    }

    public static @Nullable String extractExceptionName(@NotNull String text) {
        int lastCausedBy = text.lastIndexOf("Caused by:");
        if (lastCausedBy >= 0) {
            String tail = text.substring(lastCausedBy);
            Matcher m = Pattern.compile("([A-Za-z0-9_]+(?:Exception|Error))").matcher(tail);
            if (m.find()) {
                return m.group(1);
            }
        }
        Matcher m = Pattern.compile("([A-Za-z0-9_]+(?:Exception|Error))").matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    public static @Nullable String extractSimpleLogger(@Nullable String logger) {
        if (logger == null || logger.isBlank()) return null;
        String s = logger.trim();
        Matcher m = Pattern.compile("\\[([^\\]]+)\\]$").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        int lastDot = Math.max(s.lastIndexOf('.'), s.lastIndexOf('$'));
        if (lastDot >= 0 && lastDot < s.length() - 1) {
            String sub = s.substring(lastDot + 1).trim();
            sub = sub.replaceAll("[\\[\\]]", "");
            if (!sub.isEmpty()) return sub;
        }
        return s;
    }

    private static @NotNull String extractFirstLine(@NotNull String msg) {
        int idx = msg.indexOf('\n');
        return idx >= 0 ? msg.substring(0, idx).trim() : msg.trim();
    }

    private void handleContinuationLine(@NotNull String line) {
        // Multi-line continuation: find active query across threads
        if (activeQueryByThread.size() == 1) {
            MeshRequestEntry entry = activeQueryByThread.values().iterator().next();
            appendQueryLine(entry, line);
        } else if (!activeQueryByThread.isEmpty()) {
            MeshRequestEntry entry = activeQueryByThread.values().iterator().next();
            appendQueryLine(entry, line);
        } else if (!pendingByThread.isEmpty()) {
            MeshRequestEntry entry = pendingByThread.values().iterator().next();
            if (entry != null && entry.hasError() && (line.startsWith("\tat ") || line.startsWith("Caused by:") || line.startsWith("\t... "))) {
                String current = entry.getRawStackTrace();
                entry.setRawStackTrace(current != null ? current + "\n" + line : line);
                if (line.startsWith("Caused by:")) {
                    classifyException(entry, entry.getRawStackTrace(), line);
                    captureService.onEntryUpdated(entry);
                }
            }
        }
    }

    private void appendQueryLine(@NotNull MeshRequestEntry entry, @NotNull String line) {
        entry.addRawLogLine(line);
        String current = entry.getRawQuery();
        entry.setRawQuery(current == null || current.isEmpty() ? line : current + "\n" + line);

        if (entry.getOperationName() == null) {
            entry.setOperationName(GraphQLFormatter.extractOperationName(entry.getRawQuery()));
        }
        if (entry.getRootField() == null) {
            entry.setRootField(GraphQLFormatter.extractRootField(entry.getRawQuery()));
        }
        entry.setOperationType(MeshOperationType.fromQueryText(entry.getRawQuery()));
    }

    private void parseWritingJsonPayload(@NotNull MeshRequestEntry entry, @NotNull String message) {
        // Native JSON payload support: {"query": "...", "variables": ...}
        if (message.startsWith("{") && (message.contains("\"query\"") || message.contains("\"mutation\""))) {
            try {
                JsonElement elem = JsonParser.parseString(message);
                if (elem.isJsonObject()) {
                    JsonObject o = elem.getAsJsonObject();
                    if (o.has("variables") && !o.get("variables").isJsonNull()) {
                        String vars = o.get("variables").toString();
                        entry.setRawVariables(vars);
                        entry.setFormattedVariables(DataSetJsonConverter.toPrettyJson(vars));
                    }
                    if (o.has("query") && !o.get("query").isJsonNull()) {
                        String q = o.get("query").getAsString();
                        entry.setRawQuery(q);
                        entry.setFormattedQuery(GraphQLFormatter.format(q));
                        entry.setOperationType(MeshOperationType.fromQueryText(q));
                        entry.setOperationName(GraphQLFormatter.extractOperationName(q));
                        entry.setRootField(GraphQLFormatter.extractRootField(q));
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }

        // e.g. Writing [{variables={}, query=query GetUserInfoWithRoles { ... }}] as "application/json"
        Matcher matcher = WRITING_JSON_PATTERN.matcher(message);
        if (matcher.find()) {
            String vars = matcher.group("vars");
            if (vars != null && !vars.isBlank()) {
                entry.setRawVariables(vars);
                entry.setFormattedVariables(DataSetJsonConverter.toPrettyJson(vars));
            }
            String query = matcher.group("query");
            if (query != null && !query.isBlank()) {
                if (entry.getRawQuery() == null || entry.getRawQuery().isBlank()) {
                    entry.setRawQuery(query.trim());
                    entry.setFormattedQuery(GraphQLFormatter.format(query.trim()));
                    entry.setOperationType(MeshOperationType.fromQueryText(query));
                    entry.setOperationName(GraphQLFormatter.extractOperationName(query));
                    entry.setRootField(GraphQLFormatter.extractRootField(query));
                }
            }
        } else {
            // Variables extraction fallback
            int vIdx = message.indexOf("variables=");
            if (vIdx >= 0) {
                int vStart = vIdx + 10;
                int vEnd = message.indexOf(", query=", vStart);
                if (vEnd < 0) vEnd = message.indexOf(",query=", vStart);
                if (vEnd > vStart) {
                    String vars = message.substring(vStart, vEnd).trim();
                    entry.setRawVariables(vars);
                    entry.setFormattedVariables(DataSetJsonConverter.toPrettyJson(vars));
                }
            }

            // Fallback: look for query= or mutation=
            int qIdx = message.indexOf("query=");
            int mIdx = message.indexOf("mutation=");
            if (qIdx >= 0 || mIdx >= 0) {
                int start = qIdx >= 0 ? qIdx + 6 : mIdx + 9;
                int end = message.lastIndexOf("}] as \"application/json\"");
                if (end < start) {
                    if (message.endsWith("\"") || message.endsWith("}")) {
                        end = message.length();
                        if (message.endsWith("\"")) end--;
                        if (message.endsWith("}")) end--;
                    } else {
                        end = message.length();
                    }
                }
                String extracted = message.substring(start, end).trim();
                if (entry.getRawQuery() == null || entry.getRawQuery().isBlank()) {
                    entry.setRawQuery(extracted);
                    entry.setFormattedQuery(GraphQLFormatter.format(extracted));
                    entry.setOperationType(mIdx >= 0 ? MeshOperationType.MUTATION : MeshOperationType.QUERY);
                    entry.setOperationName(GraphQLFormatter.extractOperationName(extracted));
                    entry.setRootField(GraphQLFormatter.extractRootField(extracted));
                }
            }
        }
    }

    @Nullable
    private MeshRequestEntry findPendingEntry(@Nullable String traceId, @Nullable String thread) {
        if (traceId != null && !traceId.isBlank()) {
            MeshRequestEntry entry = pendingByTrace.get(traceId);
            if (entry != null) return entry;
        }
        if (thread != null && !thread.isBlank()) {
            return pendingByThread.get(thread);
        }
        return null;
    }

    private void cleanupPendingEntry(@NotNull MeshRequestEntry entry) {
        if (entry.getTraceId() != null) {
            pendingByTrace.remove(entry.getTraceId());
        }
        if (entry.getThreadName() != null) {
            pendingByThread.remove(entry.getThreadName());
            activeQueryByThread.remove(entry.getThreadName());
        }
    }

    private long parseEpochMillis(@NotNull String timestampStr) {
        try {
            if (timestampStr.contains("T")) {
                try {
                    return java.time.OffsetDateTime.parse(timestampStr).toInstant().toEpochMilli();
                } catch (Exception ignored) {
                    return java.time.LocalDateTime.parse(timestampStr).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                }
            }
            LocalDateTime ldt = LocalDateTime.parse(timestampStr, LOG_DATE_TIME_FORMATTER);
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private String formatDisplayTimestamp(@NotNull String timestampStr) {
        try {
            if (timestampStr.contains("T")) {
                try {
                    java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(timestampStr);
                    return odt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
                } catch (Exception ignored) {
                    LocalDateTime ldt = java.time.LocalDateTime.parse(timestampStr);
                    return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
                }
            }
        } catch (Exception ignored) {}
        return timestampStr;
    }

    public void clear() {
        pendingByTrace.clear();
        pendingByThread.clear();
        activeQueryByThread.clear();
        lineBuffer.setLength(0);
    }
}
