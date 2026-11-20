package configswitcher.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static configswitcher.service.AuthStatusService.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthStatusServiceTest {
    @TempDir
    Path directory;

    @Test
    void followsLensStateForTheExactServiceNamespaceAndServicePort() throws Exception {
        String rows = """
                [{"kind":"service","namespace":"ps","name":"authz","port":80,"forwardPort":10198,"status":"%s"},
                 {"kind":"pod","namespace":"ps","name":"authz","port":80,"status":"Disabled"},
                 {"kind":"service","namespace":"other","name":"authz","port":80,"status":"Disabled"},
                 {"kind":"service","namespace":"ps","name":"other","port":80,"status":"Disabled"},
                 {"kind":"service","namespace":"ps","name":"authz","port":10198,"status":"Disabled"}]
                """;
        for (String key : new String[]{"port_forwards", "value"}) {
            for (String state : new String[]{"Active", "Disabled", "Reconnecting"}) {
                Files.writeString(directory.resolve("cluster.json"), "{\"" + key + "\":" + rows.formatted(state) + "}");
                Set<Status> matches = EnumSet.noneOf(Status.class);
                AuthStatusService.readStore(directory, key, new AuthStatusService.Settings("authz", "ps"), matches);
                assertEquals(Set.of(Status.valueOf(state.toUpperCase(java.util.Locale.ROOT))), matches);
            }
        }
    }
}
