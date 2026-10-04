package configswitcher.util;

import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.util.Key;
import configswitcher.state.PluginSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters process stdout/stderr stream by log level (DEBUG, INFO, WARN, ERROR)
 * before emitting text to the IntelliJ Terminal Tool Window or ConsoleView,
 * and highlights error log messages and stack traces in ANSI bright red.
 * Supports Spring Boot text logs, JSON structured logs, bracketed logs, and preserves continuation lines
 * (such as multiline stack traces and formatted payloads).
 */
public class TerminalOutputFilter {

    public enum LogLevel {
        DEBUG, INFO, WARN, ERROR, NONE
    }

    public static final String ANSI_RED = "\u001B[91m";
    public static final String ANSI_RESET = "\u001B[0m";

    // Pattern 1: Standard Spring Boot / Logback text log
    // e.g. 2026-09-27 01:06:26.841 DEBUG [co,...] ...
    // e.g. 2026-09-27 01:06:26.848  INFO [co,...] ...
    private static final Pattern SPRING_TEXT_LOG_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}[\\.,]\\d{3}\\s+(TRACE|DEBUG|INFO|WARN|WARNING|ERROR)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern 2: JSON structured log
    // e.g. {"timestamp":"2026-09-27T01:40:28.016+03:00", ... "level":"DEBUG", ...}
    private static final Pattern JSON_LOG_LEVEL_PATTERN = Pattern.compile(
            "\"(?:level|severity)\"\\s*:\\s*\"(TRACE|DEBUG|INFO|WARN|WARNING|ERROR)\"",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern 3: Bracketed log level, e.g. [DEBUG] or [INFO]
    private static final Pattern BRACKETED_LOG_PATTERN = Pattern.compile(
            "^\\[(TRACE|DEBUG|INFO|WARN|WARNING|ERROR)\\]",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern 4: Standalone log level prefix, e.g. DEBUG: or INFO:
    private static final Pattern PREFIX_LOG_PATTERN = Pattern.compile(
            "^(TRACE|DEBUG|INFO|WARN|WARNING|ERROR):?\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern 5: Java Exception header, e.g. org.springframework.beans.factory.BeanCreationException: ...
    private static final Pattern EXCEPTION_LINE_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_$.]+(?:Exception|Error):\\s*"
    );

    private final Supplier<PluginSettingsState.State> settingsSupplier;
    private final StringBuilder stdoutBuffer = new StringBuilder();
    private final StringBuilder stderrBuffer = new StringBuilder();
    private boolean lastLineAllowed = true;
    private boolean lastLineWasError = false;
    private boolean hasSeenAnyLog = false;

    public TerminalOutputFilter(@NotNull Supplier<PluginSettingsState.State> settingsSupplier) {
        this.settingsSupplier = settingsSupplier;
    }

    public TerminalOutputFilter(boolean allowDebug, boolean allowInfo, boolean allowWarn, boolean allowError) {
        this(allowDebug, allowInfo, allowWarn, allowError, true, false);
    }

    public TerminalOutputFilter(boolean allowDebug, boolean allowInfo, boolean allowWarn, boolean allowError, boolean colorizeErrors) {
        this(allowDebug, allowInfo, allowWarn, allowError, colorizeErrors, false);
    }

    public TerminalOutputFilter(boolean allowDebug, boolean allowInfo, boolean allowWarn, boolean allowError, boolean colorizeErrors, boolean showErrorStackTrace) {
        PluginSettingsState.State s = new PluginSettingsState.State();
        s.terminalOutputDebug = allowDebug;
        s.terminalOutputInfo = allowInfo;
        s.terminalOutputWarn = allowWarn;
        s.terminalOutputError = allowError;
        s.colorizeErrorOutputInTerminal = colorizeErrors;
        s.terminalShowErrorStackTrace = showErrorStackTrace;
        this.settingsSupplier = () -> s;
    }

    /**
     * Paints text in ANSI Bright Red, ensuring any internal resets are properly re-applied.
     */
    public static @NotNull String paintRed(@NotNull String text) {
        if (text.isEmpty()) return text;
        String sanitized = text.replace(ANSI_RESET, ANSI_RESET + ANSI_RED);
        return ANSI_RED + sanitized + ANSI_RESET;
    }

    public boolean isColorizeErrorsEnabled() {
        return getSettings().colorizeErrorOutputInTerminal;
    }

    /**
     * Processes an incoming chunk of text from process stdout.
     */
    public synchronized void processChunk(@Nullable String chunk, @NotNull Consumer<String> lineConsumer) {
        processChunk(chunk, false, lineConsumer);
    }

    /**
     * Processes an incoming chunk of text specifying output type (stdout vs stderr).
     */
    public synchronized void processChunk(@Nullable String chunk, @NotNull Key outputType, @NotNull Consumer<String> lineConsumer) {
        boolean isStderr = ProcessOutputTypes.STDERR.equals(outputType);
        processChunk(chunk, isStderr, lineConsumer);
    }

    /**
     * Processes an incoming chunk of text from stdout or stderr.
     */
    public synchronized void processChunk(@Nullable String chunk, boolean isStderr, @NotNull Consumer<String> lineConsumer) {
        if (chunk == null || chunk.isEmpty()) return;

        PluginSettingsState.State settings = getSettings();
        StringBuilder targetBuffer = isStderr ? stderrBuffer : stdoutBuffer;

        // Fast-path: if colorization is disabled, not stderr, stack traces allowed, and all levels are enabled, pass through directly
        if (!settings.colorizeErrorOutputInTerminal && !isStderr &&
                settings.terminalShowErrorStackTrace &&
                settings.terminalOutputDebug && settings.terminalOutputInfo &&
                settings.terminalOutputWarn && settings.terminalOutputError && targetBuffer.length() == 0) {
            lineConsumer.accept(chunk);
            return;
        }

        targetBuffer.append(chunk);

        int newlineIndex;
        while ((newlineIndex = targetBuffer.indexOf("\n")) != -1) {
            String lineWithNewline = targetBuffer.substring(0, newlineIndex + 1);
            String rawLine = lineWithNewline.endsWith("\r\n")
                    ? lineWithNewline.substring(0, lineWithNewline.length() - 2)
                    : (lineWithNewline.endsWith("\n") ? lineWithNewline.substring(0, lineWithNewline.length() - 1) : lineWithNewline);
            String delimiter = lineWithNewline.substring(rawLine.length());

            targetBuffer.delete(0, newlineIndex + 1);

            boolean allowed = isLineAllowed(rawLine, isStderr);
            if (allowed) {
                if (shouldColorizeRed(rawLine, isStderr)) {
                    lineConsumer.accept(paintRed(rawLine) + delimiter);
                } else {
                    lineConsumer.accept(lineWithNewline);
                }
            }
        }
    }

    /**
     * Flushes any remaining buffered text upon process completion for both stdout and stderr.
     */
    public synchronized void flushRemaining(@NotNull Consumer<String> lineConsumer) {
        flushRemaining(false, lineConsumer);
        flushRemaining(true, lineConsumer);
    }

    public synchronized void flushRemaining(@NotNull Key outputType, @NotNull Consumer<String> lineConsumer) {
        boolean isStderr = ProcessOutputTypes.STDERR.equals(outputType);
        flushRemaining(isStderr, lineConsumer);
    }

    public synchronized void flushRemaining(boolean isStderr, @NotNull Consumer<String> lineConsumer) {
        StringBuilder targetBuffer = isStderr ? stderrBuffer : stdoutBuffer;
        if (targetBuffer.length() > 0) {
            String remaining = targetBuffer.toString();
            targetBuffer.setLength(0);
            if (isLineAllowed(remaining, isStderr)) {
                if (shouldColorizeRed(remaining, isStderr)) {
                    lineConsumer.accept(paintRed(remaining));
                } else {
                    lineConsumer.accept(remaining);
                }
            }
        }
    }

    /**
     * Determines whether a given line is allowed based on detected log level and settings.
     */
    public synchronized boolean isLineAllowed(@NotNull String line) {
        return isLineAllowed(line, false);
    }

    public synchronized boolean isLineAllowed(@NotNull String line, boolean isStderr) {
        PluginSettingsState.State settings = getSettings();

        // If error call stack hiding is active, filter out any stack trace line immediately (stdout or stderr)
        if (!settings.terminalShowErrorStackTrace && isStackTraceLine(line)) {
            return false;
        }

        if (isStderr) {
            lastLineWasError = true;
            return settings.terminalOutputError;
        }

        LogLevel level = detectLogLevel(line);

        if (level != LogLevel.NONE) {
            hasSeenAnyLog = true;
            boolean allowed = isLevelEnabled(level);
            lastLineAllowed = allowed;
            lastLineWasError = (level == LogLevel.ERROR);
            return allowed;
        }

        if (isErrorLine(line)) {
            lastLineWasError = true;
            lastLineAllowed = settings.terminalOutputError;
            return lastLineAllowed;
        }

        // Check if this line is a continuation line (stack trace, multiline sql/json, indented text)
        if (isContinuationLine(line)) {
            return lastLineAllowed;
        }

        // Non-log line (system banners, build commands, ASCII art, etc.)
        lastLineAllowed = true;
        lastLineWasError = false;
        return true;
    }

    private synchronized boolean shouldColorizeRed(@NotNull String rawLine, boolean isStderr) {
        if (!getSettings().colorizeErrorOutputInTerminal) {
            return false;
        }
        if (rawLine.isBlank()) {
            return false;
        }
        if (isStderr) {
            return true;
        }
        LogLevel level = detectLogLevel(rawLine);
        if (level == LogLevel.ERROR) {
            return true;
        }
        if (level != LogLevel.NONE) {
            return false;
        }
        if (isErrorLine(rawLine)) {
            return true;
        }
        if (isContinuationLine(rawLine)) {
            return lastLineWasError;
        }
        return false;
    }

    public static @NotNull LogLevel detectLogLevel(@NotNull String line) {
        if (line.isEmpty()) return LogLevel.NONE;

        // 1. Spring Boot Text Log
        Matcher m1 = SPRING_TEXT_LOG_PATTERN.matcher(line);
        if (m1.find()) {
            return parseLevel(m1.group(1));
        }

        // 2. JSON structured log
        if (line.startsWith("{") || line.contains("\"level\"")) {
            Matcher m2 = JSON_LOG_LEVEL_PATTERN.matcher(line);
            if (m2.find()) {
                return parseLevel(m2.group(1));
            }
        }

        // 3. Bracketed log
        Matcher m3 = BRACKETED_LOG_PATTERN.matcher(line);
        if (m3.find()) {
            return parseLevel(m3.group(1));
        }

        // 4. Standalone prefix
        Matcher m4 = PREFIX_LOG_PATTERN.matcher(line);
        if (m4.find()) {
            return parseLevel(m4.group(1));
        }

        return LogLevel.NONE;
    }

    public static boolean isErrorLine(@NotNull String line) {
        if (line.isEmpty()) return false;
        LogLevel level = detectLogLevel(line);
        if (level == LogLevel.ERROR) {
            return true;
        }
        if (level != LogLevel.NONE) {
            return false; // INFO, WARN, DEBUG
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return false;
        String upper = trimmed.toUpperCase();
        return upper.contains("APPLICATION FAILED TO START") ||
                upper.contains("EXCEPTION IN THREAD ") ||
                upper.startsWith("ERROR:") ||
                upper.startsWith("[ERROR]") ||
                upper.startsWith("[FATAL]") ||
                trimmed.startsWith("Caused by:") ||
                EXCEPTION_LINE_PATTERN.matcher(trimmed).find();
    }

    /**
     * Determines whether the given line represents a call stack / stack trace frame.
     */
    public static boolean isStackTraceLine(@NotNull String line) {
        if (line.isEmpty()) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return false;

        // Strip common log prefixes if present (e.g. Maven [ERROR] or [FATAL])
        if (trimmed.startsWith("[ERROR]") || trimmed.startsWith("[FATAL]") || trimmed.startsWith("[WARN]")) {
            trimmed = trimmed.substring(7).trim();
        }

        // 1. "at package.Class.method(Source:123)"
        if (trimmed.startsWith("at ")) {
            int openIdx = trimmed.indexOf('(');
            if (openIdx > 3 && trimmed.endsWith(")")) {
                String target = trimmed.substring(3, openIdx).trim();
                if ((target.contains(".") || target.contains("/")) && !target.contains(" ")) {
                    return true;
                }
            }
        }

        // 2. "... 45 more" or "... 16 common frames omitted"
        if (trimmed.startsWith("... ") && (trimmed.endsWith("more") || trimmed.endsWith("common frames omitted"))) {
            return true;
        }

        // 3. "[CIRCULAR REFERENCE:...]"
        if (trimmed.startsWith("[CIRCULAR REFERENCE:") && trimmed.endsWith("]")) {
            return true;
        }

        // 4. Thread dump / lock synchronizers
        if (trimmed.startsWith("- locked <") || trimmed.startsWith("- waiting on <") ||
                trimmed.startsWith("- parking to wait for <") || trimmed.startsWith("- eliminated <")) {
            return true;
        }

        return false;
    }

    private boolean isContinuationLine(@NotNull String line) {
        if (line.isEmpty()) return true;
        char firstChar = line.charAt(0);
        if (firstChar == ' ' || firstChar == '\t') {
            return true;
        }
        if (line.startsWith("Caused by:") || line.startsWith("... ") || line.startsWith("Suppressed:")) {
            return true;
        }
        return false;
    }

    private static @NotNull LogLevel parseLevel(@NotNull String levelStr) {
        String upper = levelStr.toUpperCase();
        return switch (upper) {
            case "TRACE", "DEBUG" -> LogLevel.DEBUG;
            case "INFO" -> LogLevel.INFO;
            case "WARN", "WARNING" -> LogLevel.WARN;
            case "ERROR", "FATAL" -> LogLevel.ERROR;
            default -> LogLevel.NONE;
        };
    }

    private boolean isLevelEnabled(@NotNull LogLevel level) {
        PluginSettingsState.State settings = getSettings();
        return switch (level) {
            case DEBUG -> settings.terminalOutputDebug;
            case INFO -> settings.terminalOutputInfo;
            case WARN -> settings.terminalOutputWarn;
            case ERROR -> settings.terminalOutputError;
            case NONE -> true;
        };
    }

    private @NotNull PluginSettingsState.State getSettings() {
        PluginSettingsState.State s = settingsSupplier.get();
        return s != null ? s : new PluginSettingsState.State();
    }
}
