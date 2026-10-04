package configswitcher.state;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import configswitcher.i18n.PluginLanguage;
import configswitcher.model.ExecutionMode;
import configswitcher.model.GitPatchEntry;
import configswitcher.model.SwitchMode;
import configswitcher.model.TerminalShellType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service(Service.Level.PROJECT)
@State(
        name = "SpringBootProfileSwitcherSettings",
        storages = {@Storage("springBootProfileSwitcher.xml")}
)
public class PluginSettingsState implements PersistentStateComponent<PluginSettingsState.State> {

    public static class State {
        public PluginLanguage language = PluginLanguage.detectSystemLanguage();
        public String selectedProfile = "default";
        public String selectedFilePath = "";
        public SwitchMode switchMode = SwitchMode.PROFILE_ARGUMENTS;
        public ExecutionMode executionMode = ExecutionMode.COMMAND_PIPELINE;
        public String targetRunConfigName = "";
        public boolean autoRestart = true;
        public List<String> additionalDirectories = new ArrayList<>();
        public Map<String, String> profileCustomVmOptions = new HashMap<>();
        public Map<String, String> profileCustomProgramArgs = new HashMap<>();
        public boolean backupFileBeforeSwap = true;
        public String mirroredProfile = "";

        // Git Patches & Multi-Stage Execution Pipeline
        public String patchStorageDir = ".idea/patches";
        public List<GitPatchEntry> gitPatches = new ArrayList<>();
        public boolean revertPatchFilesBeforeApply = true;
        public boolean autoRevertPatches = true;
        public boolean enablePreRun = false;
        public String preRunCommand = "mvn clean package";
        public String preRunWorkingDir = "";
        public String runCommandTemplate = "";
        public String runWorkingDir = "";
        public boolean skipPreRunOnQuickSwitch = false;
        public boolean skipPreRunIfRunning = true;
        public boolean executeInTerminal = true;
        public TerminalShellType terminalShellType = TerminalShellType.AUTO;

        // Terminal Output Log Level Filtering & Colorization
        public boolean terminalOutputDebug = true;
        public boolean terminalOutputInfo = true;
        public boolean terminalOutputWarn = true;
        public boolean terminalOutputError = true;
        public boolean colorizeErrorOutputInTerminal = true;
        public boolean terminalShowErrorStackTrace = false;

        // GraphQL Mesh Observability
        public boolean enableMeshCapture = true;
        public int meshMaxHistory = 500;
        public String meshDefaultEndpointUrl = "";
        public List<String> meshWebAddresses = new ArrayList<>();
        public List<String> deletedMeshWebAddresses = new ArrayList<>();
        public String selectedMeshWebAddress = "";
        public boolean meshAutoOpenToolWindow = false;
    }

    private State myState = new State();
    private final Project project;

    public PluginSettingsState() {
        this.project = null;
    }

    public PluginSettingsState(@NotNull Project project) {
        this.project = project;
    }

    public static boolean isLegacyDefaultTemplate(@Nullable String template) {
        if (template == null || template.isBlank()) return true;
        if ("java -jar target/server.jar --debug".equals(template.trim())) return true;
        return template.contains("C:\\Users\\al\\projects\\bft\\co")
                || template.contains("C:/Users/al/projects/bft/co");
    }

    public void ensureCommandTemplateCalculated(@NotNull Project project) {
        if (isLegacyDefaultTemplate(myState.runCommandTemplate)) {
            myState.runCommandTemplate = configswitcher.service.AppRunManager.calculateDefaultCommandTemplate(project);
        }
    }

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        XmlSerializerUtil.copyBean(state, myState);
        if (myState.language == null) {
            myState.language = PluginLanguage.detectSystemLanguage();
        }
        // Enforce simplified defaults
        myState.executionMode = ExecutionMode.COMMAND_PIPELINE;
        myState.switchMode = SwitchMode.PROFILE_ARGUMENTS;
        myState.autoRestart = true;
        myState.executeInTerminal = true;
        myState.terminalShellType = TerminalShellType.AUTO;

        // Ensure git patches apply to the active profile (no target profile filtering)
        if (myState.gitPatches != null) {
            for (configswitcher.model.GitPatchEntry patch : myState.gitPatches) {
                patch.setApplicableProfile("");
            }
        }

        // Automatically sanitize hardcoded example if present
        if ("mvn -T 8 -o \"-Dmaven.test.skip=true\"".equals(myState.preRunCommand)) {
            myState.preRunCommand = "mvn clean package";
            myState.enablePreRun = false;
        }
        if (myState.preRunCommand == null) {
            myState.preRunCommand = "mvn clean package";
        }

        if (project != null && isLegacyDefaultTemplate(myState.runCommandTemplate)) {
            myState.runCommandTemplate = configswitcher.service.AppRunManager.calculateDefaultCommandTemplate(project);
        }
        if (myState.meshWebAddresses == null) {
            myState.meshWebAddresses = new ArrayList<>();
        }
        if (myState.deletedMeshWebAddresses == null) {
            myState.deletedMeshWebAddresses = new ArrayList<>();
        }
        // Purge any previously deleted addresses that might be present in older persisted XML
        if (!myState.deletedMeshWebAddresses.isEmpty()) {
            myState.meshWebAddresses.removeAll(myState.deletedMeshWebAddresses);
        }
        // Normalize any /graphiql entries to have trailing slash
        for (int i = 0; i < myState.meshWebAddresses.size(); i++) {
            String addr = myState.meshWebAddresses.get(i);
            if (addr != null && !addr.contains("?") && addr.endsWith("/graphiql")) {
                myState.meshWebAddresses.set(i, addr + "/");
            }
        }
        if (myState.selectedMeshWebAddress != null && !myState.selectedMeshWebAddress.contains("?") && myState.selectedMeshWebAddress.endsWith("/graphiql")) {
            myState.selectedMeshWebAddress = myState.selectedMeshWebAddress + "/";
        }
        if (myState.meshDefaultEndpointUrl != null && !myState.meshDefaultEndpointUrl.contains("?") && myState.meshDefaultEndpointUrl.endsWith("/graphiql")) {
            myState.meshDefaultEndpointUrl = myState.meshDefaultEndpointUrl + "/";
        }
        if (myState.selectedMeshWebAddress == null) {
            myState.selectedMeshWebAddress = "";
        }
        if (myState.meshDefaultEndpointUrl == null) {
            myState.meshDefaultEndpointUrl = "";
        }
        // If the selectedMeshWebAddress was deleted or not in list, fallback to first available or empty
        if (!myState.selectedMeshWebAddress.isEmpty() && !myState.meshWebAddresses.contains(myState.selectedMeshWebAddress)) {
            myState.selectedMeshWebAddress = myState.meshWebAddresses.isEmpty() ? "" : myState.meshWebAddresses.get(0);
        }
    }

    public void addMeshWebAddress(@NotNull String url) {
        addMeshWebAddress(url, true);
    }

    public void addMeshWebAddress(@NotNull String url, boolean select) {
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return;
        if (!trimmed.contains("?") && trimmed.endsWith("/graphiql")) {
            trimmed = trimmed + "/";
        }
        myState.deletedMeshWebAddresses.remove(trimmed);
        if (!myState.meshWebAddresses.contains(trimmed)) {
            myState.meshWebAddresses.add(trimmed);
        }
        if (select) {
            myState.selectedMeshWebAddress = trimmed;
            myState.meshDefaultEndpointUrl = trimmed;
        }
    }

    public void removeMeshWebAddress(@NotNull String url) {
        String trimmed = url.trim();
        myState.meshWebAddresses.remove(trimmed);
        if (!trimmed.isEmpty() && !myState.deletedMeshWebAddresses.contains(trimmed)) {
            myState.deletedMeshWebAddresses.add(trimmed);
        }
        if (trimmed.equals(myState.selectedMeshWebAddress) || !myState.meshWebAddresses.contains(myState.selectedMeshWebAddress)) {
            myState.selectedMeshWebAddress = myState.meshWebAddresses.isEmpty() ? "" : myState.meshWebAddresses.get(0);
            myState.meshDefaultEndpointUrl = myState.selectedMeshWebAddress;
        }
    }

    public void setSelectedMeshWebAddress(@NotNull String url) {
        String trimmed = url.trim();
        myState.selectedMeshWebAddress = trimmed;
        myState.meshDefaultEndpointUrl = trimmed;
        if (!trimmed.isEmpty()) {
            myState.deletedMeshWebAddresses.remove(trimmed);
            if (!myState.meshWebAddresses.contains(trimmed)) {
                myState.meshWebAddresses.add(trimmed);
            }
        }
    }

    public static PluginSettingsState getInstance(@NotNull Project project) {
        PluginSettingsState instance = project.getService(PluginSettingsState.class);
        if (instance != null) {
            instance.ensureCommandTemplateCalculated(project);
        }
        return instance;
    }
}
