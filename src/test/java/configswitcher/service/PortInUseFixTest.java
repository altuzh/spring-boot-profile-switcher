package configswitcher.service;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

class PortInUseFixTest {
    @Test
    void scriptPlansBothFamiliesWithoutModifyingNetworking() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path script = Files.createTempFile("port-plan-test", ".ps1");
        Path result = Files.createTempFile("port-plan-result", ".txt");
        try (var source = getClass().getResourceAsStream("/scripts/free-reserved-port.ps1")) {
            Files.copy(source, script, StandardCopyOption.REPLACE_EXISTING);
            String command = "function global:netsh { $global:LASTEXITCODE=0; "
                    + "if ($args -contains 'store=active') { '     50841     50940' } }; & '"
                    + script.toString().replace("'", "''") + "' -Port 50906 -PlanOnly -ResultFile '"
                    + result.toString().replace("'", "''") + "'";
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command)
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor(), output);
            assertTrue(output.contains("PLAN ipv4 50841-50940"), output);
            assertTrue(output.contains("PLAN ipv6 50841-50940"), output);
            assertEquals(0, Files.size(result));
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(result);
        }
    }

    @Test
    void matchesOnlyValidPortConflictAndListeningTcpOwners() {
        assertEquals(50906, PortInUseFix.portFrom("Port 50906 was already in use."));
        assertNull(PortInUseFix.portFrom("Port 70000 was already in use."));
        assertNull(PortInUseFix.portFrom("Request to port 50906 failed."));

        String netstat = "  TCP    0.0.0.0:50906     0.0.0.0:0        LISTENING       3210\n"
                + "  TCP    [::]:50906        [::]:0           LISTENING       3210\n"
                + "  TCP    127.0.0.1:50906   127.0.0.1:50000  ESTABLISHED     9876\n"
                + "  TCP    0.0.0.0:50907     0.0.0.0:0        LISTENING       4567\n";
        assertEquals(Set.of(3210L), PortInUseFix.parseListeningPids(netstat, 50906));
    }
}
