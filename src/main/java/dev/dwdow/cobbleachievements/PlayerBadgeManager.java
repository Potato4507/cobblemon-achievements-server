package dev.dwdow.cobbleachievements;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class PlayerBadgeManager {
    private static final TypeInfo NORMAL = new TypeInfo("normal", "Normal", "nor", 0xA8A77A, Formatting.GRAY);
    private static final TypeInfo FIRE = new TypeInfo("fire", "Fire", "fir", 0xEE8130, Formatting.RED);
    private static final TypeInfo WATER = new TypeInfo("water", "Water", "wat", 0x6390F0, Formatting.BLUE);
    private static final TypeInfo ELECTRIC = new TypeInfo("electric", "Electric", "ele", 0xF7D02C, Formatting.YELLOW);
    private static final TypeInfo GRASS = new TypeInfo("grass", "Grass", "gra", 0x7AC74C, Formatting.GREEN);
    private static final TypeInfo ICE = new TypeInfo("ice", "Ice", "ice", 0x96D9D6, Formatting.AQUA);
    private static final TypeInfo FIGHTING = new TypeInfo("fighting", "Fighting", "fig", 0xC22E28, Formatting.DARK_RED);
    private static final TypeInfo POISON = new TypeInfo("poison", "Poison", "poi", 0xA33EA1, Formatting.DARK_PURPLE);
    private static final TypeInfo GROUND = new TypeInfo("ground", "Ground", "gro", 0xE2BF65, Formatting.GOLD);
    private static final TypeInfo FLYING = new TypeInfo("flying", "Flying", "fly", 0xA98FF3, Formatting.LIGHT_PURPLE);
    private static final TypeInfo PSYCHIC = new TypeInfo("psychic", "Psychic", "psy", 0xF95587, Formatting.LIGHT_PURPLE);
    private static final TypeInfo BUG = new TypeInfo("bug", "Bug", "bug", 0xA6B91A, Formatting.GREEN);
    private static final TypeInfo ROCK = new TypeInfo("rock", "Rock", "roc", 0xB6A136, Formatting.GOLD);
    private static final TypeInfo GHOST = new TypeInfo("ghost", "Ghost", "gho", 0x735797, Formatting.DARK_PURPLE);
    private static final TypeInfo DRAGON = new TypeInfo("dragon", "Dragon", "dra", 0x6F35FC, Formatting.DARK_BLUE);
    private static final TypeInfo DARK = new TypeInfo("dark", "Dark", "dar", 0x705746, Formatting.DARK_GRAY);
    private static final TypeInfo STEEL = new TypeInfo("steel", "Steel", "ste", 0xB7B7CE, Formatting.GRAY);
    private static final TypeInfo FAIRY = new TypeInfo("fairy", "Fairy", "fai", 0xD685AD, Formatting.LIGHT_PURPLE);

    private static final TypeInfo[] TYPES = {
        NORMAL, FIRE, WATER, ELECTRIC, GRASS, ICE, FIGHTING, POISON, GROUND,
        FLYING, PSYCHIC, BUG, ROCK, GHOST, DRAGON, DARK, STEEL, FAIRY
    };
    private static final Map<String, TypeInfo> TYPES_BY_KEY = new LinkedHashMap<>();
    private static final String GYM_ONLY_TEAM = "ca_gym";

    static {
        for (TypeInfo type : TYPES) {
            TYPES_BY_KEY.put(type.key(), type);
            TYPES_BY_KEY.put(type.label().toLowerCase(Locale.ROOT), type);
        }
        TYPES_BY_KEY.put("elec", ELECTRIC);
        TYPES_BY_KEY.put("fight", FIGHTING);
        TYPES_BY_KEY.put("psy", PSYCHIC);
        TYPES_BY_KEY.put("dragon", DRAGON);
    }

    private PlayerBadgeManager() {
    }

    public static boolean isValidType(String rawType) {
        return type(rawType) != null;
    }

    public static String canonicalType(String rawType) {
        TypeInfo type = type(rawType);
        return type == null ? "" : type.label();
    }

    public static String validTypesText() {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < TYPES.length; index++) {
            if (index > 0) builder.append(", ");
            builder.append(TYPES[index].label());
        }
        return builder.toString();
    }

    public static void applyAll(MinecraftServer server, AchievementConfig config) {
        if (server == null || config == null) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
                apply(player, config);
            } catch (Exception error) {
                CobbleAchievementsMod.LOGGER.warn("Could not apply player badge for {}", player.getGameProfile().getName(), error);
            }
        }
    }

    public static void apply(ServerPlayerEntity player, AchievementConfig config) {
        if (player == null || config == null) return;
        Scoreboard scoreboard = player.getServer().getScoreboard();
        String scoreHolderName = player.getNameForScoreboard();
        Team current = scoreboard.getScoreHolderTeam(scoreHolderName);
        if (!config.playerBadgesEnabled) {
            removeFromBadgeTeams(scoreboard, scoreHolderName);
            return;
        }

        AchievementConfig.PlayerBadgeConfig badge = badgeFor(player, config);
        if (badge == null || !badge.active) {
            removeFromBadgeTeams(scoreboard, scoreHolderName);
            return;
        }
        TypeInfo type = type(badge.type);
        if (current != null && current != ensureTeam(scoreboard, type, badge.gymLeader) && !isBadgeTeam(scoreboard, current) && !config.playerBadgesOverrideExistingTeams) {
            return;
        }
        Team team = ensureTeam(scoreboard, type, badge.gymLeader);
        if (current != team) {
            scoreboard.addScoreHolderToTeam(scoreHolderName, team);
        }
    }

    public static AchievementConfig.PlayerBadgeConfig badgeFor(ServerPlayerEntity player, AchievementConfig config) {
        if (player == null || config == null || config.playerBadges == null) return null;
        String uuid = player.getUuidAsString();
        AchievementConfig.PlayerBadgeConfig badge = config.playerBadges.get(uuid);
        if (badge != null) return badge;

        String playerName = player.getGameProfile().getName();
        String matchingKey = "";
        for (Map.Entry<String, AchievementConfig.PlayerBadgeConfig> entry : config.playerBadges.entrySet()) {
            AchievementConfig.PlayerBadgeConfig candidate = entry.getValue();
            if (candidate == null || candidate.name == null) continue;
            if (candidate.name.equalsIgnoreCase(playerName)) {
                matchingKey = entry.getKey();
                badge = candidate;
                break;
            }
        }
        if (badge == null) return null;

        if (!uuid.equals(matchingKey)) {
            config.playerBadges.remove(matchingKey);
            badge.uuid = uuid;
            badge.name = playerName;
            config.playerBadges.put(uuid, badge);
            config.save();
        }
        return badge;
    }

    private static Team ensureTeam(Scoreboard scoreboard, TypeInfo type, boolean gymLeader) {
        String name = teamName(type, gymLeader);
        Team team = scoreboard.getTeam(name);
        if (team == null) {
            team = scoreboard.addTeam(name);
        }
        team.setDisplayName(Text.literal(name));
        team.setPrefix(prefix(type, gymLeader));
        team.setSuffix(Text.literal(""));
        team.setColor(type == null ? Formatting.GOLD : type.formatting());
        return team;
    }

    private static MutableText prefix(TypeInfo type, boolean gymLeader) {
        MutableText text = Text.literal("");
        if (gymLeader) {
            text.append(Text.literal("[GYM] ").styled(style -> style.withColor(0xFFD166).withBold(true)));
        }
        if (type != null) {
            text.append(Text.literal("[" + type.label().toUpperCase(Locale.ROOT) + "] ")
                .styled(style -> style.withColor(type.rgb()).withBold(true)));
        }
        return text;
    }

    private static void removeFromBadgeTeams(Scoreboard scoreboard, String scoreHolderName) {
        Team current = scoreboard.getScoreHolderTeam(scoreHolderName);
        if (current == null) return;
        removeFromTeam(scoreboard, scoreHolderName, current, GYM_ONLY_TEAM);
        for (TypeInfo type : TYPES) {
            removeFromTeam(scoreboard, scoreHolderName, current, teamName(type, false));
            removeFromTeam(scoreboard, scoreHolderName, current, teamName(type, true));
        }
    }

    private static void removeFromTeam(Scoreboard scoreboard, String scoreHolderName, Team current, String teamName) {
        Team team = scoreboard.getTeam(teamName);
        if (team != null && team == current) {
            scoreboard.removeScoreHolderFromTeam(scoreHolderName, team);
        }
    }

    private static boolean isBadgeTeam(Scoreboard scoreboard, Team team) {
        if (team == null) return false;
        if (scoreboard.getTeam(GYM_ONLY_TEAM) == team) return true;
        for (TypeInfo type : TYPES) {
            if (scoreboard.getTeam(teamName(type, false)) == team) return true;
            if (scoreboard.getTeam(teamName(type, true)) == team) return true;
        }
        return false;
    }

    private static String teamName(TypeInfo type, boolean gymLeader) {
        if (type == null) return GYM_ONLY_TEAM;
        return "ca_" + type.teamKey() + (gymLeader ? "_g" : "");
    }

    private static TypeInfo type(String rawType) {
        String key = key(rawType);
        return key.isBlank() ? null : TYPES_BY_KEY.get(key);
    }

    private static String key(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private record TypeInfo(String key, String label, String teamKey, int rgb, Formatting formatting) {
    }
}
