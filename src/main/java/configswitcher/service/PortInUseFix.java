package configswitcher.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Identifies the local listener named by a Spring Boot port-in-use failure. */
public final class PortInUseFix {
    private static final Pattern PORT_ERROR = Pattern.compile("(?i)\\bPort\\s+(\\d{1,5})\\s+(?:was|is)\\s+already\\s+in\\s+use\\b");

    private PortInUseFix() {}

    public static Integer portFrom(String message) {
        if (message == null) return null;
        Matcher match = PORT_ERROR.matcher(message);
        if (!match.find()) return null;
        int port = Integer.parseInt(match.group(1));
        return port >= 1 && port <= 65535 ? port : null;
    }

    public static Set<Long> listeningPids(int port) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("netstat", "-ano", "-p", "tcp").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        if (process.waitFor() != 0) throw new IOException("netstat failed: " + output.trim());
        return parseListeningPids(output, port);
    }

    static Set<Long> parseListeningPids(String output, int port) {
        Set<Long> result = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 5 && "TCP".equalsIgnoreCase(fields[0])
                    && fields[1].endsWith(":" + port) && "LISTENING".equalsIgnoreCase(fields[3])) {
                try {
                    result.add(Long.parseLong(fields[4]));
                } catch (NumberFormatException ignored) {}
            }
        }
        return result;
    }

    public static void stopListener(long pid) throws Exception {
        if (pid == ProcessHandle.current().pid()) throw new IOException("Refusing to stop the profile switcher itself");
        ProcessHandle process = ProcessHandle.of(pid).orElseThrow(() -> new IOException("Process " + pid + " has exited"));
        if (!process.destroy() && process.isAlive()) throw new IOException("Cannot stop process " + pid);
        try {
            process.onExit().get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            process.destroyForcibly();
            process.onExit().get(3, TimeUnit.SECONDS);
        }
        if (process.isAlive()) throw new IOException("Process " + pid + " is still running");
    }

    public static String planReservedPort(int port) throws Exception {
        return runScript(port, true);
    }

    public static String freeReservedPort(int port) throws Exception {
        return runScript(port, false);
    }

    private static String runScript(int port, boolean planOnly) throws Exception {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid TCP port: " + port);
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) throw new IOException("Windows is required");
        Path script = Files.createTempFile("n2o-free-port-", ".ps1");
        Path result = Files.createTempFile("n2o-free-port-result-", ".txt");
        try {
            try (InputStream source = PortInUseFix.class.getResourceAsStream("/scripts/free-reserved-port.ps1")) {
                if (source == null) throw new IOException("Port repair script is missing from the JAR");
                Files.copy(source, script, StandardCopyOption.REPLACE_EXISTING);
            }
            ProcessBuilder builder;
            if (planOnly) {
                builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                        script.toString(), "-Port", Integer.toString(port), "-ResultFile", result.toString(), "-PlanOnly");
            } else {
                String child = "& '" + script.toString().replace("'", "''") + "' -Port " + port
                        + " -ResultFile '" + result.toString().replace("'", "''") + "'";
                String encoded = Base64.getEncoder().encodeToString(child.getBytes(StandardCharsets.UTF_16LE));
                String parent = "$p = Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -PassThru "
                        + "-ArgumentList '-NoProfile -ExecutionPolicy Bypass -EncodedCommand " + encoded + "'; exit $p.ExitCode";
                builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", parent);
            }
            Process process = builder.redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
            int exitCode = process.waitFor();
            String message = Files.readString(result).trim();
            if (exitCode != 0) throw new IOException(message.isEmpty() ? output : message);
            return planOnly ? output : message;
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(result);
        }
    }
}
