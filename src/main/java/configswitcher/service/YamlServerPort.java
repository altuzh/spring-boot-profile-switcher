package configswitcher.service;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the selected YAML before the base configuration from the same module. */
public final class YamlServerPort {
    private YamlServerPort() {}

    public static Integer read(Path selected, Path base) throws IOException {
        if (selected != null && Files.isRegularFile(selected)) {
            Integer port = readFile(selected);
            if (port != null) return port;
            Path parent = selected.getParent();
            for (String name : new String[]{"application.yml", "application.yaml"}) {
                Path sibling = parent.resolve(name);
                if (!sibling.equals(selected) && Files.isRegularFile(sibling)) {
                    port = readFile(sibling);
                    if (port != null) return port;
                }
            }
            return null;
        }
        return base != null && Files.isRegularFile(base) ? readFile(base) : null;
    }

    private static Integer readFile(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (!(document instanceof Map)) return null;
            Map<?, ?> root = (Map<?, ?>) document;
            Object value = root.get("server.port");
            if (value == null && root.get("server") instanceof Map) value = ((Map<?, ?>) root.get("server")).get("port");
            if (value == null) return null;
            String text = value.toString().trim();
            Matcher placeholder = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}").matcher(text);
            if (placeholder.matches()) {
                String environment = System.getenv(placeholder.group(1));
                text = environment != null ? environment : placeholder.group(2);
            }
            if (text == null || !text.matches("[0-9]{1,5}")) throw new IllegalArgumentException("server.port is not a resolved numeric port");
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("server.port must be between 1 and 65535");
            return port;
        } catch (RuntimeException e) {
            throw new IOException("Cannot read server.port from " + file + ": " + e.getMessage(), e);
        }
    }
}
