package dev.dwdow.cobbleachievements;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AchievementState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("cobblemon-achievements-state.json");

    public Map<String, PlayerAwards> players = new LinkedHashMap<>();

    public static AchievementState load() {
        if (!Files.exists(PATH)) return new AchievementState();
        try {
            AchievementState state = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), AchievementState.class);
            if (state == null) state = new AchievementState();
            if (state.players == null) state.players = new LinkedHashMap<>();
            return state;
        } catch (Exception ignored) {
            return new AchievementState();
        }
    }

    public boolean award(String playerUuid, String playerName, AchievementConfig.TargetConfig target) {
        PlayerAwards awards = players.computeIfAbsent(playerUuid, ignored -> new PlayerAwards(playerName));
        awards.name = playerName;
        if (awards.achievements.containsKey(target.achievementId)) return false;
        Award award = new Award();
        award.id = target.achievementId;
        award.title = target.title;
        award.targetUuid = target.uuid;
        award.targetName = target.name;
        award.awardedAt = Instant.now().toString();
        awards.achievements.put(target.achievementId, award);
        save();
        return true;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonArray playerArray = new JsonArray();
        for (Map.Entry<String, PlayerAwards> entry : players.entrySet()) {
            JsonObject player = new JsonObject();
            player.addProperty("uuid", entry.getKey());
            player.addProperty("name", entry.getValue().name);
            JsonArray achievements = new JsonArray();
            for (Award award : entry.getValue().achievements.values()) achievements.add(GSON.toJsonTree(award));
            player.add("achievements", achievements);
            playerArray.add(player);
        }
        root.add("players", playerArray);
        return root;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static final class PlayerAwards {
        public String name = "";
        public Map<String, Award> achievements = new LinkedHashMap<>();

        public PlayerAwards() {
        }

        public PlayerAwards(String name) {
            this.name = name;
        }
    }

    public static final class Award {
        public String id = "";
        public String title = "";
        public String targetUuid = "";
        public String targetName = "";
        public String awardedAt = "";
    }
}
