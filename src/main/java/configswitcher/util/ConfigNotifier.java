package configswitcher.util;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

public class ConfigNotifier {
    private static final String NOTIFICATION_GROUP_ID = "Spring Boot Profile Switcher";

    public static void notifyInfo(@Nullable Project project, String message) {
        ConfigSwitcherLog.info(project, "NOTIFICATION [INFO]: " + message);
        notify(project, message, NotificationType.INFORMATION);
    }

    public static void notifyWarning(@Nullable Project project, String message) {
        ConfigSwitcherLog.warn(project, "NOTIFICATION [WARN]: " + message);
        notify(project, message, NotificationType.WARNING);
    }

    public static void notifyError(@Nullable Project project, String message) {
        ConfigSwitcherLog.error(project, "NOTIFICATION [ERROR]: " + message);
        notify(project, message, NotificationType.ERROR);
    }

    private static void notify(@Nullable Project project, String message, NotificationType type) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(message, type)
                    .notify(project);
        } catch (Throwable ignored) {}
    }
}
