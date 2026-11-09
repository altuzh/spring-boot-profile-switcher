package configswitcher.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class YamlServerPortTest {
    @Test
    void profileOverridesAndFallsBackWithinItsModule(@TempDir Path directory) throws Exception {
        Path base = directory.resolve("application.yml");
        Path profile = directory.resolve("application-dev.yaml");
        Files.writeString(base, "server:\n  port: 50906\n");
        Files.writeString(profile, "server.port: '${N2O_PORT_TEST_UNDEFINED:50907}'\n");
        assertEquals(50907, YamlServerPort.read(profile, base));
        Files.writeString(profile, "management:\n  server:\n    port: 9090\n");
        assertEquals(50906, YamlServerPort.read(profile, null));
        Files.writeString(profile, "server: {port: 70000}\n");
        assertThrows(IOException.class, () -> YamlServerPort.read(profile, base));
    }
}
