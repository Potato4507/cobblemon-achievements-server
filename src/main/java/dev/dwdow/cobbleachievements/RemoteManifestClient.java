package dev.dwdow.cobbleachievements;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

public final class RemoteManifestClient {
    private static final String PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAgo9uAeO6x3qtuVVT/uJWe3ABugdGUiZn4ETSzOpH/II=";
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("manifest HTTP " + response.statusCode());
        }

        String manifestSha256 = sha256(response.body().getBytes(StandardCharsets.UTF_8));
        JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
        byte[] payloadBytes = Base64.getDecoder().decode(envelope.get("payloadBase64").getAsString());
        byte[] signatureBytes = Base64.getDecoder().decode(envelope.get("signatureBase64").getAsString());
        verify(payloadBytes, signatureBytes);

        JsonObject payload = JsonParser.parseString(new String(payloadBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        Path cacheDir = cacheDir();
        Files.createDirectories(cacheDir);
        String previousManifestSha256 = read(cacheDir.resolve("manifest.sha256"));
        boolean manifestChanged = !manifestSha256.equals(previousManifestSha256);
        Files.writeString(cacheDir.resolve("manifest.payload.json"), new String(payloadBytes, StandardCharsets.UTF_8) + "\n", StandardCharsets.UTF_8);
        Files.writeString(cacheDir.resolve("manifest.sha256"), manifestSha256 + "\n", StandardCharsets.UTF_8);
        cacheDataFiles(cacheDir, payload);
        UpdateResult update = manifestChanged && config.remoteUpdateDownloadEnabled ? downloadUpdate(cacheDir, payload) : UpdateResult.none();

        String installed = installedVersion();
        String version = string(payload, "latestModVersion");
        boolean updateAvailable = !version.isBlank() && !version.equals(installed);
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
            byte[] bytes = downloadBytes(url);
            if (!sha256.isBlank() && !sha256.equalsIgnoreCase(sha256(bytes))) {
                throw new SecurityException("sha256 mismatch for remote file " + id);
            }
            Files.write(filesDir.resolve(id + ".json"), bytes);
        }
    }

    private static UpdateResult downloadUpdate(Path cacheDir, JsonObject payload) throws Exception {
        String downloadUrl = string(payload, "downloadUrl");
        byte[] bytes = downloadUrl.isBlank() ? downloadChunkedBase64(payload) : downloadBytes(downloadUrl);
        if (bytes.length == 0) return UpdateResult.none();
        String expected = string(payload, "downloadSha256");
        if (!expected.isBlank() && !expected.equalsIgnoreCase(sha256(bytes))) {
            throw new SecurityException("downloadSha256 mismatch");
        }
        Path updatesDir = cacheDir.resolve("updates");
        Files.createDirectories(updatesDir);
        Path jar = updatesDir.resolve("cobblemon-achievements-server-latest.jar");
        Files.write(jar, bytes);
        return new UpdateResult(jar.toString(), tryInstallUpdate(jar));
    }

    private static String tryInstallUpdate(Path downloadedJar) {
        try {
            Path currentJar = currentModJar();
            if (currentJar == null) return "";
            Files.copy(downloadedJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
            return currentJar.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static byte[] downloadChunkedBase64(JsonObject payload) throws Exception {
        if (!payload.has("downloadBase64Chunks") || !payload.get("downloadBase64Chunks").isJsonArray()) return new byte[0];
        StringBuilder encoded = new StringBuilder();
        JsonArray chunks = payload.getAsJsonArray("downloadBase64Chunks");
        for (int i = 0; i < chunks.size(); i++) {
            String url = chunks.get(i).getAsString();
            if (url == null || url.isBlank()) continue;
            encoded.append(new String(downloadBytes(url), StandardCharsets.UTF_8).replaceAll("\\s+", ""));
        }
        return encoded.isEmpty() ? new byte[0] : Base64.getDecoder().decode(encoded.toString());
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

    private static byte[] downloadBytes(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("download HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
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
        if (!update.downloadedPath.isBlank()) return message + " | update downloaded; manual jar swap may be needed before restart";
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
