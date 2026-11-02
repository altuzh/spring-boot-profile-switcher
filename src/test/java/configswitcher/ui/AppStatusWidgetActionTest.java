package configswitcher.ui;

import configswitcher.service.AppStatusService.AppStartupStage;
import configswitcher.service.AppStatusService.StatusInfo;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

class AppStatusWidgetActionTest {
    @Test
    void opensStartedApplicationAtDetectedPort() {
        assertEquals("http://localhost:9090/#/", AppStatusWidgetAction.applicationUrl(
                status(AppStartupStage.STARTED, "9090")));
    }

    @Test
    void keepsAnalyzerForOtherStagesOrMissingPort() {
        for (AppStartupStage stage : AppStartupStage.values()) {
            if (stage != AppStartupStage.STARTED) {
                assertNull(AppStatusWidgetAction.applicationUrl(status(stage, "8080")));
            }
        }
        assertNull(AppStatusWidgetAction.applicationUrl(status(AppStartupStage.STARTED, null)));
        assertNull(AppStatusWidgetAction.applicationUrl(status(AppStartupStage.STARTED, "")));
        assertNull(AppStatusWidgetAction.applicationUrl(status(AppStartupStage.STARTED, " ")));
    }

    private static StatusInfo status(AppStartupStage stage, String port) {
        return new StatusInfo(stage, "", "", Color.GRAY, port, null);
    }
}
