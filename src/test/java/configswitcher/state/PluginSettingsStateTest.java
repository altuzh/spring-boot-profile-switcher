package configswitcher.state;

import configswitcher.model.ExecutionMode;
import configswitcher.model.SwitchMode;
import configswitcher.model.TerminalShellType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PluginSettingsStateTest {

    private PluginSettingsState settings;

    @BeforeEach
    public void setUp() {
        settings = new PluginSettingsState();
    }

    @Test
    public void testDefaultMeshWebAddresses() {
        PluginSettingsState.State state = settings.getState();
        assertNotNull(state.meshWebAddresses, "meshWebAddresses should not be null");
        assertTrue(state.meshWebAddresses.isEmpty(), "meshWebAddresses should be empty by default (not prefilled)");
        assertEquals("", state.selectedMeshWebAddress, "selectedMeshWebAddress should be empty by default");
        assertEquals("", state.meshDefaultEndpointUrl, "meshDefaultEndpointUrl should be empty by default");
        assertNotNull(state.deletedMeshWebAddresses, "deletedMeshWebAddresses should not be null");
        assertTrue(state.deletedMeshWebAddresses.isEmpty());
    }

    @Test
    public void testDefaultGitPatchSettings() {
        PluginSettingsState.State state = settings.getState();
        assertTrue(state.revertPatchFilesBeforeApply, "revertPatchFilesBeforeApply should be true by default");
        assertTrue(state.autoRevertPatches, "autoRevertPatches should be true by default");
        assertEquals(".idea/patches", state.patchStorageDir);
        assertNotNull(state.gitPatches);
    }

    @Test
    public void testDefaultPreRunCommand() {
        PluginSettingsState.State state = settings.getState();
        assertEquals("mvn clean package", state.preRunCommand, "preRunCommand should default to 'mvn clean package'");
        assertFalse(state.enablePreRun, "enablePreRun should default to false");
    }

    @Test
    public void testPreRunCommandSanitization() {
        PluginSettingsState.State legacyState = new PluginSettingsState.State();
        legacyState.preRunCommand = "mvn -T 8 -o \"-Dmaven.test.skip=true\"";
        legacyState.enablePreRun = true;

        settings.loadState(legacyState);

        PluginSettingsState.State state = settings.getState();
        assertEquals("mvn clean package", state.preRunCommand, "Legacy hardcoded example should be sanitized to default");
        assertFalse(state.enablePreRun, "enablePreRun should be reset to false when legacy command sanitized");
    }

    @Test
    public void testAddMeshWebAddress() {
        String newUrl = "http://mesh-staging.test.ecp/graphql";
        settings.addMeshWebAddress(newUrl);

        PluginSettingsState.State state = settings.getState();
        assertTrue(state.meshWebAddresses.contains(newUrl));
        assertEquals(newUrl, state.selectedMeshWebAddress);
        assertEquals(newUrl, state.meshDefaultEndpointUrl);

        // Adding duplicate should not duplicate entry in list
        int sizeBefore = state.meshWebAddresses.size();
        settings.addMeshWebAddress(newUrl);
        assertEquals(sizeBefore, state.meshWebAddresses.size());
    }

    @Test
    public void testAddMeshWebAddressWithoutSelecting() {
        String newUrl = "http://mesh-dev.test.ecp/graphql";
        String initialSelected = settings.getState().selectedMeshWebAddress;

        settings.addMeshWebAddress(newUrl, false);

        PluginSettingsState.State state = settings.getState();
        assertTrue(state.meshWebAddresses.contains(newUrl));
        assertEquals(initialSelected, state.selectedMeshWebAddress, "Selection should not change when select=false");
    }

    @Test
    public void testRemoveMeshWebAddress() {
        String url1 = "http://mesh-1.test.ecp/graphql";
        String url2 = "http://mesh-2.test.ecp/graphql";
        settings.addMeshWebAddress(url1);
        settings.addMeshWebAddress(url2);

        settings.removeMeshWebAddress(url2);

        PluginSettingsState.State state = settings.getState();
        assertFalse(state.meshWebAddresses.contains(url2));
        assertTrue(state.deletedMeshWebAddresses.contains(url2));
        assertEquals(url1, state.selectedMeshWebAddress);
    }

    @Test
    public void testRemoveAllAllowsEmptyAndDoesNotRestoreDefaults() {
        settings.addMeshWebAddress("http://mesh-1.test.ecp/graphql");
        settings.addMeshWebAddress("http://mesh-2.test.ecp/graphql");

        settings.removeMeshWebAddress("http://mesh-1.test.ecp/graphql");
        settings.removeMeshWebAddress("http://mesh-2.test.ecp/graphql");

        PluginSettingsState.State state = settings.getState();
        assertTrue(state.meshWebAddresses.isEmpty(), "Should allow meshWebAddresses to be empty when all removed");
        assertEquals("", state.selectedMeshWebAddress);
        assertTrue(state.deletedMeshWebAddresses.contains("http://mesh-1.test.ecp/graphql"));
        assertTrue(state.deletedMeshWebAddresses.contains("http://mesh-2.test.ecp/graphql"));
    }

    @Test
    public void testDeletedValuesNeverComeBackOnLoadState() {
        String url = "http://mesh-java.test.ecp/graphql";
        settings.addMeshWebAddress(url);
        settings.removeMeshWebAddress(url);

        // Simulate loadState with an older state XML that still had the deleted URL in meshWebAddresses
        PluginSettingsState.State olderState = new PluginSettingsState.State();
        olderState.meshWebAddresses = new ArrayList<>(List.of(url, "http://other.test.ecp/graphql"));
        olderState.deletedMeshWebAddresses = new ArrayList<>(settings.getState().deletedMeshWebAddresses);

        settings.loadState(olderState);

        PluginSettingsState.State loaded = settings.getState();
        assertFalse(loaded.meshWebAddresses.contains(url), "Deleted value must never come back after loadState");
        assertTrue(loaded.meshWebAddresses.contains("http://other.test.ecp/graphql"));
    }

    @Test
    public void testLoadStateDoesNotPrefillDefaultValues() {
        PluginSettingsState.State emptyState = new PluginSettingsState.State();
        emptyState.meshWebAddresses = new ArrayList<>();
        emptyState.selectedMeshWebAddress = "";
        emptyState.meshDefaultEndpointUrl = "";

        settings.loadState(emptyState);

        PluginSettingsState.State loaded = settings.getState();
        assertTrue(loaded.meshWebAddresses.isEmpty(), "loadState must not prefill default values");
        assertEquals("", loaded.selectedMeshWebAddress);
    }

    @Test
    public void testReAddingDeletedAddressClearsDeletedFlag() {
        String url = "http://mesh-java.test.ecp/graphql";
        settings.addMeshWebAddress(url);
        settings.removeMeshWebAddress(url);
        assertTrue(settings.getState().deletedMeshWebAddresses.contains(url));

        // Explicitly re-add
        settings.addMeshWebAddress(url);
        assertFalse(settings.getState().deletedMeshWebAddresses.contains(url), "Re-adding explicitly should clear from deleted list");
        assertTrue(settings.getState().meshWebAddresses.contains(url));
    }

    @Test
    public void testSetSelectedMeshWebAddress() {
        String customUrl = "https://custom-mesh.org/graphql";
        settings.setSelectedMeshWebAddress(customUrl);

        PluginSettingsState.State state = settings.getState();
        assertEquals(customUrl, state.selectedMeshWebAddress);
        assertTrue(state.meshWebAddresses.contains(customUrl), "Should auto-add custom URL if not present");
    }

    @Test
    public void testLoadStateEnforcesSimplifiedDefaults() {
        PluginSettingsState.State olderState = new PluginSettingsState.State();
        olderState.executionMode = ExecutionMode.INTELLIJ_RUN_CONFIG;
        olderState.switchMode = SwitchMode.FILE_SWAP;
        olderState.autoRestart = false;
        olderState.executeInTerminal = false;
        olderState.terminalShellType = TerminalShellType.CMD;
        olderState.gitPatches.add(new configswitcher.model.GitPatchEntry(true, ".idea/patches/p1.patch", "Old patch", "dev"));

        settings.loadState(olderState);

        PluginSettingsState.State loaded = settings.getState();
        assertEquals(ExecutionMode.COMMAND_PIPELINE, loaded.executionMode, "executionMode must be locked to COMMAND_PIPELINE");
        assertEquals(SwitchMode.PROFILE_ARGUMENTS, loaded.switchMode, "switchMode must be locked to non-destructive PROFILE_ARGUMENTS");
        assertTrue(loaded.autoRestart, "autoRestart must be locked to true");
        assertTrue(loaded.executeInTerminal, "executeInTerminal must be locked to true");
        assertEquals(TerminalShellType.AUTO, loaded.terminalShellType, "terminalShellType must be locked to AUTO");
        assertEquals("", loaded.gitPatches.get(0).getApplicableProfile(), "applicableProfile must be cleared so patches apply to active profile");
    }

    @Test
    public void testLanguageDefaultAndLoadState() {
        assertEquals(configswitcher.i18n.PluginLanguage.EN, settings.getState().language, "Default language should be EN");

        PluginSettingsState.State ruState = new PluginSettingsState.State();
        ruState.language = configswitcher.i18n.PluginLanguage.RU;
        settings.loadState(ruState);
        assertEquals(configswitcher.i18n.PluginLanguage.RU, settings.getState().language);

        PluginSettingsState.State nullLangState = new PluginSettingsState.State();
        nullLangState.language = null;
        settings.loadState(nullLangState);
        assertEquals(configswitcher.i18n.PluginLanguage.EN, settings.getState().language, "Null language should fallback to EN");
    }

    @Test
    public void testSettingsStateProjectIsolation() {
        com.intellij.openapi.project.Project projA = (com.intellij.openapi.project.Project) java.lang.reflect.Proxy.newProxyInstance(
                com.intellij.openapi.project.Project.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.project.Project.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return "ProjectA";
                    if ("isDefault".equals(method.getName())) return false;
                    if ("isDisposed".equals(method.getName())) return false;
                    if (method.getReturnType().equals(boolean.class)) return false;
                    return null;
                }
        );
        com.intellij.openapi.project.Project projB = (com.intellij.openapi.project.Project) java.lang.reflect.Proxy.newProxyInstance(
                com.intellij.openapi.project.Project.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.project.Project.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return "ProjectB";
                    if ("isDefault".equals(method.getName())) return false;
                    if ("isDisposed".equals(method.getName())) return false;
                    if (method.getReturnType().equals(boolean.class)) return false;
                    return null;
                }
        );

        PluginSettingsState stateA = new PluginSettingsState(projA);
        PluginSettingsState stateB = new PluginSettingsState(projB);

        stateA.getState().selectedProfile = "profile-alpha";
        stateA.getState().runCommandTemplate = "cmd-alpha";
        stateA.addMeshWebAddress("http://mesh-alpha:8080/graphql");

        stateB.getState().selectedProfile = "profile-beta";
        stateB.getState().runCommandTemplate = "cmd-beta";
        stateB.addMeshWebAddress("http://mesh-beta:8080/graphql");

        assertEquals("profile-alpha", stateA.getState().selectedProfile);
        assertEquals("profile-beta", stateB.getState().selectedProfile);

        assertEquals("cmd-alpha", stateA.getState().runCommandTemplate);
        assertEquals("cmd-beta", stateB.getState().runCommandTemplate);

        assertTrue(stateA.getState().meshWebAddresses.contains("http://mesh-alpha:8080/graphql"));
        assertFalse(stateA.getState().meshWebAddresses.contains("http://mesh-beta:8080/graphql"));

        assertTrue(stateB.getState().meshWebAddresses.contains("http://mesh-beta:8080/graphql"));
        assertFalse(stateB.getState().meshWebAddresses.contains("http://mesh-alpha:8080/graphql"));
    }
}
