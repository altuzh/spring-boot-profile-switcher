package configswitcher.mesh;

import configswitcher.mesh.service.MeshAppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeshAppSettingsTest {
    @TempDir
    Path resources;

    @Test
    void resolvesProfileAndLaunchOverridesAndRoutesBothN2oEndpoints() throws Exception {
        Files.writeString(resources.resolve("application.yml"), """
                n2o:
                  engine:
                    graphql:
                      endpoint: ${MESH_URL:https://base.example/graphql}
                  graphql:
                    endpoint:
                      user-info: ${MESH_URL:https://test.example/graphql}
                """);
        Path profile = Files.writeString(resources.resolve("application-test.yml"), """
                n2o.engine.graphql.endpoint: ${MESH_URL:https://test.example/graphql}
                n2o.engine.graphql.forward-headers: [X-Auth-Request-User, X-Local-Principal]
                """);
        assertEquals(URI.create("https://test.example/graphql"),
                MeshAppSettings.read(profile, "test", List.of(), Map.of()).upstream());
        var args = List.of("--server.port=8080", "--MESH_URL=https://cli.example/graphql",
                "-DMESH_URL=https://vm.example/graphql");
        var settings = MeshAppSettings.read(profile, "test", args, Map.of("MESH_URL", "https://env.example/graphql"));
        assertEquals(URI.create("https://cli.example/graphql"), settings.upstream());
        assertEquals(Set.of("x-auth-request-user", "x-local-principal"), settings.forwardedHeaders());
        assertEquals(List.of("--server.port=8080", "--MESH_URL=https://cli.example/graphql", "-DMESH_URL=https://vm.example/graphql",
                        "--n2o.engine.graphql.endpoint=http://127.0.0.1:1234/graphql",
                        "--n2o.graphql.endpoint.user-info=http://127.0.0.1:1234/graphql"),
                settings.route(args, URI.create("http://127.0.0.1:1234/graphql")));
    }

    @Test
    void replacesDirectEndpointArgumentsAndDoesNotGuessIfEndpointsDiffer() throws Exception {
        Path base = Files.writeString(resources.resolve("application.yaml"), "mesh.url: https://base.example/graphql\n");
        var args = List.of("--mesh.url=https://override.example/graphql");
        var settings = MeshAppSettings.read(base, "default", args, Map.of());
        assertEquals(URI.create("https://override.example/graphql"), settings.upstream());
        assertEquals(List.of("--mesh.url=http://127.0.0.1:1234/graphql"),
                settings.route(args, URI.create("http://127.0.0.1:1234/graphql")));
        assertThrows(IllegalArgumentException.class, () -> MeshAppSettings.read(base, "default",
                List.of("--n2o.engine.graphql.endpoint=https://other.example/graphql"), Map.of()));
    }
}
