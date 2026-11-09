package configswitcher.ui;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import configswitcher.service.PortInUseFix;
import configswitcher.service.ConfigScannerService;
import configswitcher.service.YamlServerPort;
import configswitcher.state.PluginSettingsState;
import configswitcher.model.ConfigFileModel;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

public final class FreePortAction extends AnAction {
    public FreePortAction() { super("Free blocked TCP port..."); }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() == null) return;
        var state = PluginSettingsState.getInstance(event.getProject()).getState();
        var scanner = ConfigScannerService.getInstance(event.getProject());
        ConfigFileModel selected = scanner.findConfigByProfile(state.selectedProfile);
        ConfigFileModel base = scanner.getBaseConfigFile();
        Integer detected;
        try {
            Path selectedPath = state.selectedFilePath != null && !state.selectedFilePath.isBlank()
                    ? Path.of(state.selectedFilePath) : selected != null ? Path.of(selected.getFullPath()) : null;
            detected = YamlServerPort.read(selectedPath, base == null ? null : Path.of(base.getFullPath()));
        } catch (Exception e) { showError(e); return; }
        String value = Messages.showInputDialog(event.getProject(), detected == null
                ? "server.port was not found in YAML. TCP port (1–65535):" : "server.port from YAML (1–65535):",
                "Free blocked TCP port", null, detected == null ? "" : detected.toString(), null);
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
