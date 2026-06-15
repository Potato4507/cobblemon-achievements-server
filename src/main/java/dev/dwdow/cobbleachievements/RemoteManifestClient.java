package dev.dwdow.cobbleachievements;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RemoteManifestClient {
    private static final String PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAgo9uAeO6x3qtuVVT/uJWe3ABugdGUiZn4ETSzOpH/II=";
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_REMOTE_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_UPDATE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CHUNKS = 512;
    private static final int MAX_CHUNK_BYTES = 256 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static volatile Status status = Status.initial();

    private RemoteManifestClient() {
    }

    public static Status status() {
        return status;
    }

    public static Status refresh(AchievementConfig config) {
        if (!config.remoteManifestEnabled) {
            status = new Status(false, Instant.now().toString(), "", installedVersion(), "", "", "", false, false, "remote manifest disabled");
            return status;
        }
        if (config.remoteManifestUrls.isEmpty()) {
            status = new Status(false, Instant.now().toString(), "", installedVersion(), "", "", "", false, false, "no remote manifest URLs configured");
            return status;
        }
        for (String url : config.remoteManifestUrls) {
            if (url == null || url.isBlank()) continue;
            try {
                Status refreshed = fetchAndApply(config, url.trim());
                status = refreshed;
                return refreshed;
            } catch (Exception error) {
                status = new Status(false, Instant.now().toString(), url.trim(), installedVersion(), "", "", "", false, false, error.getMessage());
            }
        }
        return status;
    }

    private static Status fetchAndApply(AchievementConfig config, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(httpsUri(url))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("manifest HTTP " + response.statusCode());
        }
        if (response.body().getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) {
            throw new SecurityException("remote manifest too large");
        }

        String manifestSha256 = sha256(response.body().getBytes(StandardCharsets.UTF_8));
        JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
        byte[] payloadBytes = Base64.getDecoder().decode(envelope.get("payloadBase64").getAsString());
        if (payloadBytes.length > MAX_MANIFEST_BYTES) throw new SecurityException("remote manifest payload too large");
        byte[] signatureBytes = Base64.getDecoder().decode(envelope.get("signatureBase64").getAsString());
        verify(payloadBytes, signatureBytes);

        JsonObject payload = JsonParser.parseString(new String(payloadBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        Path cacheDir = cacheDir();
        Files.createDirectories(cacheDir);
        String previousManifestSha256 = read(cacheDir.resolve("manifest.sha256"));
        boolean manifestChanged = !manifestSha256.equals(previousManifestSha256);
        String installed = installedVersion();
        String version = string(payload, "latestModVersion");
        boolean updateAvailable = !version.isBlank() && !version.equals(installed);

        cacheDataFiles(cacheDir, payload);
        String expectedSha = string(payload, "downloadSha256");
        Path stagedJar = stagedJarPath(cacheDir, version, expectedSha);
        boolean stagedAlready = updateAvailable && stagedJar != null && Files.exists(stagedJar);
        boolean shouldStage = config.remoteUpdateDownloadEnabled && updateAvailable && (manifestChanged || !stagedAlready);
        UpdateResult update = shouldStage ? stageUpdate(cacheDir, payload, version) : (stagedAlready ? new UpdateResult(stagedJar.toString(), "") : UpdateResult.none());

        atomicWrite(cacheDir.resolve("manifest.payload.json"), (new String(payloadBytes, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8));
        atomicWrite(cacheDir.resolve("manifest.sha256"), (manifestSha256 + "\n").getBytes(StandardCharsets.UTF_8));

        String message = statusMessage(string(payload, "message"), manifestChanged, update);
        return new Status(true, Instant.now().toString(), url, installed, version, update.downloadedPath, update.installedPath, manifestChanged, updateAvailable, message);
    }

    private static void verify(byte[] payloadBytes, byte[] signatureBytes) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(PUBLIC_KEY_BASE64);
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payloadBytes);
        if (!verifier.verify(signatureBytes)) throw new SecurityException("remote manifest signature rejected");
    }

    private static void cacheDataFiles(Path cacheDir, JsonObject payload) throws Exception {
        if (!payload.has("files") || !payload.get("files").isJsonArray()) return;
        Path filesDir = cacheDir.resolve("files");
        Files.createDirectories(filesDir);
        JsonArray files = payload.getAsJsonArray("files");
        for (int i = 0; i < files.size(); i++) {
            JsonObject file = files.get(i).getAsJsonObject();
            String id = safeName(string(file, "id"));
            String url = string(file, "url");
            String sha256 = string(file, "sha256");
            if (id.isBlank() || url.isBlank()) continue;
            byte[] bytes = downloadBytes(url, MAX_REMOTE_FILE_BYTES);
            if (!sha256.isBlank() && !sha256.equalsIgnoreCase(sha256(bytes))) {
                throw new SecurityException("sha256 mismatch for remote file " + id);
            }
            atomicWrite(filesDir.resolve(id + ".json"), bytes);
        }
    }

    private static UpdateResult stageUpdate(Path cacheDir, JsonObject payload, String latestVersion) throws Exception {
        String downloadUrl = string(payload, "downloadUrl");
        byte[] bytes = downloadUrl.isBlank() ? downloadChunkedBase64(payload) : downloadBytes(downloadUrl, MAX_UPDATE_BYTES);
        if (bytes.length == 0) return UpdateResult.none();
        String expected = string(payload, "downloadSha256");
        if (expected.isBlank()) throw new SecurityException("downloadSha256 is required for mod jar updates");
        String actual = sha256(bytes);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new SecurityException("downloadSha256 mismatch");
        }
        validateDownloadedJar(bytes, latestVersion);
        Path updatesDir = cacheDir.resolve("updates");
        Files.createDirectories(updatesDir);
        Path jar = stagedJarPath(cacheDir, latestVersion, actual);
        if (jar == null) jar = updatesDir.resolve("cobblemon-achievements-server-latest.jar");
        atomicWrite(jar, bytes);
        writeInstallInstructions(updatesDir, jar, latestVersion, actual);
        return new UpdateResult(jar.toString(), "");
    }

    private static byte[] downloadChunkedBase64(JsonObject payload) throws Exception {
        if (!payload.has("downloadBase64Chunks") || !payload.get("downloadBase64Chunks").isJsonArray()) return new byte[0];
        StringBuilder encoded = new StringBuilder();
        JsonArray chunks = payload.getAsJsonArray("downloadBase64Chunks");
        if (chunks.size() > MAX_CHUNKS) throw new SecurityException("too many update chunks");
        for (int i = 0; i < chunks.size(); i++) {
            String url = chunks.get(i).getAsString();
            if (url == null || url.isBlank()) continue;
            encoded.append(new String(downloadBytes(url, MAX_CHUNK_BYTES), StandardCharsets.UTF_8).replaceAll("\\s+", ""));
            if (encoded.length() > MAX_UPDATE_BYTES * 2) throw new SecurityException("chunked update too large");
        }
        if (encoded.isEmpty()) return new byte[0];
        byte[] decoded = Base64.getDecoder().decode(encoded.toString());
        if (decoded.length > MAX_UPDATE_BYTES) throw new SecurityException("update jar too large");
        return decoded;
    }

    private static Path currentModJar() {
        try {
            return FabricLoader.getInstance().getModContainer(CobbleAchievementsMod.MOD_ID)
                .flatMap(container -> container.getOrigin().getPaths().stream()
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .findFirst())
                .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] downloadBytes(String url, int maxBytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(httpsUri(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("download HTTP " + response.statusCode() + " from " + url);
        }
        if (response.body().length > maxBytes) {
            throw new SecurityException("download too large from " + url);
        }
        return response.body();
    }

    private static URI httpsUri(String url) {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new SecurityException("refusing non-HTTPS remote URL");
        }
        return uri;
    }

    private static void validateDownloadedJar(byte[] bytes, String expectedVersion) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (!"fabric.mod.json".equals(name)) continue;

                JsonObject mod = JsonParser.parseString(new String(readLimited(zip, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8)).getAsJsonObject();
                String id = string(mod, "id");
                if (!CobbleAchievementsMod.MOD_ID.equals(id)) {
                    throw new SecurityException("downloaded jar has wrong mod id: " + id);
                }
                String version = string(mod, "version");
                if (expectedVersion != null && !expectedVersion.isBlank() && !expectedVersion.equals(version)) {
                    throw new SecurityException("downloaded jar version " + version + " does not match manifest " + expectedVersion);
                }
                String environment = string(mod, "environment");
                if (!environment.isBlank() && !"server".equals(environment) && !"*".equals(environment)) {
                    throw new SecurityException("downloaded jar is not marked as a server mod");
                }
                return;
            }
        }
        throw new SecurityException("downloaded jar is missing fabric.mod.json");
    }

    private static byte[] readLimited(ZipInputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            if (output.size() + read > maxBytes) throw new IOException("zip entry too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void writeInstallInstructions(Path updatesDir, Path jar, String version, String sha256) throws IOException {
        String fileName = jar.getFileName().toString();
        Path currentJar = currentModJar();
        String currentJarText = currentJar == null ? "(not detected)" : currentJar.toString();
        String instructions = """
            Cobblemon Achievements Server staged update

            Version: %s
            SHA-256: %s
            Staged jar: %s
            Current running jar: %s

            Safety rule:
            - Stop the Modrinth server first.
            - Back up the current mods folder if you can.
            - Replace only cobblemon-achievements-server-*.jar with the staged jar.
            - Start the server again and check /cach remote status.

            This updater intentionally does not overwrite the running server jar automatically.
            """.formatted(version == null || version.isBlank() ? "(unknown)" : version, sha256, jar, currentJarText);
        atomicWrite(updatesDir.resolve("INSTALL-README.txt"), instructions.getBytes(StandardCharsets.UTF_8));

        String powershell = """
            $ErrorActionPreference = "Stop"
            Write-Host "Stop the Modrinth server before running this script."
            $GameDir = Resolve-Path (Join-Path $PSScriptRoot "..\\..")
            $ModsDir = Join-Path $GameDir "mods"
            $Jar = Join-Path $PSScriptRoot "%s"
            if (!(Test-Path -LiteralPath $ModsDir)) { throw "mods folder not found: $ModsDir" }
            if (!(Test-Path -LiteralPath $Jar)) { throw "staged jar not found: $Jar" }
            Get-ChildItem -LiteralPath $ModsDir -Filter "cobblemon-achievements-server-*.jar" | ForEach-Object {
                Move-Item -LiteralPath $_.FullName -Destination ($_.FullName + ".bak") -Force
            }
            Copy-Item -LiteralPath $Jar -Destination $ModsDir -Force
            Write-Host "Installed staged Cobblemon Achievements Server jar. Start the server and check /cach remote status."
            """.formatted(fileName);
        atomicWrite(updatesDir.resolve("install-staged-update.ps1"), powershell.getBytes(StandardCharsets.UTF_8));

        String shell = """
            #!/usr/bin/env sh
            set -eu
            echo "Stop the Modrinth server before running this script."
            SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
            GAME_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
            MODS_DIR="$GAME_DIR/mods"
            JAR="$SCRIPT_DIR/%s"
            test -d "$MODS_DIR"
            test -f "$JAR"
            for old in "$MODS_DIR"/cobblemon-achievements-server-*.jar; do
              if [ -f "$old" ]; then mv "$old" "$old.bak"; fi
            done
            cp "$JAR" "$MODS_DIR/"
            echo "Installed staged Cobblemon Achievements Server jar. Start the server and check /cach remote status."
            """.formatted(fileName);
        atomicWrite(updatesDir.resolve("install-staged-update.sh"), shell.getBytes(StandardCharsets.UTF_8));
    }

    private static Path stagedJarPath(Path cacheDir, String version, String sha256) {
        if (sha256 == null || sha256.isBlank()) return null;
        String safeVersion = safeName(version);
        if (safeVersion.isBlank()) safeVersion = "latest";
        String safeSha = safeName(sha256);
        if (safeSha.length() > 12) safeSha = safeSha.substring(0, 12);
        return cacheDir.resolve("updates").resolve("cobblemon-achievements-server-" + safeVersion + "-" + safeSha + ".jar");
    }

    private static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path cacheDir() {
        return FabricLoader.getInstance().getGameDir().resolve("cobblemon-achievements-remote");
    }

    private static String installedVersion() {
        return FabricLoader.getInstance().getModContainer(CobbleAchievementsMod.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("");
    }

    private static String read(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8).trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        return object.get(key).getAsString();
    }

    private static String safeName(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9._-]+", "_");
    }

    private static String statusMessage(String remoteMessage, boolean manifestChanged, UpdateResult update) {
        String message = remoteMessage == null || remoteMessage.isBlank() ? "remote manifest verified" : remoteMessage;
        if (!manifestChanged) return message + " | no GitHub manifest change";
        if (!update.downloadedPath.isBlank() && !update.installedPath.isBlank()) return message + " | update installed; restart required";
        if (!update.downloadedPath.isBlank()) return message + " | verified update staged; stop the server and install the staged jar manually";
        return message + " | GitHub manifest changed";
    }

    private record UpdateResult(String downloadedPath, String installedPath) {
        private static UpdateResult none() {
            return new UpdateResult("", "");
        }
    }

    public record Status(boolean ok, String checkedAt, String sourceUrl, String installedModVersion, String latestModVersion, String downloadedUpdatePath, String installedUpdatePath, boolean manifestChanged, boolean updateAvailable, String message) {
        private static Status initial() {
            return new Status(false, "", "", installedVersion(), "", "", "", false, false, "not checked yet");
        }
    }
}
