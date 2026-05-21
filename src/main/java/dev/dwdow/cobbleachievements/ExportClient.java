package dev.dwdow.cobbleachievements;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

public final class ExportClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private ExportClient() {
    }

    public static void exportAsync(AchievementConfig config, JsonObject snapshot) {
        Thread thread = new Thread(() -> export(config, snapshot), "Cobblemon Achievements Export");
        thread.setDaemon(true);
        thread.start();
    }

    private static void export(AchievementConfig config, JsonObject snapshot) {
        writeLocal(snapshot);
        if (config.httpEndpoint != null && !config.httpEndpoint.isBlank()) postHttp(config, snapshot);
        if (hasGithub(config)) putGithub(config, snapshot);
    }

    private static void writeLocal(JsonObject snapshot) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("cobblemon-achievements-snapshots");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("latest.json"), GSON.toJson(snapshot) + "\n", StandardCharsets.UTF_8);
            String stamp = Instant.now().toString().replace(":", "-");
            Files.writeString(dir.resolve(stamp + ".json"), GSON.toJson(snapshot) + "\n", StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static void postHttp(AchievementConfig config, JsonObject snapshot) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.httpEndpoint))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("content-type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(snapshot), StandardCharsets.UTF_8))
                .build();
            HTTP.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    private static boolean hasGithub(AchievementConfig config) {
        return notBlank(config.githubToken) && notBlank(config.githubOwner) && notBlank(config.githubRepo) && notBlank(config.githubPath);
    }

    private static void putGithub(AchievementConfig config, JsonObject snapshot) {
        try {
            String api = "https://api.github.com/repos/" + enc(config.githubOwner) + "/" + enc(config.githubRepo)
                + "/contents/" + encodePath(config.githubPath);
            String branch = config.githubBranch == null || config.githubBranch.isBlank() ? "main" : config.githubBranch.trim();
            String sha = fetchGithubSha(api, branch, config.githubToken);

            JsonObject body = new JsonObject();
            body.addProperty("message", "Update Cobblemon player snapshot");
            body.addProperty("branch", branch);
            body.addProperty("content", Base64.getEncoder().encodeToString((GSON.toJson(snapshot) + "\n").getBytes(StandardCharsets.UTF_8)));
            if (!sha.isBlank()) body.addProperty("sha", sha);

            HttpRequest request = HttpRequest.newBuilder(URI.create(api))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("accept", "application/vnd.github+json")
                .header("authorization", "Bearer " + config.githubToken)
                .header("content-type", "application/json; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
            HTTP.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    private static String fetchGithubSha(String api, String branch, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(api + "?ref=" + enc(branch)))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("accept", "application/vnd.github+json")
                .header("authorization", "Bearer " + token)
                .GET()
                .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) return "";
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            return json.has("sha") ? json.get("sha").getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String path) {
        String[] parts = path.split("/");
        StringBuilder encoded = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!encoded.isEmpty()) encoded.append('/');
            encoded.append(enc(part));
        }
        return encoded.toString();
    }
}
