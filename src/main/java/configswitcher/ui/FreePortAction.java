package configswitcher.ui;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import configswitcher.service.PortInUseFix;
import org.jetbrains.annotations.NotNull;

public final class FreePortAction extends AnAction {
    public FreePortAction() { super("Free blocked TCP port..."); }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        String value = Messages.showInputDialog(event.getProject(), "TCP port (1–65535):", "Free blocked TCP port", null);
        if (value == null) return;
        int port;
        try {
            port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Messages.showErrorDialog("Enter a TCP port from 1 to 65535.", "Free blocked TCP port");
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String plan = PortInUseFix.planReservedPort(port);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (Messages.showYesNoDialog(event.getProject(), plan
                            + "\n\nWindows administrator access is required. WinNAT may restart, briefly interrupting WSL, Docker or VM networking.",
                            "Free TCP port " + port + "?", null) != Messages.YES) return;
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try {
                            String result = PortInUseFix.freeReservedPort(port);
                            ApplicationManager.getApplication().invokeLater(() -> Messages.showInfoMessage(result, "Free blocked TCP port"));
                        } catch (Exception e) { showError(e); }
                    });
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private static void showError(Exception error) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(error.getMessage(), "Free blocked TCP port"));
    }
}
