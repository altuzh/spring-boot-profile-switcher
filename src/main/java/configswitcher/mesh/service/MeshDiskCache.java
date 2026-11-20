package configswitcher.mesh.service;

import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Map;

final class MeshDiskCache {
    private static final Logger LOG = Logger.getInstance(MeshDiskCache.class);
    private static final Gson JSON = new Gson();
    private final Path directory;

    MeshDiskCache(Path directory) {
        this.directory = directory;
    }

    record Response(Map<String, List<String>> headers, byte[] body) {
    }

    private record Entry(Map<String, List<String>> headers, String body) {
    }

    Response read(String ruleId, String key) {
        Path file = file(ruleId, key);
        if (!Files.isRegularFile(file)) return null;
        try {
            if (Files.size(file) > 2 * 1024 * 1024) return null;
            byte[] data = Files.readAllBytes(file);
            if (SystemInfo.isWindows) data = Crypt32Util.cryptUnprotectData(data, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
            Entry entry = JSON.fromJson(new String(data, StandardCharsets.UTF_8), Entry.class);
            return new Response(Map.copyOf(entry.headers()), Base64.getDecoder().decode(entry.body()));
        } catch (IOException | RuntimeException | LinkageError e) {
            LOG.warn("Could not read a saved Mesh cache response.");
            return null;
        }
    }

    boolean save(String ruleId, String key, Map<String, List<String>> headers, byte[] body) {
        Path temporary = null;
        try {
            if (SystemInfo.isWindows) Files.createDirectories(directory);
            else
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            byte[] data = JSON.toJson(new Entry(headers, Base64.getEncoder().encodeToString(body)))
                    .getBytes(StandardCharsets.UTF_8);
            if (SystemInfo.isWindows) data = Crypt32Util.cryptProtectData(data, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
            temporary = Files.createTempFile(directory, "write-", ".tmp");
            Files.write(temporary, data);
            Files.move(temporary, file(ruleId, key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException | LinkageError e) {
            LOG.warn("Could not save a Mesh cache response locally.");
            return false;
        } finally {
            if (temporary != null) delete(temporary);
        }
    }

    void remove(String ruleId, String key) {
        delete(file(ruleId, key));
    }

    boolean clear() {
        return removeEntries("*.entry");
    }

    void removeRule(String ruleId) {
        if (ruleId.matches("[0-9a-f]{64}")) removeEntries(ruleId + "-*.entry");
    }

    private boolean removeEntries(String glob) {
        if (!Files.isDirectory(directory)) return true;
        boolean deleted = true;
        try (var files = Files.newDirectoryStream(directory, glob)) {
            for (Path file : files) deleted &= delete(file);
        } catch (IOException e) {
            LOG.warn("Could not open the local Mesh cache directory.");
            return false;
        }
        return deleted;
    }

    private Path file(String ruleId, String key) {
        return directory.resolve(ruleId + "-" + key + ".entry");
    }

    private static boolean delete(Path file) {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            LOG.warn("Could not remove a saved Mesh cache response.");
            return false;
        }
    }
}
