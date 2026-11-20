package configswitcher.mesh;

import com.sun.net.httpserver.HttpServer;
import com.intellij.util.xmlb.XmlSerializer;
import configswitcher.mesh.service.MeshCacheProxy;
import configswitcher.state.PluginSettingsState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshCacheProxyTest {
    private static final String QUERY = """
            {"query":"# comment\\nquery Read($id: ID!) { customer(id: $id) { name } }","variables":{"id":1}}
            """;
    private final HttpClient client = HttpClient.newHttpClient();
    private MeshCacheProxy proxy = new MeshCacheProxy();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer mesh;
    private URI upstream;

    @BeforeEach
    void start() throws IOException {
        mesh = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mesh.createContext("/mesh", exchange -> {
            try (exchange) {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int call = calls.incrementAndGet();
                var headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/json");
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth != null) headers.set("X-Seen-Authorization", auth);
                String trace = exchange.getRequestHeaders().getFirst("X-B3-TraceId");
                if (trace != null) headers.set("X-Seen-Trace", trace);
                if (request.contains("VaryBit")) headers.set("Vary", "Accept-Encoding, X-B3-TraceId");
                if (request.contains("NoStore")) headers.set("Cache-Control", "no-store");
                if (request.contains("Expired")) {
                    headers.set("Cache-Control", "max-age=5");
                    headers.set("Age", "5");
                }
                int status = request.contains("Unavailable") ? 503 : 200;
                String content = request.contains("Fail") ? "{\"errors\":[{\"message\":\"Failed\"}]}"
                        : "{\"data\":{\"call\":" + call + "}}";
                if (request.contains("Large")) content = "{\"data\":{\"value\":\"" + "a".repeat(300_000) + "\"}}";
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                if (request.contains("Compressed")) {
                    var buffer = new ByteArrayOutputStream();
                    try (var gzip = new GZIPOutputStream(buffer)) {
                        gzip.write(bytes);
                    }
                    headers.set("Content-Encoding", "gzip");
                    bytes = buffer.toByteArray();
                }
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        mesh.start();
        upstream = URI.create("http://127.0.0.1:" + mesh.getAddress().getPort() + "/mesh");
        proxy.start(upstream);
    }

    @AfterEach
    void stop() {
        proxy.stop();
        if (mesh != null) mesh.stop(0);
    }

    @Test
    void reusesResponseForTheSameVariablesAndCredentials() throws Exception {
        var first = post(QUERY, "Bearer alice");
        assertEquals("MISS", cacheStatus(first));
        assertEquals("Bearer alice", first.headers().firstValue("X-Seen-Authorization").orElseThrow());
        var captured = proxy.requests().get(0);
        assertEquals("Read", captured.operation());
        assertEquals("customer", captured.rootField());
        assertTrue(captured.requestHeaders().contains("authorization: [redacted]"));
        assertTrue(captured.responseHeaders().contains("x-seen-authorization: [redacted]"));
        assertFalse((captured.requestHeaders() + captured.responseHeaders()).contains("Bearer alice"));
        var repeat = post(QUERY, "Bearer alice");
        assertEquals("HIT", cacheStatus(repeat));
        assertEquals(first.body(), repeat.body());
        assertEquals("MISS", cacheStatus(post(QUERY, "Bearer bob")));
        assertEquals("MISS", cacheStatus(post(QUERY.replace("\"id\":1", "\"id\":2"), "Bearer alice")));
        assertEquals(3, calls.get());
    }

    @Test
    void ignoresRequestMetadataButForwardsItAndSeparatesCookies(@TempDir Path directory) throws Exception {
        proxy.routeTo(upstream, "test", directory);
        var first = postWithTrace(QUERY, "first", "alice");
        assertEquals("MISS", cacheStatus(first));
        assertEquals("first", first.headers().firstValue("X-Seen-Trace").orElseThrow());
        proxy.stop();
        proxy = new MeshCacheProxy();
        proxy.routeTo(upstream, "test", directory);
        assertEquals("HIT", cacheStatus(postWithTrace(QUERY, "next", "alice")));
        var hit = proxy.requests().get(0);
        assertTrue(hit.requestHeaders().contains("x-b3-traceid: next"));
        assertTrue(hit.requestHeaders().contains("cookie: [redacted]"));
        assertTrue(hit.responseHeaders().contains("content-type: application/json"));
        assertTrue(hit.responseHeaders().contains("x-mesh-cache: HIT"));
        assertEquals("MISS", cacheStatus(postWithTrace(QUERY, "next", "bob")));
        var details = proxy.requests().get(0).lookup();
        assertTrue(details.contains("cookie"));
        assertFalse(details.contains("x-b3"));
        assertEquals(2, calls.get());
    }

    @Test
    void skipsResponsesThatVaryByB3Tracing() throws Exception {
        String payload = "{\"query\":\"query VaryBit { customer { name } }\"}";
        assertEquals("MISS", cacheStatus(postWithTrace(payload, "first", "alice")));
        assertEquals("MISS", cacheStatus(postWithTrace(payload, "next", "alice")));
        assertTrue(proxy.requests().get(0).storage().contains("x-b3-traceid"));
        assertEquals(2, calls.get());
    }

    @Test
    void separatesAccountsUsingHeadersConfiguredByTheApp() throws Exception {
        proxy.routeTo(upstream, "test", null, Set.of("x-local-principal"));
        for (String principal : List.of("alice", "bob")) {
            var request = request(QUERY).header("X-Local-Principal", principal).build();
            assertEquals("MISS", cacheStatus(client.send(request, HttpResponse.BodyHandlers.ofString())));
        }
        assertTrue(proxy.requests().get(0).lookup().contains("x-local-principal"));
        assertEquals(2, calls.get());
    }

    @Test
    void cachesSingleMutationsAndKeepsExistingQueryResponses() throws Exception {
        String read = "{\"query\":\"{ customer { name } }\"}";
        String write = "{\"query\":\"mutation Change { update }\"}";
        post(read, "Bearer alice");
        assertEquals("MISS", cacheStatus(post(write, "Bearer alice")));
        assertEquals("HIT", cacheStatus(post(write, "Bearer alice")));
        assertEquals("HIT", cacheStatus(post(read, "Bearer alice")));
        String multi = "{\"query\":\"query Read { customer { name } } mutation Change { update }\",\"operationName\":\"Change\"}";
        assertEquals("BYPASS", cacheStatus(post(multi, "Bearer alice")));
        assertEquals(3, calls.get());
    }

    @Test
    void skipsGraphqlErrorsHttpErrorsAndUncacheableResponses() throws Exception {
        for (String operation : new String[]{"Fail", "Unavailable", "NoStore"}) {
            String type = operation.equals("Fail") ? "mutation" : "query";
            String payload = "{\"query\":\"" + type + " " + operation + " { customer { name } }\"}";
            var first = post(payload, "Bearer alice");
            var second = post(payload, "Bearer alice");
            assertEquals(operation.equals("Unavailable") ? 503 : 200, second.statusCode());
            assertEquals("MISS", cacheStatus(first));
            assertEquals("MISS", cacheStatus(second));
        }
        assertTrue(proxy.requests().get(0).storage().contains("no-store"));
        assertEquals(6, calls.get());
    }

    @Test
    void persistsIndefinitelyAndSeparatesProfiles(@TempDir Path directory) throws Exception {
        String payload = "{\"query\":\"query Expired { customer { name } }\"}";
        proxy.routeTo(upstream, "test", directory);
        var saved = post(payload, "Bearer alice");
        assertEquals("MISS", cacheStatus(saved));
        assertTrue(proxy.requests().get(0).storage().contains("local cache"));
        proxy.stop();
        proxy = new MeshCacheProxy();
        proxy.routeTo(upstream, "uat", directory);
        assertEquals("MISS", cacheStatus(post(payload, "Bearer alice")));
        proxy.routeTo(upstream, "test", directory);
        var hit = post(payload, "Bearer alice");
        assertEquals("HIT", cacheStatus(hit));
        assertEquals(saved.body(), hit.body());
        assertTrue(proxy.requests().get(0).lookup().contains("profile cache"));
        assertTrue(proxy.clearProfileCache("test", directory));
        assertEquals("MISS", cacheStatus(post(payload, "Bearer alice")));
        proxy.routeTo(upstream, "uat", directory);
        assertEquals("HIT", cacheStatus(post(payload, "Bearer alice")));
        assertEquals(3, calls.get());
    }

    @Test
    void bypassRuleMatchesExactPayloadAcrossCredentialsAndClearsSavedMatches(@TempDir Path directory) throws Exception {
        proxy.routeTo(upstream, "test", directory);
        String write = "{\"query\":\"mutation Change { update }\"}";
        assertEquals("MISS", cacheStatus(post(write, "Bearer alice")));
        assertEquals("MISS", cacheStatus(post(write, "Bearer bob")));
        var row = proxy.requests().get(0);
        assertTrue(row.lookup().contains("authorization"));
        assertFalse(row.lookup().contains("Bearer"));
        var settings = new PluginSettingsState.State();
        settings.meshCacheBypassRules.put(row.ruleId(), row.operation());
        var restored = XmlSerializer.deserialize(XmlSerializer.serialize(settings), PluginSettingsState.State.class);
        assertEquals(settings.meshCacheBypassRules, restored.meshCacheBypassRules);
        proxy.setBypassRules(restored.meshCacheBypassRules.keySet());
        assertEquals("BYPASS", cacheStatus(post(write, "Bearer alice")));
        assertTrue(proxy.requests().get(0).lookup().contains("Always Bypass"));
        String other = write.replace("update", "anotherWrite");
        assertEquals("MISS", cacheStatus(post(other, "Bearer alice")));
        assertEquals("HIT", cacheStatus(post(other, "Bearer alice")));
        proxy.stop();
        proxy = new MeshCacheProxy();
        proxy.setBypassRules(restored.meshCacheBypassRules.keySet());
        proxy.routeTo(upstream, "test", directory);
        assertEquals("BYPASS", cacheStatus(post(write, "Bearer bob")));
        proxy.setBypassRules(Set.of());
        assertEquals("MISS", cacheStatus(post(write, "Bearer bob")));
        assertEquals("HIT", cacheStatus(post(other, "Bearer alice")));
        assertEquals(6, calls.get());
    }

    @Test
    void cachesCompressedJsonButStreamsLargeResponsesWithoutCaching() throws Exception {
        String compressed = "{\"query\":\"query Compressed { customer { name } }\"}";
        assertEquals("MISS", cacheStatus(post(compressed, "Bearer alice")));
        assertEquals("HIT", cacheStatus(post(compressed, "Bearer alice")));
        String large = "{\"query\":\"query Large { customer { name } }\"}";
        assertEquals("MISS", cacheStatus(post(large, "Bearer alice")));
        var repeat = post(large, "Bearer alice");
        assertEquals("MISS", cacheStatus(repeat));
        assertEquals("{\"data\":{\"value\":\"" + "a".repeat(300_000) + "\"}}", repeat.body());
        assertEquals(3, calls.get());
    }

    @Test
    void refusesBrowserTrafficAndStartsWithAnEmptyCache() throws Exception {
        var browser = request(QUERY).header("Origin", "https://example.com").build();
        assertEquals(403, client.send(browser, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(0, calls.get());
        assertEquals("MISS", cacheStatus(post(QUERY, "Bearer alice")));
        proxy.stop();
        assertNull(proxy.endpoint());
        proxy.start(upstream);
        assertEquals("MISS", cacheStatus(post(QUERY, "Bearer alice")));
        assertEquals(2, calls.get());
    }

    @Test
    void refreshReplaysOnlyReadQueriesAndReplacesTheSavedResponse(@TempDir Path directory) throws Exception {
        proxy.routeTo(upstream, "test", directory);
        var first = post(QUERY, "Bearer alice");
        var result = proxy.refresh(proxy.requests().get(0));
        assertEquals("REFRESH", result.cacheStatus());
        assertEquals("customer", result.rootField());
        assertEquals("{\"data\":{\"call\":2}}", result.response());
        proxy.stop();
        proxy = new MeshCacheProxy();
        proxy.routeTo(upstream, "test", directory);
        var hit = post(QUERY, "Bearer alice");
        assertEquals("HIT", cacheStatus(hit));
        assertEquals(result.response(), hit.body());
        assertEquals(first.headers().firstValue("X-Seen-Authorization"), hit.headers().firstValue("X-Seen-Authorization"));
        post("{\"query\":\"mutation Change { update }\"}", "Bearer alice");
        var write = proxy.requests().get(0);
        assertFalse(write.refreshable());
        assertNull(proxy.refresh(write));
        assertEquals(3, calls.get());
    }

    @Test
    void disablingLeavesAlreadyRoutedRequestsWorkingWithoutCachingOrCapture() throws Exception {
        post(QUERY, "Bearer alice");
        var previous = proxy.requests().get(0);
        proxy.disable();
        assertEquals("BYPASS", cacheStatus(post(QUERY, "Bearer alice")));
        assertEquals(0, proxy.requests().size());
        assertNull(proxy.refresh(previous));
        assertEquals(2, calls.get());
    }

    private HttpResponse<String> post(String payload, String token) throws IOException, InterruptedException {
        return client.send(request(payload).header("Authorization", token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithTrace(String payload, String trace, String cookie) throws IOException, InterruptedException {
        var request = request(payload).header("Cookie", "session=" + cookie)
                .header("X-Request-ID", trace)
                .header("X-B3-TraceId", trace).header("X-B3-SpanId", trace + "-span")
                .header("X-B3-ParentSpanId", trace + "-parent").build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String payload) {
        return HttpRequest.newBuilder(proxy.endpoint()).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload));
    }

    private static String cacheStatus(HttpResponse<?> response) {
        return response.headers().firstValue("X-Mesh-Cache").orElseThrow();
    }
}
