package configswitcher.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TerminalOutputFilterTest {

    @Test
    public void testDetectLogLevels() {
        String springDebug = "2026-09-27 01:06:26.841 DEBUG [co,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] SomeResolver : Found key";
        String springInfo = "2026-09-27 01:06:26.848  INFO [co,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] Engine : Execute GraphQL";
        String springWarn = "2026-09-27 01:06:26.850  WARN [co,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] Engine : Slow query";
        String springError = "2026-09-27 01:06:26.855 ERROR [co,c1ff8fe5b6cd26a2] 8024 --- [io-50906-exec-9] Engine : Query failed";

        assertEquals(TerminalOutputFilter.LogLevel.DEBUG, TerminalOutputFilter.detectLogLevel(springDebug));
        assertEquals(TerminalOutputFilter.LogLevel.INFO, TerminalOutputFilter.detectLogLevel(springInfo));
        assertEquals(TerminalOutputFilter.LogLevel.WARN, TerminalOutputFilter.detectLogLevel(springWarn));
        assertEquals(TerminalOutputFilter.LogLevel.ERROR, TerminalOutputFilter.detectLogLevel(springError));

        String jsonDebug = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"level\":\"DEBUG\",\"message\":\"Found key\"}";
        String jsonInfo = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"level\":\"INFO\",\"message\":\"Started\"}";
        String jsonWarn = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"level\":\"WARN\",\"message\":\"Warning\"}";
        String jsonError = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"level\":\"ERROR\",\"message\":\"Error\"}";

        assertEquals(TerminalOutputFilter.LogLevel.DEBUG, TerminalOutputFilter.detectLogLevel(jsonDebug));
        assertEquals(TerminalOutputFilter.LogLevel.INFO, TerminalOutputFilter.detectLogLevel(jsonInfo));
        assertEquals(TerminalOutputFilter.LogLevel.WARN, TerminalOutputFilter.detectLogLevel(jsonWarn));
        assertEquals(TerminalOutputFilter.LogLevel.ERROR, TerminalOutputFilter.detectLogLevel(jsonError));

        String nonLogBanner = "  .   ____          _            __ _ _";
        String nonLogBuild = "[ConfigSwitcher] Launching Application: java -jar app.jar";

        assertEquals(TerminalOutputFilter.LogLevel.NONE, TerminalOutputFilter.detectLogLevel(nonLogBanner));
        assertEquals(TerminalOutputFilter.LogLevel.NONE, TerminalOutputFilter.detectLogLevel(nonLogBuild));
    }

    @Test
    public void testFilteringWhenDebugDisabled() {
        // DEBUG disabled, INFO/WARN/ERROR enabled
        TerminalOutputFilter filter = new TerminalOutputFilter(false, true, true, true);
        List<String> output = new ArrayList<>();

        String debugLog = "2026-09-27 01:06:26.841 DEBUG [co] Resolver : debug details\n";
        String infoLog = "2026-09-27 01:06:26.848  INFO [co] Engine : starting app\n";
        String warnLog = "2026-09-27 01:06:26.850  WARN [co] Engine : deprecated feature\n";
        String errorLog = "2026-09-27 01:06:26.855 ERROR [co] Engine : failed\n";

        filter.processChunk(debugLog, output::add);
        filter.processChunk(infoLog, output::add);
        filter.processChunk(warnLog, output::add);
        filter.processChunk(errorLog, output::add);

        assertEquals(3, output.size());
        assertTrue(output.get(0).contains("INFO"));
        assertTrue(output.get(1).contains("WARN"));
        assertTrue(output.get(2).contains("ERROR"));
    }

    @Test
    public void testFilteringJsonLogs() {
        // Only ERROR enabled
        TerminalOutputFilter filter = new TerminalOutputFilter(false, false, false, true);
        List<String> output = new ArrayList<>();

        String jsonDebug = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"level\":\"DEBUG\",\"message\":\"test debug\"}\n";
        String jsonInfo = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"level\":\"INFO\",\"message\":\"test info\"}\n";
        String jsonError = "{\"timestamp\":\"2026-09-27T01:40:28.016+03:00\",\"level\":\"ERROR\",\"message\":\"test error\"}\n";

        filter.processChunk(jsonDebug, output::add);
        filter.processChunk(jsonInfo, output::add);
        filter.processChunk(jsonError, output::add);

        assertEquals(1, output.size());
        assertTrue(output.get(0).contains("test error"));
    }

    @Test
    public void testContinuationLinesHandling() {
        // DEBUG disabled, ERROR enabled
        TerminalOutputFilter filter = new TerminalOutputFilter(false, true, true, true);
        List<String> output = new ArrayList<>();

        // 1. Debug with continuation line (should both be suppressed)
        filter.processChunk("2026-09-27 01:06:26.841 DEBUG [co] Query:\n", output::add);
        filter.processChunk("\tselect * from table\n", output::add);

        assertTrue(output.isEmpty(), "Debug and its continuation line should be suppressed");

        // 2. Error with stack trace when stack traces disabled (default)
        filter.processChunk("2026-09-27 01:06:26.855 ERROR [co] NullPointer\n", output::add);
        filter.processChunk("\tat org.example.Service.run(Service.java:10)\n", output::add);
        filter.processChunk("Caused by: java.lang.Exception: root cause\n", output::add);

        assertEquals(2, output.size());
        assertTrue(output.get(0).contains("ERROR"));
        assertTrue(output.get(1).contains("Caused by"));

        // 3. Error with stack trace when stack traces enabled
        TerminalOutputFilter filterWithStack = new TerminalOutputFilter(false, true, true, true, true, true);
        List<String> outputWithStack = new ArrayList<>();
        filterWithStack.processChunk("2026-09-27 01:06:26.855 ERROR [co] NullPointer\n", outputWithStack::add);
        filterWithStack.processChunk("\tat org.example.Service.run(Service.java:10)\n", outputWithStack::add);
        filterWithStack.processChunk("Caused by: java.lang.Exception: root cause\n", outputWithStack::add);

        assertEquals(3, outputWithStack.size());
        assertTrue(outputWithStack.get(0).contains("ERROR"));
        assertTrue(outputWithStack.get(1).contains("at org.example"));
        assertTrue(outputWithStack.get(2).contains("Caused by"));
    }

    @Test
    public void testNonLogLinesPassThrough() {
        // All disabled except ERROR
        TerminalOutputFilter filter = new TerminalOutputFilter(false, false, false, true);
        List<String> output = new ArrayList<>();

        filter.processChunk("[ConfigSwitcher] Running Pre-Run Build: mvn clean\n", output::add);
        filter.processChunk("BUILD SUCCESSFUL\n", output::add);

        assertEquals(2, output.size());
        assertTrue(output.get(0).contains("Running Pre-Run Build"));
        assertTrue(output.get(1).contains("BUILD SUCCESSFUL"));
    }

    @Test
    public void testErrorColorizationInTerminal() {
        // colorizeErrors = true, showErrorStackTrace = true
        TerminalOutputFilter filter = new TerminalOutputFilter(true, true, true, true, true, true);
        List<String> output = new ArrayList<>();

        String infoLog = "2026-09-28 01:00:00.000 INFO [main] Normal startup log\n";
        String errorLog = "2026-09-28 01:00:01.000 ERROR [main] BeanCreationException: Error creating bean\n";
        String stackTrace1 = "\tat org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:325)\n";
        String causedBy = "Caused by: java.lang.NullPointerException: database URL missing\n";
        String nextInfoLog = "2026-09-28 01:00:02.000 INFO [main] Recovery attempt completed\n";

        filter.processChunk(infoLog, output::add);
        filter.processChunk(errorLog, output::add);
        filter.processChunk(stackTrace1, output::add);
        filter.processChunk(causedBy, output::add);
        filter.processChunk(nextInfoLog, output::add);

        assertEquals(5, output.size());
        // INFO should not contain ANSI red
        assertFalse(output.get(0).contains(TerminalOutputFilter.ANSI_RED));
        // ERROR and continuation stack trace lines should contain ANSI red
        assertTrue(output.get(1).contains(TerminalOutputFilter.ANSI_RED));
        assertTrue(output.get(1).endsWith(TerminalOutputFilter.ANSI_RESET + "\n"));
        assertTrue(output.get(2).contains(TerminalOutputFilter.ANSI_RED));
        assertTrue(output.get(2).endsWith(TerminalOutputFilter.ANSI_RESET + "\n"));
        assertTrue(output.get(3).contains(TerminalOutputFilter.ANSI_RED));
        assertTrue(output.get(3).endsWith(TerminalOutputFilter.ANSI_RESET + "\n"));
        // Following INFO should revert back to normal (no ANSI red)
        assertFalse(output.get(4).contains(TerminalOutputFilter.ANSI_RED));
    }

    @Test
    public void testIsStackTraceLine() {
        // Valid Java stack trace lines
        assertTrue(TerminalOutputFilter.isStackTraceLine("\tat org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:325)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("    at java.base/java.lang.Thread.run(Thread.java:833)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("    at java.base@21.0.2/java.lang.Thread.run(Thread.java:833)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("at ru.gov.pfr.ecp.fo.ko.FoKoApplication.main(FoKoApplication.java:35)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("\tat com.example.MyService.<init>(MyService.java:20)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("\tat com.example.MyService.lambda$init$0(MyService.java:25)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("\tat com.example.Native.call(Native Method)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("\tat com.example.Unknown.call(Unknown Source)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("\tat app//com.example.Service.run(Service.java:10)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("\t... 45 more"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("    ... 16 common frames omitted"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("\t[CIRCULAR REFERENCE: java.lang.Exception]"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("\t- locked <0x0000000712345678> (a java.lang.Object)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("[ERROR]     at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:215)"));
        assertTrue(TerminalOutputFilter.isStackTraceLine("[ERROR]     ... 25 more"));

        // Non-stack lines that must NOT be detected as stack trace
        assertFalse(TerminalOutputFilter.isStackTraceLine("2026-09-28 01:00:00.000 ERROR [main] BeanCreationException: Error creating bean"));
        assertFalse(TerminalOutputFilter.isStackTraceLine("org.springframework.beans.factory.BeanCreationException: Error creating bean with name dataSource"));
        assertFalse(TerminalOutputFilter.isStackTraceLine("Caused by: java.lang.NullPointerException: database URL missing"));
        assertFalse(TerminalOutputFilter.isStackTraceLine("[ERROR] Caused by: org.apache.maven.plugin.MojoExecutionException: Compilation failure"));
        assertFalse(TerminalOutputFilter.isStackTraceLine("[ERROR] /path/to/File.java:[10,5] cannot find symbol"));
        assertFalse(TerminalOutputFilter.isStackTraceLine("Application run failed"));
        assertFalse(TerminalOutputFilter.isStackTraceLine("[ConfigSwitcher] Running Pre-Run Build: mvn clean compile"));
        assertFalse(TerminalOutputFilter.isStackTraceLine("2026-09-28 01:00:01.000 INFO [main] Tomcat started on port(s): 8080 (http)"));
        assertFalse(TerminalOutputFilter.isStackTraceLine("at home (yesterday)"));
        assertFalse(TerminalOutputFilter.isStackTraceLine(""));
    }

    @Test
    public void testStackTraceFilteredByDefaultInTerminal() {
        // Default constructor: terminalShowErrorStackTrace = false
        TerminalOutputFilter filter = new TerminalOutputFilter(true, true, true, true, true);
        List<String> output = new ArrayList<>();

        String err1 = "2026-09-28 01:00:01.000 ERROR [main] o.s.b.SpringApplication : Application run failed\n";
        String ex1 = "org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'dataSource'\n";
        String st1 = "\tat org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:325)\n";
        String st2 = "\tat org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:200)\n";
        String st3 = "\t... 45 common frames omitted\n";
        String cause = "Caused by: java.sql.SQLException: Connection refused: connect\n";
        String st4 = "\tat java.base/sun.nio.ch.Net.connect0(Native Method)\n";
        String st5 = "\t... 50 more\n";
        String normal = "2026-09-28 01:00:02.000 INFO [main] Retrying in 5 seconds...\n";

        filter.processChunk(err1, output::add);
        filter.processChunk(ex1, output::add);
        filter.processChunk(st1, output::add);
        filter.processChunk(st2, output::add);
        filter.processChunk(st3, output::add);
        filter.processChunk(cause, output::add);
        filter.processChunk(st4, output::add);
        filter.processChunk(st5, output::add);
        filter.processChunk(normal, output::add);

        // Expected output: err1, ex1, cause, normal. All st1..st5 must be filtered!
        assertEquals(4, output.size());
        assertTrue(output.get(0).contains("Application run failed"));
        assertTrue(output.get(0).contains(TerminalOutputFilter.ANSI_RED));

        assertTrue(output.get(1).contains("BeanCreationException"));
        assertTrue(output.get(1).contains(TerminalOutputFilter.ANSI_RED));

        assertTrue(output.get(2).contains("Caused by: java.sql.SQLException"));
        assertTrue(output.get(2).contains(TerminalOutputFilter.ANSI_RED));

        assertTrue(output.get(3).contains("Retrying in 5 seconds..."));
        assertFalse(output.get(3).contains(TerminalOutputFilter.ANSI_RED));
    }

    @Test
    public void testStderrStackTraceFiltered() {
        TerminalOutputFilter filter = new TerminalOutputFilter(true, true, true, true, true, false);
        List<String> output = new ArrayList<>();

        filter.processChunk("Exception in thread \"main\" java.lang.RuntimeException: Crash\n", true, output::add);
        filter.processChunk("\tat com.example.Main.main(Main.java:5)\n", true, output::add);
        filter.processChunk("\t... 10 more\n", true, output::add);

        assertEquals(1, output.size());
        assertTrue(output.get(0).contains("Exception in thread \"main\""));
        assertTrue(output.get(0).contains(TerminalOutputFilter.ANSI_RED));
    }

    @Test
    public void testErrorColorizationDisabled() {
        // colorizeErrors = false
        TerminalOutputFilter filter = new TerminalOutputFilter(true, true, true, true, false);
        List<String> output = new ArrayList<>();

        String errorLog = "2026-09-28 01:00:01.000 ERROR [main] Fatal failure\n";
        filter.processChunk(errorLog, output::add);

        assertEquals(1, output.size());
        assertFalse(output.get(0).contains(TerminalOutputFilter.ANSI_RED));
    }

    @Test
    public void testStderrColorization() {
        TerminalOutputFilter filter = new TerminalOutputFilter(true, true, true, true, true);
        List<String> output = new ArrayList<>();

        filter.processChunk("Error writing to stream\n", true, output::add);

        assertEquals(1, output.size());
        assertTrue(output.get(0).contains(TerminalOutputFilter.ANSI_RED));
    }

    @Test
    public void testPaintRedWithInternalReset() {
        String input = "Prefix \u001B[0m Middle text";
        String painted = TerminalOutputFilter.paintRed(input);
        assertTrue(painted.startsWith(TerminalOutputFilter.ANSI_RED));
        assertTrue(painted.endsWith(TerminalOutputFilter.ANSI_RESET));
        // Ensure reset in middle was chained with red
        assertTrue(painted.contains("\u001B[0m\u001B[91m"));
    }
}
