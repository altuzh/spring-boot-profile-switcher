package configswitcher.mesh.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

@Service(Service.Level.PROJECT)
public final class MeshCacheProxy implements Disposable {
    private static final String LOOPBACK = "127.0.0.1";
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_RECENT = 32;
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Set<String> HOP_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade",
            "host", "content-length", "expect", "http2-settings");
    private static final Set<String> B3_HEADERS = Set.of("b3", "x-b3-traceid", "x-b3-spanid",
            "x-b3-parentspanid", "x-b3-sampled", "x-b3-flags");
    private static final Set<String> DEFAULT_MATCH_HEADERS = Set.of("authorization", "cookie", "x-auth-request-user",
            "x-api-key", "api-key", "x-auth-token", "x-user-id", "x-tenant-id", "idempotency-key", "x-idempotency-key",
            "accept-encoding");
    private static final Set<String> VISIBLE_HEADER_VALUES = Set.of("accept", "accept-encoding", "accept-language",
            "content-type", "content-encoding", "cache-control", "pragma", "vary", "age", "date", "expires",
            "retry-after", "user-agent", "traceparent", "x-request-id", "x-correlation-id", "x-mesh-cache");

    private Session session;
    private Set<String> bypassRules = Set.of();

    public synchronized void start(URI upstream) throws IOException {
        if (session != null) throw new IllegalStateException("Mesh proxy is already running");
        routeTo(upstream);
    }

    public synchronized URI routeTo(URI upstream) throws IOException {
        return routeTo(upstream, "default", null);
    }

    public synchronized URI routeTo(URI upstream, String profile, Path projectCache) throws IOException {
        return routeTo(upstream, profile, projectCache, Set.of());
    }

    public synchronized URI routeTo(URI upstream, String profile, Path projectCache, Set<String> forwardedHeaders) throws IOException {
        if (upstream.getHost() == null || upstream.getUserInfo() != null || upstream.getFragment() != null
                || upstream.getQuery() != null || upstream.getPath().isEmpty()
                || !("http".equalsIgnoreCase(upstream.getScheme()) || "https".equalsIgnoreCase(upstream.getScheme()))) {
            throw new IllegalArgumentException("Expected the full HTTP(S) GraphQL endpoint");
        }
        if (session != null && session.endpoint.equals(upstream)) throw new IllegalArgumentException("Mesh proxy loop");
        Set<String> matchedHeaders = new HashSet<>(DEFAULT_MATCH_HEADERS);
        matchedHeaders.addAll(forwardedHeaders);
        matchedHeaders.removeAll(B3_HEADERS);
        if (session == null || !session.upstream.equals(upstream) || !session.profile.equals(profile)
                || !session.matchedHeaders.equals(matchedHeaders)) {
            Path directory = projectCache == null ? null : profileDirectory(projectCache, profile);
            Session next = new Session(upstream, profile, directory == null ? null : new MeshDiskCache(directory),
                    bypassRules, Set.copyOf(matchedHeaders));
            stop();
            session = next;
        }
        session.setEnabled(true);
        return session.endpoint;
    }

    public synchronized void disable() {
        if (session != null) session.setEnabled(false);
    }

    public synchronized void setBypassRules(Set<String> rules) {
        bypassRules = Set.copyOf(rules);
        if (session != null) session.setBypassRules(bypassRules);
    }

    public synchronized String profile() {
        return session == null ? null : session.profile;
    }

    public static Path projectCacheDirectory(Project project) {
        return Path.of(PathManager.getSystemPath(), "config-switcher", "mesh-cache-v1", project.getLocationHash());
    }

    private static Path profileDirectory(Path projectCache, String profile) {
        return projectCache.resolve(fingerprint(Map.of(), profile.getBytes(StandardCharsets.UTF_8)));
    }

    public synchronized boolean clearProfileCache(String profile, Path projectCache) {
        if (session != null && session.profile.equals(profile)) return session.clearCache();
        return projectCache == null || new MeshDiskCache(profileDirectory(projectCache, profile)).clear();
    }

    public synchronized List<RequestView> requests() {
        return session == null ? List.of() : session.requests();
    }

    public RequestView refresh(RequestView request) throws IOException, InterruptedException {
        Session current;
        synchronized (this) {
            current = session;
        }
        return current == null ? null : current.refresh(request);
    }

    public synchronized URI endpoint() {
        return session == null ? null : session.endpoint;
    }

    public synchronized URI upstream() {
        return session == null ? null : session.upstream;
    }

    public synchronized void stop() {
        if (session == null) return;
        session.setEnabled(false);
        session.server.stop(0);
        session.workers.shutdownNow();
        session = null;
    }

    @Override
    public void dispose() {
        stop();
    }

    private enum Operation {QUERY, MUTATION, UNKNOWN}

    private record CachedResponse(Map<String, List<String>> headers, byte[] body, String ruleId) {
    }

    public record RequestView(long id, Instant time, String operation, int status, String cacheStatus,
                              String request, String response, boolean refreshable, String ruleId,
                              String lookup, String storage, long elapsedMs, String requestHeaders, String responseHeaders) {
    }

    private record Replay(Map<String, List<String>> headers, byte[] body) {
    }

    private record Seen(Replay replay, Map<String, String> headerKeys) {
    }

    private record Incoming(String method, String queryString, Map<String, List<String>> headers, byte[] body,
                            String ruleId, Operation operation, String label, Map<String, String> headerKeys) {
    }

    private record Decision(String key, String status, String reason, long revision) {
    }

    private static final class Session {
        private final URI upstream;
        private final URI endpoint;
        private final String profile;
        private final MeshDiskCache disk;
        private final Set<String> matchedHeaders;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final HttpServer server;
        private final ExecutorService workers = Executors.newFixedThreadPool(8, task -> {
            Thread thread = new Thread(task, "Mesh cache proxy");
            thread.setDaemon(true);
            return thread;
        });
        private final Map<String, CachedResponse> cache = new LinkedHashMap<>(16, 0.75f, true);
        private final Map<RequestView, Seen> recent = new LinkedHashMap<>();
        private Set<String> bypassRules;
        private long requestId;
        private boolean enabled = true;
        private long generation;

        private Session(URI upstream, String profile, MeshDiskCache disk, Set<String> bypassRules,
                        Set<String> matchedHeaders) throws IOException {
            this.upstream = upstream;
            this.profile = profile;
            this.disk = disk;
            this.matchedHeaders = matchedHeaders;
            this.bypassRules = bypassRules;
            server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 16);
            endpoint = URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + "/graphql");
            server.createContext("/graphql", this::handle);
            server.setExecutor(workers);
            server.start();
        }

        private void setEnabled(boolean value) {
            synchronized (cache) {
                if (enabled == value) return;
                enabled = value;
                generation++;
                cache.clear();
                recent.clear();
            }
        }

        private void setBypassRules(Set<String> rules) {
            synchronized (cache) {
                if (rules.equals(bypassRules)) return;
                if (disk != null) rules.stream().filter(rule -> !bypassRules.contains(rule)).forEach(disk::removeRule);
                bypassRules = rules;
                cache.values().removeIf(entry -> rules.contains(entry.ruleId()));
            }
        }

        private boolean clearCache() {
            synchronized (cache) {
                generation++;
                cache.clear();
                return disk == null || disk.clear();
            }
        }

        private List<RequestView> requests() {
            synchronized (cache) {
                var copy = new ArrayList<>(recent.keySet());
                Collections.reverse(copy);
                return copy;
            }
        }

        private RequestView capture(Incoming incoming, Decision decision, int status, String storage,
                                    Map<String, List<String>> headers, byte[] response, long started) {
            String requestText = incoming.queryString() != null && incoming.body().length == 0
                    ? incoming.queryString() : preview(incoming.body(), first(incoming.headers(), "content-encoding"));
            String responseText = preview(response, first(headers, "content-encoding"));
            synchronized (cache) {
                if (!enabled) return null;
                Replay replay = incoming.operation() == Operation.QUERY
                        && !incoming.headers().containsKey("cache-control") && !incoming.headers().containsKey("pragma")
                        ? new Replay(incoming.headers(), incoming.body()) : null;
                var displayedResponseHeaders = new TreeMap<>(headers);
                if (!decision.status().equals("REFRESH")) {
                    displayedResponseHeaders.put("x-mesh-cache", List.of(decision.status()));
                }
                var view = new RequestView(++requestId, Instant.now(), incoming.label(), status, decision.status(),
                        requestText, responseText, replay != null, incoming.ruleId(), decision.reason(), storage,
                        Duration.ofNanos(System.nanoTime() - started).toMillis(),
                        displayHeaders(incoming.headers(), matchedHeaders), displayHeaders(displayedResponseHeaders, matchedHeaders));
                recent.put(view, new Seen(replay, incoming.headerKeys()));
                if (recent.size() > MAX_RECENT) recent.remove(recent.keySet().iterator().next());
                return view;
            }
        }

        private RequestView refresh(RequestView view) throws IOException, InterruptedException {
            long started = System.nanoTime();
            Incoming incoming;
            Decision decision;
            synchronized (cache) {
                Seen prior = recent.get(view);
                Replay replay = prior == null ? null : prior.replay();
                if (!enabled || replay == null) return null;
                incoming = new Incoming("POST", null, replay.headers(), replay.body(), view.ruleId(), Operation.QUERY,
                        view.operation(), prior.headerKeys());
                String key = bypassRules.contains(view.ruleId()) ? null : cacheKey(replay.headers(), replay.body());
                if (key != null) {
                    cache.remove(key);
                    if (disk != null) {
                        disk.remove(view.ruleId(), key);
                        String oldKey = requestKey(replay.headers(), replay.body());
                        if (!oldKey.equals(key)) disk.remove(view.ruleId(), oldKey);
                    }
                }
                decision = new Decision(key, "REFRESH", key == null ? "Always Bypass rule matched." : "Requested a fresh response from Mesh.",
                        generation);
            }
            try {
                var response = request("POST", upstream, incoming.headers(), incoming.body());
                try (InputStream stream = response.body()) {
                    var headers = forwardedHeaders(response.headers().map());
                    byte[] body = jsonType(first(headers, "content-type"))
                            ? stream.readNBytes(MAX_RESPONSE_BYTES + 1) : new byte[0];
                    String storage = store(incoming, decision, response.statusCode(), headers, body);
                    return capture(incoming, decision, response.statusCode(), storage, headers, body, started);
                }
            } catch (IOException | IllegalArgumentException e) {
                capture(incoming, decision, 502, "Mesh connection failed.", Map.of(), new byte[0], started);
                throw new IOException("Mesh refresh failed", e);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                var inbound = exchange.getRequestHeaders();
                if (!endpoint.getAuthority().equals(inbound.getFirst("Host")) || inbound.containsKey("Origin")
                        || inbound.containsKey("Sec-Fetch-Site")) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                URI target = exchange.getRequestURI();
                if (!endpoint.getRawPath().equals(target.getRawPath()) || target.isAbsolute()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                boolean post = exchange.getRequestMethod().equals("POST");
                if (!post && !exchange.getRequestMethod().equals("GET")) {
                    exchange.getResponseHeaders().set("Allow", "GET, POST");
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                if (body.length > MAX_REQUEST_BYTES) {
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }
                String queryString = target.getRawQuery();
                Map<String, List<String>> headers = forwardedHeaders(inbound);
                Operation operation = post && queryString == null && jsonType(first(headers, "content-type"))
                        && !headers.containsKey("content-encoding") ? singleOperation(body) : Operation.UNKNOWN;
                String method = exchange.getRequestMethod();
                String ruleId = fingerprint(Map.of("method", List.of(method), "query", queryString == null
                        ? List.of() : List.of(queryString)), body);
                var incoming = new Incoming(method, queryString, headers, body, ruleId, operation,
                        operationLabel(preview(body, ""), method), headerKeys(headers, matchedHeaders));
                Decision decision;
                CachedResponse hit;
                synchronized (cache) {
                    String bypass = bypassReason(incoming);
                    String key = bypass == null ? cacheKey(headers, body) : null;
                    hit = key == null ? null : cache.get(key);
                    boolean fromDisk = false;
                    if (key != null && hit == null && disk != null) {
                        var saved = disk.read(ruleId, key);
                        if (saved == null) {
                            String oldKey = requestKey(headers, body);
                            if (!oldKey.equals(key)) saved = disk.read(ruleId, oldKey);
                        }
                        if (saved != null && storageRejection(saved.headers(), matchedHeaders) == null) {
                            hit = new CachedResponse(saved.headers(), saved.body(), ruleId);
                            remember(key, hit);
                            fromDisk = true;
                        }
                    }
                    String reason = bypass != null ? bypass : hit != null
                            ? (fromDisk ? "Found a saved response in the profile cache." : "Found a cached response for this request.")
                            : missReason(incoming);
                    String status = key == null ? "BYPASS" : hit != null ? "HIT" : "MISS";
                    decision = new Decision(key, status, reason, generation);
                }
                long started = System.nanoTime();
                if (hit != null) {
                    String storage = operation == Operation.MUTATION
                            ? "Returned the cached response. Mesh did not execute this mutation again."
                            : "Returned the cached response.";
                    capture(incoming, decision, 200, storage, hit.headers(), hit.body(), started);
                    send(exchange, 200, hit.headers(), "HIT", hit.body());
                } else {
                    forward(exchange, incoming, decision, started);
                }
            }
        }

        private String bypassReason(Incoming request) {
            if (!enabled) return "Mesh caching is off.";
            if (bypassRules.contains(request.ruleId())) return "Always Bypass rule matched.";
            if (request.headers().containsKey("cache-control")) return "The request includes Cache-Control.";
            if (request.headers().containsKey("pragma")) return "The request includes Pragma.";
            if (request.operation() == Operation.UNKNOWN)
                return "The payload is not a supported single GraphQL operation.";
            return null;
        }

        private String missReason(Incoming request) {
            RequestView previous = null;
            boolean otherBody = false;
            for (RequestView view : recent.keySet()) {
                if (view.ruleId().equals(request.ruleId())) previous = view;
                else if (view.operation().equals(request.label())) otherBody = true;
            }
            if (previous == null) return otherBody ? "The request body or variables differ from the earlier request."
                    : "No response is cached for this request.";
            Seen seen = recent.get(previous);
            var changed = new TreeSet<>(seen.headerKeys().keySet());
            changed.addAll(request.headerKeys().keySet());
            changed.removeIf(name -> Objects.equals(seen.headerKeys().get(name), request.headerKeys().get(name)));
            if (!changed.isEmpty()) return "Forwarded headers changed: " + String.join(", ", changed) + ".";
            return "No response is cached. Previous result: " + previous.storage();
        }

        private void forward(HttpExchange exchange, Incoming incoming, Decision decision, long started) throws IOException {
            URI target = incoming.queryString() == null ? upstream
                    : URI.create(upstream.toASCIIString() + "?" + incoming.queryString());
            String cacheStatus = decision.status();
            HttpResponse<InputStream> response;
            try {
                response = request(incoming.method(), target, incoming.headers(), incoming.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(502, -1);
                return;
            } catch (IOException | IllegalArgumentException e) {
                capture(incoming, decision, 502, "Mesh connection failed.", Map.of(), new byte[0], started);
                exchange.getResponseHeaders().set("X-Mesh-Cache", cacheStatus);
                exchange.sendResponseHeaders(502, -1);
                return;
            }
            Map<String, List<String>> responseHeaders = forwardedHeaders(response.headers().map());
            int status = response.statusCode();
            try (InputStream stream = response.body()) {
                boolean observe;
                synchronized (cache) {
                    observe = enabled;
                }
                boolean hasBody = status != 204 && status != 205 && status != 304;
                if (observe && hasBody && jsonType(first(responseHeaders, "content-type"))) {
                    byte[] prefix = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                    String storage = store(incoming, decision, status, responseHeaders, prefix);
                    capture(incoming, decision, status, storage, responseHeaders, prefix, started);
                    if (prefix.length <= MAX_RESPONSE_BYTES) {
                        send(exchange, status, responseHeaders, cacheStatus, prefix);
                        return;
                    }
                    writeHeaders(exchange, status, responseHeaders, cacheStatus, 0);
                    exchange.getResponseBody().write(prefix);
                } else {
                    if (observe) {
                        String storage = store(incoming, decision, status, responseHeaders, new byte[0]);
                        capture(incoming, decision, status, storage, responseHeaders, new byte[0], started);
                    }
                    writeHeaders(exchange, status, responseHeaders, cacheStatus, hasBody ? 0 : -1);
                    if (!hasBody) return;
                }
                stream.transferTo(exchange.getResponseBody());
            }
        }

        private HttpResponse<InputStream> request(String method, URI target, Map<String, List<String>> headers,
                                                  byte[] body) throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30))
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        }

        private String store(Incoming incoming, Decision decision, int status, Map<String, List<String>> headers,
                             byte[] body) {
            if (decision.key() == null) return "Not stored because caching was bypassed.";
            if (status != 200) return "Mesh returned HTTP " + status + ".";
            String policy = storageRejection(headers, matchedHeaders);
            if (policy != null) return policy;
            String rejection = responseRejection(body, first(headers, "content-encoding"));
            if (rejection != null) return rejection;
            synchronized (cache) {
                if (!enabled || decision.revision() != generation) return "The cache was reset during the request.";
                if (bypassRules.contains(incoming.ruleId()))
                    return "An Always Bypass rule was added during the request.";
                remember(decision.key(), new CachedResponse(headers, body, incoming.ruleId()));
                if (disk != null) {
                    return disk.save(incoming.ruleId(), decision.key(), headers, body)
                            ? "Saved in this profile’s local cache." : "Stored in memory. Could not save locally.";
                }
            }
            return "Stored in memory.";
        }

        private void remember(String key, CachedResponse response) {
            cache.put(key, response);
            if (cache.size() > MAX_ENTRIES) cache.remove(cache.keySet().iterator().next());
        }

        private String cacheKey(Map<String, List<String>> headers, byte[] body) {
            var identity = new TreeMap<>(headers);
            identity.keySet().retainAll(matchedHeaders);
            identity.put(":matched-headers", matchedHeaders.stream().sorted().toList());
            return requestKey(identity, body);
        }

        private String requestKey(Map<String, List<String>> headers, byte[] body) {
            var identity = new TreeMap<>(headers);
            identity.put(":upstream", List.of(upstream.toASCIIString()));
            return fingerprint(identity, body);
        }
    }

    private static String operationLabel(String request, String fallback) {
        try {
            JsonObject payload = JSON.fromJson(request, JsonObject.class);
            if (payload != null && payload.has("operationName") && payload.get("operationName").isJsonPrimitive()) {
                String name = payload.get("operationName").getAsString();
                if (name.matches("[A-Za-z_][A-Za-z_0-9]{0,63}")) return name;
            }
            if (payload != null && payload.has("query") && payload.get("query").isJsonPrimitive()) {
                String query = payload.get("query").getAsString();
                String name = GraphQLFormatter.extractOperationName(query);
                if (name != null && name.matches("[A-Za-z_][A-Za-z_0-9]{0,63}")) return name;
            }
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    private static String preview(byte[] body, String encoding) {
        int limit = 64 * 1024;
        try {
            if (body.length != 0 && encoding.equalsIgnoreCase("gzip")) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = gzip.readNBytes(limit + 1);
                }
            } else if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("identity")) {
                return "";
            }
            return new String(body, 0, Math.min(body.length, limit), StandardCharsets.UTF_8)
                    + (body.length > limit ? "\n…" : "");
        } catch (IOException e) {
            return "";
        }
    }

    private static Map<String, List<String>> forwardedHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> result.put(name.toLowerCase(Locale.ROOT), values));
        List<String> connection = result.getOrDefault("connection", List.of());
        for (String line : connection) {
            for (String name : line.split(",")) result.remove(name.trim());
        }
        HOP_HEADERS.forEach(result::remove);
        return result;
    }

    private static String fingerprint(Map<String, List<String>> headers, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(JSON.toJson(new TreeMap<>(headers)).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, String> headerKeys(Map<String, List<String>> headers, Set<String> matchedHeaders) {
        Map<String, String> result = new TreeMap<>();
        headers.forEach((name, values) -> {
            if (matchedHeaders.contains(name)) result.put(name, fingerprint(Map.of(name, values), new byte[0]));
        });
        return result;
    }

    private static String displayHeaders(Map<String, List<String>> headers, Set<String> matchedHeaders) {
        var lines = new ArrayList<String>();
        new TreeMap<>(headers).forEach((name, values) -> {
            boolean visible = (VISIBLE_HEADER_VALUES.contains(name) || B3_HEADERS.contains(name))
                    && (!matchedHeaders.contains(name) || name.equals("accept-encoding"));
            if (visible) {
                for (String value : values) lines.add(name + ": " + value);
            } else {
                lines.add(name + ": [redacted]");
            }
        });
        return String.join("\n", lines);
    }

    private static String first(Map<String, List<String>> headers, String name) {
        return headers.getOrDefault(name, List.of("")).get(0);
    }

    private static boolean jsonType(String contentType) {
        String mediaType = contentType.split(";", 2)[0].trim();
        return mediaType.equalsIgnoreCase("application/json")
                || mediaType.equalsIgnoreCase("application/graphql-response+json");
    }

    private static String storageRejection(Map<String, List<String>> headers, Set<String> matchedHeaders) {
        if (!jsonType(first(headers, "content-type"))) return "Mesh did not return a JSON response.";
        if (headers.containsKey("set-cookie")) return "The response sets a cookie.";
        if (headers.containsKey("pragma")) return "The response includes Pragma.";
        if (headers.getOrDefault("vary", List.of()).stream().anyMatch(value -> value.contains("*")))
            return "The response has Vary: *.";
        for (String line : headers.getOrDefault("vary", List.of())) {
            for (String token : line.split(",")) {
                String name = token.trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && !matchedHeaders.contains(name)) {
                    return "The response varies by an unmatched request header: " + name + ".";
                }
            }
        }
        for (String line : headers.getOrDefault("cache-control", List.of())) {
            for (String directive : line.toLowerCase(Locale.ROOT).split(",")) {
                String name = directive.split("=", 2)[0].trim();
                switch (name) {
                    case "private", "no-store", "no-cache", "must-understand" -> {
                        return "Response storage skipped due to Cache-Control: " + name + ".";
                    }
                }
            }
        }
        return null;
    }

    private static String responseRejection(byte[] body, String encoding) {
        if (body.length > MAX_RESPONSE_BYTES) return "The response exceeds the 256 KiB cache limit.";
        try {
            if (encoding.equalsIgnoreCase("gzip")) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = gzip.readNBytes(MAX_RESPONSE_BYTES + 1);
                }
            } else if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("identity")) {
                return "The response uses an unsupported content encoding.";
            }
            if (body.length > MAX_RESPONSE_BYTES) return "The decoded response exceeds the 256 KiB cache limit.";
            JsonObject response = JSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
            if (response != null && response.has("errors")) return "The response contains GraphQL errors.";
            if (response != null && (response.has("incremental") || response.has("hasNext")))
                return "The response is incremental.";
            return response != null && response.has("data") && response.get("data").isJsonObject()
                    ? null : "The response did not contain a GraphQL data object.";
        } catch (IOException | RuntimeException e) {
            return "The response could not be parsed as GraphQL JSON.";
        }
    }

    private static Operation singleOperation(byte[] body) {
        try (var reader = new JsonReader(new StringReader(new String(body, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            String query = null;
            Set<String> fields = new HashSet<>();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) return Operation.UNKNOWN;
                switch (field) {
                    case "query" -> {
                        if (reader.peek() != JsonToken.STRING) return Operation.UNKNOWN;
                        query = reader.nextString();
                    }
                    case "operationName", "variables" -> reader.skipValue();
                    default -> {
                        return Operation.UNKNOWN;
                    }
                }
            }
            reader.endObject();
            return query != null && reader.peek() == JsonToken.END_DOCUMENT ? operationIn(query) : Operation.UNKNOWN;
        } catch (IOException | IllegalStateException e) {
            return Operation.UNKNOWN;
        }
    }

    // shortcut: pass complex documents through; use a full GraphQL parser if they need caching.
    private static Operation operationIn(String query) {
        int depth = 0;
        int parens = 0;
        boolean started = false;
        boolean complete = false;
        Operation operation = Operation.QUERY;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == ',' || c == '\ufeff' || c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            if (c == '#') {
                while (i + 1 < query.length() && query.charAt(i + 1) != '\n' && query.charAt(i + 1) != '\r') i++;
                continue;
            }
            if (complete) return Operation.UNKNOWN;
            if (!started) {
                started = true;
                if (c != '{') {
                    if (query.startsWith("query", i)) {
                        i += 4;
                    } else if (query.startsWith("mutation", i)) {
                        operation = Operation.MUTATION;
                        i += 7;
                    } else return Operation.UNKNOWN;
                    if (i + 1 < query.length() && (Character.isLetterOrDigit(query.charAt(i + 1))
                            || query.charAt(i + 1) == '_')) return Operation.UNKNOWN;
                    continue;
                }
            }
            if (c == '"') {
                if (query.startsWith("\"\"\"", i)) return Operation.UNKNOWN;
                while (++i < query.length() && query.charAt(i) != '"') {
                    if (query.charAt(i) == '\\') i++;
                }
            } else if (c == '(') {
                parens++;
            } else if (c == ')') {
                if (--parens < 0) return Operation.UNKNOWN;
            } else if (parens == 0 && c == '{') {
                depth++;
            } else if (parens == 0 && c == '}') {
                if (depth == 0) return Operation.UNKNOWN;
                complete = --depth == 0;
            }
        }
        return complete ? operation : Operation.UNKNOWN;
    }

    private static void send(HttpExchange exchange, int status, Map<String, List<String>> headers,
                             String cacheStatus, byte[] body) throws IOException {
        writeHeaders(exchange, status, headers, cacheStatus, body.length == 0 ? -1 : body.length);
        if (body.length != 0) exchange.getResponseBody().write(body);
    }

    private static void writeHeaders(HttpExchange exchange, int status, Map<String, List<String>> headers,
                                     String cacheStatus, long length) throws IOException {
        exchange.getResponseHeaders().putAll(headers);
        exchange.getResponseHeaders().set("X-Mesh-Cache", cacheStatus);
        exchange.sendResponseHeaders(status, length);
    }
}
