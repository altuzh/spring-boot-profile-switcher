package configswitcher.service;

import configswitcher.service.AppStatusService.AppStartupStage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AppStatusServiceTest {

    @Test
    public void testInitialStateIsStopped() {
        AppStatusService service = new AppStatusService();
        AppStatusService.StatusInfo info = service.getCurrentStatus();

        assertEquals(AppStartupStage.IDLE, info.stage());
        assertEquals("Stopped", info.displayText());
        assertEquals(AppStatusService.COLOR_GREY, info.color());
    }

    @Test
    public void testStartupStageSequence() {
        AppStatusService service = new AppStatusService();
        AtomicInteger notifications = new AtomicInteger();
        service.addListener(notifications::incrementAndGet);

        // Pre-run building
        service.setStage(AppStartupStage.BUILDING);
        assertEquals(AppStartupStage.BUILDING, service.getCurrentStatus().stage());
        assertEquals("Building...", service.getCurrentStatus().displayText());
        assertEquals(AppStatusService.COLOR_BLUE, service.getCurrentStatus().color());

        // Pre-run finished -> starting app
        service.setStage(AppStartupStage.STARTING);
        assertEquals(AppStartupStage.STARTING, service.getCurrentStatus().stage());
        assertEquals("Starting...", service.getCurrentStatus().displayText());
        assertEquals(AppStatusService.COLOR_GREY, service.getCurrentStatus().color());

        // Stage 1: Tomcat started on port(s): 8080 (http) -> grey
        service.processChunk("2026-09-28 01:06:33.456 INFO 1234 --- [main] o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat started on port(s): 8080 (http) with context path ''\n");
        assertEquals(AppStartupStage.TOMCAT_STARTED, service.getCurrentStatus().stage());
        assertEquals("Tomcat: 8080", service.getCurrentStatus().displayText());
        assertEquals(AppStatusService.COLOR_GREY, service.getCurrentStatus().color());
        assertEquals("8080", service.getCurrentStatus().port());

        // Stage 2: WatchDir is started -> yellow
        service.processChunk("2026-09-28 01:06:35.789 INFO 1234 --- [main] c.b.w.WatchDirService : WatchDir is started\n");
        assertEquals(AppStartupStage.WATCHDIR_STARTED, service.getCurrentStatus().stage());
        assertEquals("WatchDir started", service.getCurrentStatus().displayText());
        assertEquals(AppStatusService.COLOR_YELLOW, service.getCurrentStatus().color());
        assertEquals("8080", service.getCurrentStatus().port());

        // Stage 3: Application was started -> green, showing "Started: <port>"
        service.processChunk("2026-09-28 01:06:40.123 INFO 1234 --- [main] c.e.MyApplication : Started MyApplication in 2.54 seconds\n");
        assertEquals(AppStartupStage.STARTED, service.getCurrentStatus().stage());
        assertEquals("Started: 8080", service.getCurrentStatus().displayText());
        assertEquals(AppStatusService.COLOR_GREEN, service.getCurrentStatus().color());
        assertEquals("8080", service.getCurrentStatus().port());

        assertTrue(notifications.get() >= 5);
    }

    @Test
    public void testErrorOnlyShownWhenAppStopped() {
        AppStatusService service = new AppStatusService();
        service.setStage(AppStartupStage.STARTING);

        // 1. Error arrives while running -> should NOT show ERROR in info
        String errorLine = "2026-09-28 01:06:45.000 ERROR 1234 --- [main] o.s.b.SpringApplication : Non-fatal configuration warning\n";
        service.processChunk(errorLine);

        // App is still running, so status must remain STARTING
        assertEquals(AppStartupStage.STARTING, service.getCurrentStatus().stage());
        assertEquals("Starting...", service.getCurrentStatus().displayText());

        // 2. Normal startup continues while running
        service.processChunk("Tomcat started on port(s): 8080\n");
        assertEquals(AppStartupStage.TOMCAT_STARTED, service.getCurrentStatus().stage());
        assertEquals("Tomcat: 8080", service.getCurrentStatus().displayText());

        service.processChunk("WatchDir is started\n");
        assertEquals(AppStartupStage.WATCHDIR_STARTED, service.getCurrentStatus().stage());

        service.processChunk("Started MyApplication in 2.54 seconds\n");
        assertEquals(AppStartupStage.STARTED, service.getCurrentStatus().stage());
        assertEquals("Started: 8080", service.getCurrentStatus().displayText());

        // 3. Runtime error arrives while running -> should still NOT show ERROR in info
        service.processChunk("2026-09-28 01:06:50.000 ERROR 1234 --- [main] Connection refused\n");
        assertEquals(AppStartupStage.STARTED, service.getCurrentStatus().stage());
        assertEquals("Started: 8080", service.getCurrentStatus().displayText());

        // 4. App terminates / is stopped with failure -> NOW show ERROR in info!
        service.onAppTerminated(1);
        assertEquals(AppStartupStage.ERROR, service.getCurrentStatus().stage());
        assertEquals("ERROR", service.getCurrentStatus().displayText());
        assertEquals(AppStatusService.COLOR_RED, service.getCurrentStatus().color());
        assertTrue(service.getCurrentStatus().tooltipText().contains("ERROR"));

        // 5. Stopping reverts to IDLE (Stopped)
        service.onAppStopped();
        assertEquals(AppStartupStage.IDLE, service.getCurrentStatus().stage());
        assertEquals("Stopped", service.getCurrentStatus().displayText());
    }

    @Test
    public void testNonZeroTerminationIsError() {
        AppStatusService service = new AppStatusService();
        service.setStage(AppStartupStage.STARTING);

        service.onAppTerminated(1);
        assertEquals(AppStartupStage.ERROR, service.getCurrentStatus().stage());
        assertEquals("ERROR", service.getCurrentStatus().displayText());
        assertEquals(AppStatusService.COLOR_RED, service.getCurrentStatus().color());
        assertTrue(service.getCurrentStatus().tooltipText().contains("exit code 1"));
    }

    @Test
    public void testPreRunFailureShowsError() {
        AppStatusService service = new AppStatusService();
        service.setStage(AppStartupStage.BUILDING);

        service.onPreRunFailed("Pre-run build failed");
        assertEquals(AppStartupStage.ERROR, service.getCurrentStatus().stage());
        assertEquals("ERROR", service.getCurrentStatus().displayText());
        assertEquals(AppStatusService.COLOR_RED, service.getCurrentStatus().color());
        assertTrue(service.getCurrentStatus().tooltipText().contains("Pre-run build failed"));
    }

    @Test
    public void testStartedDisplayTextWithAndWithoutPort() {
        // Case 1: Without prior Tomcat port
        AppStatusService service1 = new AppStatusService();
        service1.setStage(AppStartupStage.STARTING);
        service1.processChunk("Started MyApplication in 2.5 seconds\n");
        assertEquals(AppStartupStage.STARTED, service1.getCurrentStatus().stage());
        assertEquals("Started", service1.getCurrentStatus().displayText());

        // Case 2: With Tomcat port detected earlier
        AppStatusService service2 = new AppStatusService();
        service2.setStage(AppStartupStage.STARTING);
        service2.processChunk("Tomcat started on port(s): 8080 (http)\n");
        service2.processChunk("Started MyApplication in 2.5 seconds\n");
        assertEquals(AppStartupStage.STARTED, service2.getCurrentStatus().stage());
        assertEquals("Started: 8080", service2.getCurrentStatus().displayText());

        // Case 3: With port on the log line directly
        AppStatusService service3 = new AppStatusService();
        service3.setStage(AppStartupStage.STARTING);
        service3.processChunk("Application started on port 9090\n");
        assertEquals(AppStartupStage.STARTED, service3.getCurrentStatus().stage());
        assertEquals("Started: 9090", service3.getCurrentStatus().displayText());
    }
}
