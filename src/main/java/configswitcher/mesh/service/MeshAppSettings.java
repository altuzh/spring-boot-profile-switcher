package configswitcher.mesh.service;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

public record MeshAppSettings(URI upstream, List<String> keys, Set<String> forwardedHeaders) {
    private static final List<String> ENDPOINT_KEYS = List.of(
            "n2o.engine.graphql.endpoint", "n2o.graphql.endpoint.user-info", "mesh.endpoint", "mesh.url");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    public static MeshAppSettings read(Path selected, String profile, List<String> args, Map<String, String> env) throws IOException {
        Map<String, String> cli = options(args, "--");
        Map<String, String> vm = options(args, "-D");
        Map<String, String> files = new LinkedHashMap<>();
        if (selected != null && selected.getParent() != null) {
            Path dir = selected.getParent();
            readFiles(dir, "application", files);
            String profiles = property("spring.profiles.active", cli, vm, env, files);
            if (profiles == null) profiles = profile;
            if (profiles != null && !profiles.equals("default")) {
                for (String active : profiles.split(",")) {
                    String name = active.trim();
                    if (!name.matches("[A-Za-z0-9_.-]+"))
                        throw new IllegalArgumentException("Unsupported Spring profile");
                    readFiles(dir, "application-" + name, files);
                }
            }
        }
        for (String key : List.of("spring.config.import", "spring.config.location", "spring.config.additional-location",
                "spring.config.name", "spring.application.json", "spring.profiles.include")) {
            if (property(key, cli, vm, env, files) != null) {
                throw new IllegalArgumentException("Cannot determine the Mesh endpoint for custom Spring config");
            }
        }
        if (env.containsKey("SPRING_APPLICATION_JSON")) throw new IllegalArgumentException("Custom Spring config JSON");

        URI upstream = null;
        List<String> keys = new ArrayList<>();
        for (String key : ENDPOINT_KEYS) {
            // MESH_URL alone is a fallback or placeholder, not a second endpoint declaration.
            if (key.equals("mesh.url") && !files.containsKey(key) && !cli.containsKey(key)
                    && !vm.containsKey(key) && !env.containsKey(key)) continue;
            String value = property(key, cli, vm, env, files);
            if (value == null) continue;
            var matcher = PLACEHOLDER.matcher(value);
            if (matcher.matches()) {
                value = property(matcher.group(1), cli, vm, env, files);
                if (value == null) value = matcher.group(2);
            }
            if (value == null) throw new IllegalArgumentException("Unresolved Mesh endpoint");
            URI endpoint = URI.create(value.trim());
            if (upstream != null && !upstream.equals(endpoint))
                throw new IllegalArgumentException("Multiple Mesh endpoints");
            upstream = endpoint;
            keys.add(key);
        }
        if (upstream == null) {
            String value = property("MESH_URL", cli, vm, env, files);
            if (value == null) return null;
            upstream = URI.create(value.trim());
            keys.add("MESH_URL");
        }
        Set<String> forwarded = new HashSet<>();
        for (String key : List.of("n2o.engine.graphql.forward-headers", "n2o.graphql.forward-headers")) {
            String value = property(key, cli, vm, env, files);
            if (value == null) continue;
            var matcher = PLACEHOLDER.matcher(value);
            if (matcher.matches()) {
                value = property(matcher.group(1), cli, vm, env, files);
                if (value == null) value = matcher.group(2);
            }
            if (value == null) throw new IllegalArgumentException("Unresolved Mesh forwarded headers");
            for (String name : value.replaceAll("^\\[|]$", "").split("[,\\s]+")) {
                if (name.isEmpty()) continue;
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                    throw new IllegalArgumentException("Invalid Mesh forwarded header name");
                }
                forwarded.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return new MeshAppSettings(upstream, List.copyOf(keys), Set.copyOf(forwarded));
    }

    public List<String> route(List<String> args, URI local) {
        var routed = new ArrayList<>(args);
        routed.removeIf(arg -> keys.stream().anyMatch(key -> arg.equals("--" + key) || arg.startsWith("--" + key + "=")));
        for (String key : keys) routed.add("--" + key + "=" + local);
        return routed;
    }

    private static Map<String, String> options(List<String> args, String prefix) {
        var result = new LinkedHashMap<String, String>();
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (arg.startsWith(prefix) && equals > prefix.length()) {
                result.put(arg.substring(prefix.length(), equals), arg.substring(equals + 1));
            }
        }
        return result;
    }

    private static String property(String key, Map<String, String> cli, Map<String, String> vm,
                                   Map<String, String> env, Map<String, String> files) {
        if (cli.containsKey(key)) return cli.get(key);
        if (vm.containsKey(key)) return vm.get(key);
        if (env.containsKey(key)) return env.get(key);
        String envKey = key.replace('.', '_').replace("-", "").toUpperCase(Locale.ROOT);
        return env.getOrDefault(envKey, files.get(key));
    }

    private static void readFiles(Path dir, String name, Map<String, String> values) throws IOException {
        for (String extension : List.of(".yaml", ".yml", ".properties")) {
            Path file = dir.resolve(name + extension);
            if (!Files.isRegularFile(file)) continue;
            try (var reader = Files.newBufferedReader(file)) {
                if (extension.equals(".properties")) {
                    Properties properties = new Properties();
                    properties.load(reader);
                    for (String key : properties.stringPropertyNames()) values.put(key, properties.getProperty(key));
                } else {
                    Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
                    if (document instanceof Map<?, ?> map) flatten("", map, values, 0);
                }
            } catch (YAMLException e) {
                throw new IOException("Cannot resolve Mesh settings from YAML", e);
            }
        }
    }

    private static void flatten(String prefix, Map<?, ?> map, Map<String, String> values, int depth) {
        if (depth == 16) return;
        map.forEach((key, value) -> {
            String name = prefix + key;
            if (value instanceof Map<?, ?> child) flatten(name + ".", child, values, depth + 1);
            else if (value != null) values.put(name, value.toString());
        });
    }
}
