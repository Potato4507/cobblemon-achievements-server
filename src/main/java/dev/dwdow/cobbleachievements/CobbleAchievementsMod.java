package dev.dwdow.cobbleachievements;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class CobbleAchievementsMod implements ModInitializer {
    public static final String MOD_ID = "cobblemon_achievements_server";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ExecutorService REMOTE_REFRESH_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CobbleAchievements Remote Refresh");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean REMOTE_REFRESH_RUNNING = new AtomicBoolean(false);
    private static AchievementConfig config;
    private static AchievementState state;
    private static int ticks;
    private static int remoteTicks;
    private static MinecraftServer currentServer;

    @Override
    public void onInitialize() {
        config = AchievementConfig.load();
        state = AchievementState.load();
        registerCommands();
        registerEvents();
        BridgeNetworking.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> currentServer = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> currentServer = null);
        ServerTickEvents.END_SERVER_TICK.register(CobbleAchievementsMod::tick);
    }

    private static void registerEvents() {
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.LOWEST, CobbleAchievementsMod::onBattleVictory);
    }

    private static kotlin.Unit onBattleVictory(BattleVictoryEvent event) {
        try {
            List<ServerPlayerEntity> winners = playerActors(event.getWinners());
            List<ServerPlayerEntity> losers = playerActors(event.getLosers());
            if (winners.isEmpty() || losers.isEmpty()) return kotlin.Unit.INSTANCE;

            for (ServerPlayerEntity loser : losers) {
                AchievementConfig.TargetConfig target = targetForPlayer(loser);
                if (target == null || !target.active) continue;
                for (ServerPlayerEntity winner : winners) {
                    if (winner.getUuid().equals(loser.getUuid())) continue;
                    boolean fresh = state.award(winner.getUuidAsString(), winner.getGameProfile().getName(), target);
                    if (fresh) {
                        winner.sendMessage(Text.literal("[Achievements] " + target.title), false);
                        loser.sendMessage(Text.literal("[Achievements] " + winner.getGameProfile().getName() + " earned " + target.title + "."), false);
                    }
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Battle achievement check failed", error);
        }
        return kotlin.Unit.INSTANCE;
    }

    private static List<ServerPlayerEntity> playerActors(List<BattleActor> actors) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        for (BattleActor actor : actors) {
            for (UUID uuid : actor.getPlayerUUIDs()) {
                ServerPlayerEntity player = currentServer == null ? null : currentServer.getPlayerManager().getPlayer(uuid);
                if (player != null) players.add(player);
            }
        }
        return players;
    }

    private static void tick(MinecraftServer server) {
        currentServer = server;
        ticks++;
        if (ticks % 100 == 0) {
            try {
                PlayerBadgeManager.applyAll(server, config);
            } catch (Exception error) {
                LOGGER.warn("Player badge tick failed", error);
            }
        }
        int intervalTicks = Math.max(20, config.snapshotIntervalSeconds * 20);
        if (ticks % intervalTicks == 0) exportSnapshot(server);
        remoteTicks++;
        int remoteIntervalTicks = Math.max(1200, config.remoteManifestRefreshMinutes * 60 * 20);
        if (remoteTicks % remoteIntervalTicks == 0) startRemoteRefresh(server, null, false);
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("cach")
                .executes(context -> cachHelp(context.getSource()))
                .then(literal("help").executes(context -> cachHelp(context.getSource())))
                .then(literal("reload").requires(config::canManageTargets).executes(context -> {
                    config = AchievementConfig.load();
                    state = AchievementState.load();
                    PlayerBadgeManager.applyAll(context.getSource().getServer(), config);
                    feedback(context.getSource(), "Reloaded " + AchievementConfig.path());
                    return 1;
                }))
                .then(literal("active")
                    .then(literal("on").executes(context -> setActive(context.getSource(), true)))
                    .then(literal("off").executes(context -> setActive(context.getSource(), false))))
                .then(literal("target").requires(config::canManageTargets)
                    .then(literal("add")
                        .then(argument("player", EntityArgumentType.player()).executes(context ->
                            addTarget(context.getSource(), EntityArgumentType.getPlayer(context, "player"), "", "")
                        ).then(argument("achievementId", StringArgumentType.word()).executes(context ->
                            addTarget(context.getSource(), EntityArgumentType.getPlayer(context, "player"), StringArgumentType.getString(context, "achievementId"), "")
                        ).then(argument("title", StringArgumentType.greedyString()).executes(context ->
                            addTarget(context.getSource(), EntityArgumentType.getPlayer(context, "player"), StringArgumentType.getString(context, "achievementId"), StringArgumentType.getString(context, "title"))
                        )))))
                    .then(literal("remove").then(argument("player", EntityArgumentType.player()).executes(context ->
                        removeTarget(context.getSource(), EntityArgumentType.getPlayer(context, "player")))))
                    .then(literal("list").executes(context -> listTargets(context.getSource()))))
                .then(achievementCommand("ach"))
                .then(badgeCommand("badge").requires(config::canManageTargets))
                .then(literal("snapshot")
                    .then(literal("now").requires(config::canManageTargets).executes(context -> {
                        exportSnapshot(context.getSource().getServer());
                        feedback(context.getSource(), "Snapshot exported.");
                        return 1;
                    }))
                    .then(literal("toclient").requires(config::isOwner).executes(context -> {
                        sendOwnerBridgeSnapshot(context.getSource().getPlayerOrThrow(), "command");
                        feedback(context.getSource(), "Snapshot sent to your client mod.");
                        return 1;
                    })))
                .then(literal("remote").requires(config::isOwner)
                    .then(literal("status").executes(context -> remoteStatus(context.getSource())))
                    .then(literal("refresh").executes(context -> remoteRefresh(context.getSource()))))
                .then(literal("owner").requires(config::isOwner)
                    .then(literal("level")
                        .then(argument("slot", IntegerArgumentType.integer(1, 6))
                            .then(argument("level", IntegerArgumentType.integer(1, 100)).executes(context ->
                                ownerLevel(context.getSource(), IntegerArgumentType.getInteger(context, "slot") - 1, IntegerArgumentType.getInteger(context, "level"))))))
                    .then(literal("givepokemon")
                        .then(argument("properties", StringArgumentType.greedyString()).executes(context ->
                            ownerGivePokemon(context.getSource(), StringArgumentType.getString(context, "properties")))))
                    .then(literal("giveteam")
                        .then(argument("summaryJsonPath", StringArgumentType.greedyString()).executes(context ->
                            ownerGiveTeam(context.getSource(), StringArgumentType.getString(context, "summaryJsonPath")))))
                    .then(literal("giveitem")
                        .then(argument("item", StringArgumentType.word()).executes(context ->
                            ownerGiveItem(context.getSource(), StringArgumentType.getString(context, "item"), 1))
                        .then(argument("count", IntegerArgumentType.integer(1, 6400)).executes(context ->
                            ownerGiveItem(context.getSource(), StringArgumentType.getString(context, "item"), IntegerArgumentType.getInteger(context, "count"))))))
                    .then(literal("catchnear").executes(context -> ownerCatchNear(context.getSource(), 16))
                        .then(argument("radius", IntegerArgumentType.integer(1, 128)).executes(context ->
                            ownerCatchNear(context.getSource(), IntegerArgumentType.getInteger(context, "radius"))))))
            );
            dispatcher.register(badgeCommand("badge").requires(config::canManageTargets));
            dispatcher.register(badgeCommand("b").requires(config::canManageTargets));
            dispatcher.register(badgeCommand("typebadge").requires(config::canManageTargets));
            dispatcher.register(achievementCommand("ach"));
            dispatcher.register(achievementCommand("achievement"));
        });
    }

    private static int setActive(ServerCommandSource source, boolean active) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        AchievementConfig.TargetConfig target = config.targets.get(player.getUuidAsString());
        if (target == null) {
            feedback(source, "You are not configured as an achievement target.");
            return 0;
        }
        target.active = active;
        config.save();
        feedback(source, "Your achievement is now " + (active ? "active" : "inactive") + ".");
        return 1;
    }

    private static int addTarget(ServerCommandSource source, ServerPlayerEntity player, String achievementId, String title) {
        AchievementConfig.TargetConfig target = new AchievementConfig.TargetConfig(player, achievementId, title);
        config.targets.put(player.getUuidAsString(), target);
        config.save();
        feedback(source, "Added target " + target.name + " -> " + target.achievementId + ".");
        return 1;
    }

    private static int removeTarget(ServerCommandSource source, ServerPlayerEntity player) {
        config.targets.remove(player.getUuidAsString());
        config.save();
        feedback(source, "Removed target " + player.getGameProfile().getName() + ".");
        return 1;
    }

    private static int listTargets(ServerCommandSource source) {
        if (config.targets.isEmpty()) {
            feedback(source, "No achievement targets configured.");
            return 1;
        }
        for (AchievementConfig.TargetConfig target : config.targets.values()) {
            feedback(source, target.name + " -> " + target.achievementId + " (" + (target.active ? "active" : "inactive") + ")");
        }
        return 1;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> achievementCommand(String name) {
        return literal(name)
            .executes(context -> achievementHelp(context.getSource()))
            .then(literal("help").executes(context -> achievementHelp(context.getSource())))
            .then(literal("add").requires(config::canManageTargets)
                .then(argument("player", StringArgumentType.word()).executes(context ->
                    addAchievement(context.getSource(), StringArgumentType.getString(context, "player"), ""))
                    .then(argument("title", StringArgumentType.greedyString()).executes(context ->
                        addAchievement(context.getSource(), StringArgumentType.getString(context, "player"), StringArgumentType.getString(context, "title"))))))
            .then(literal("set").requires(config::canManageTargets)
                .then(argument("player", StringArgumentType.word()).executes(context ->
                    addAchievement(context.getSource(), StringArgumentType.getString(context, "player"), ""))
                    .then(argument("title", StringArgumentType.greedyString()).executes(context ->
                        addAchievement(context.getSource(), StringArgumentType.getString(context, "player"), StringArgumentType.getString(context, "title"))))))
            .then(literal("id").requires(config::canManageTargets)
                .then(argument("player", StringArgumentType.word())
                    .then(argument("id", StringArgumentType.word()).executes(context ->
                        setAchievementId(context.getSource(), StringArgumentType.getString(context, "player"), StringArgumentType.getString(context, "id"), ""))
                        .then(argument("title", StringArgumentType.greedyString()).executes(context ->
                            setAchievementId(context.getSource(), StringArgumentType.getString(context, "player"), StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "title")))))))
            .then(literal("remove").requires(config::canManageTargets)
                .then(argument("player", StringArgumentType.word()).executes(context ->
                    removeAchievement(context.getSource(), StringArgumentType.getString(context, "player")))))
            .then(literal("rm").requires(config::canManageTargets)
                .then(argument("player", StringArgumentType.word()).executes(context ->
                    removeAchievement(context.getSource(), StringArgumentType.getString(context, "player")))))
            .then(literal("list").requires(config::canManageTargets).executes(context -> listTargets(context.getSource())))
            .then(literal("ls").requires(config::canManageTargets).executes(context -> listTargets(context.getSource())))
            .then(literal("status").executes(context -> achievementStatus(context.getSource())))
            .then(literal("on").executes(context -> setActive(context.getSource(), true)))
            .then(literal("off").executes(context -> setActive(context.getSource(), false)));
    }

    private static int achievementHelp(ServerCommandSource source) {
        feedback(source, "Achievement help:");
        feedback(source, "/ach add <player> [title] - create the achievement for beating that player. OP only.");
        feedback(source, "/ach remove <player> - remove that achievement target. OP only.");
        feedback(source, "/ach list - show achievement targets. OP only.");
        feedback(source, "/ach status - show your achievement target status.");
        feedback(source, "/ach on or /ach off - let people earn or stop earning your achievement.");
        feedback(source, "Example: /ach add Steve Defeated the Ground Gym Leader");
        feedback(source, "Advanced: /ach id <player> <id> [title]. You usually do not need this.");
        return 1;
    }

    private static int addAchievement(ServerCommandSource source, String rawPlayerName, String rawTitle) {
        PlayerTarget targetPlayer = playerTarget(source, rawPlayerName);
        AchievementConfig.TargetConfig target = existingTarget(targetPlayer);
        if (target == null) {
            target = new AchievementConfig.TargetConfig();
            target.achievementId = AchievementConfig.TargetConfig.simpleId(targetPlayer.name());
        }
        target.uuid = targetPlayer.uuid();
        target.name = targetPlayer.name();
        if (target.achievementId == null || target.achievementId.isBlank()) {
            target.achievementId = AchievementConfig.TargetConfig.simpleId(target.name);
        }
        target.title = rawTitle == null || rawTitle.isBlank() ? "Defeated " + target.name : rawTitle;
        target.active = true;
        putAchievementTarget(targetPlayer, target);
        config.save();
        feedback(source, "Achievement target set: beat " + target.name + " -> " + target.title + " (id " + target.achievementId + ").");
        return 1;
    }

    private static int setAchievementId(ServerCommandSource source, String rawPlayerName, String rawId, String rawTitle) {
        PlayerTarget targetPlayer = playerTarget(source, rawPlayerName);
        AchievementConfig.TargetConfig target = existingTarget(targetPlayer);
        if (target == null) {
            target = new AchievementConfig.TargetConfig();
            target.title = "Defeated " + targetPlayer.name();
        }
        target.uuid = targetPlayer.uuid();
        target.name = targetPlayer.name();
        target.achievementId = AchievementConfig.TargetConfig.simpleId(rawId);
        if (rawTitle != null && !rawTitle.isBlank()) target.title = rawTitle;
        if (target.title == null || target.title.isBlank()) target.title = "Defeated " + target.name;
        target.active = true;
        putAchievementTarget(targetPlayer, target);
        config.save();
        feedback(source, "Advanced achievement target set: beat " + target.name + " -> " + target.title + " (id " + target.achievementId + ").");
        return 1;
    }

    private static int removeAchievement(ServerCommandSource source, String rawPlayerName) {
        PlayerTarget target = playerTarget(source, rawPlayerName);
        removeAchievementTarget(target);
        config.save();
        feedback(source, "Removed achievement target for " + target.name() + ".");
        return 1;
    }

    private static AchievementConfig.TargetConfig targetForPlayer(ServerPlayerEntity player) {
        if (player == null || config.targets == null) return null;
        String uuid = player.getUuidAsString();
        AchievementConfig.TargetConfig target = config.targets.get(uuid);
        if (target != null) {
            target.uuid = uuid;
            target.name = player.getGameProfile().getName();
            return target;
        }

        String playerName = player.getGameProfile().getName();
        String matchingKey = "";
        for (Map.Entry<String, AchievementConfig.TargetConfig> entry : config.targets.entrySet()) {
            AchievementConfig.TargetConfig candidate = entry.getValue();
            if (candidate == null || candidate.name == null) continue;
            if (candidate.name.equalsIgnoreCase(playerName)) {
                matchingKey = entry.getKey();
                target = candidate;
                break;
            }
        }
        if (target == null) return null;

        if (!uuid.equals(matchingKey)) {
            config.targets.remove(matchingKey);
            target.uuid = uuid;
            target.name = playerName;
            config.targets.put(uuid, target);
            config.save();
        }
        return target;
    }

    private static PlayerTarget playerTarget(ServerCommandSource source, String rawPlayerName) {
        String name = rawPlayerName == null || rawPlayerName.isBlank() ? "player" : rawPlayerName;
        ServerPlayerEntity online = onlinePlayer(source, name);
        if (online != null) {
            return new PlayerTarget(online.getUuidAsString(), online.getUuidAsString(), online.getGameProfile().getName(), online);
        }
        return new PlayerTarget("name:" + AchievementConfig.TargetConfig.simpleId(name), "", name, null);
    }

    private static AchievementConfig.TargetConfig existingTarget(PlayerTarget target) {
        AchievementConfig.TargetConfig configured = config.targets.get(target.key());
        if (configured != null) return configured;
        if (target.online() != null) {
            configured = targetForPlayer(target.online());
            if (configured != null) return configured;
        }
        for (AchievementConfig.TargetConfig candidate : config.targets.values()) {
            if (candidate != null && candidate.name != null && candidate.name.equalsIgnoreCase(target.name())) return candidate;
        }
        return null;
    }

    private static void putAchievementTarget(PlayerTarget target, AchievementConfig.TargetConfig achievement) {
        removeAchievementTarget(target);
        config.targets.put(target.key(), achievement);
    }

    private static void removeAchievementTarget(PlayerTarget target) {
        config.targets.entrySet().removeIf(entry -> {
            AchievementConfig.TargetConfig achievement = entry.getValue();
            if (entry.getKey().equals(target.key())) return true;
            if (!target.uuid().isBlank() && entry.getKey().equals(target.uuid())) return true;
            return achievement != null && achievement.name != null && achievement.name.equalsIgnoreCase(target.name());
        });
    }

    private record PlayerTarget(String key, String uuid, String name, ServerPlayerEntity online) {
    }

    private static int achievementStatus(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        AchievementConfig.TargetConfig target = targetForPlayer(player);
        if (target == null) {
            feedback(source, "You are not an achievement target. Ask an OP to run /ach add " + player.getGameProfile().getName());
            return 0;
        }
        feedback(source, "Your achievement is " + (target.active ? "active" : "inactive") + ": " + target.title + " (id " + target.achievementId + ").");
        return 1;
    }

    private static int cachHelp(ServerCommandSource source) {
        feedback(source, "CobbleAchievements help:");
        feedback(source, "/ach help - easy achievement commands.");
        feedback(source, "/b help - badge/type/gym leader commands.");
        feedback(source, "/cach active <on|off> - toggle your target achievement.");
        feedback(source, "/cach target add <player> [id] [title] - add a defeat achievement target. OP only.");
        feedback(source, "/cach target remove <player> - remove a target. OP only.");
        feedback(source, "/cach target list - show targets. OP only.");
        feedback(source, "/cach snapshot now - export player Pokemon snapshot. OP only.");
        feedback(source, "/cach remote status - show auto-update status. Owner only.");
        feedback(source, "/cach remote refresh - start auto-update check in the background. Owner only.");
        return 1;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> badgeCommand(String name) {
        return literal(name)
            .executes(context -> badgeHelp(context.getSource()))
            .then(literal("help").executes(context -> badgeHelp(context.getSource())))
            .then(literal("types").executes(context -> badgeTypes(context.getSource())))
            .then(literal("set")
                .then(argument("player", StringArgumentType.word())
                    .then(argument("type", StringArgumentType.word()).executes(context ->
                        setBadge(context.getSource(), StringArgumentType.getString(context, "player"), StringArgumentType.getString(context, "type"))))))
            .then(literal("clear")
                .then(argument("player", StringArgumentType.word()).executes(context ->
                    clearBadge(context.getSource(), StringArgumentType.getString(context, "player")))))
            .then(literal("remove")
                .then(argument("player", StringArgumentType.word()).executes(context ->
                    clearBadge(context.getSource(), StringArgumentType.getString(context, "player")))))
            .then(gymBadgeCommand("gym"))
            .then(gymBadgeCommand("leader"))
            .then(literal("list").executes(context -> listBadges(context.getSource())))
            .then(literal("ls").executes(context -> listBadges(context.getSource())))
            .then(literal("apply").executes(context -> applyBadges(context.getSource())));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> gymBadgeCommand(String name) {
        return literal(name)
            .then(argument("player", StringArgumentType.word())
                .then(literal("on").executes(context ->
                    setGymBadge(context.getSource(), StringArgumentType.getString(context, "player"), true, ""))
                    .then(argument("type", StringArgumentType.word()).executes(context ->
                        setGymBadge(context.getSource(), StringArgumentType.getString(context, "player"), true, StringArgumentType.getString(context, "type")))))
                .then(literal("off").executes(context ->
                    setGymBadge(context.getSource(), StringArgumentType.getString(context, "player"), false, ""))));
    }

    private static int badgeHelp(ServerCommandSource source) {
        feedback(source, "Badge help:");
        feedback(source, "/b set <player> <type> - set a player's type badge.");
        feedback(source, "/b gym <player> on [type] - mark a player as a gym leader.");
        feedback(source, "/b gym <player> off - remove the gym leader tag.");
        feedback(source, "/b clear <player> - remove all badge tags from a player.");
        feedback(source, "/b list - show saved badges.");
        feedback(source, "/b types - show valid Pokemon types.");
        feedback(source, "Example: /b set Steve ground");
        feedback(source, "Aliases: /badge, /typebadge, /cach badge.");
        return 1;
    }

    private static int badgeTypes(ServerCommandSource source) {
        feedback(source, "Valid badge types: " + PlayerBadgeManager.validTypesText());
        return 1;
    }

    private static int setBadge(ServerCommandSource source, String rawPlayerName, String rawType) {
        String type = PlayerBadgeManager.canonicalType(rawType);
        if (type.isBlank()) {
            feedback(source, "Unknown type '" + rawType + "'. Valid types: " + PlayerBadgeManager.validTypesText());
            return 0;
        }
        BadgeTarget target = badgeTarget(source, rawPlayerName);
        AchievementConfig.PlayerBadgeConfig badge = existingBadge(target);
        if (badge == null) {
            badge = new AchievementConfig.PlayerBadgeConfig();
        }
        badge.uuid = target.uuid();
        badge.name = target.name();
        badge.type = type;
        badge.active = true;
        putBadge(target, badge);
        config.save();
        applyBadgeTarget(target);
        feedback(source, "Set " + badge.name + " badge to " + type + (badge.gymLeader ? " gym leader" : "") + ".");
        return 1;
    }

    private static int setGymBadge(ServerCommandSource source, String rawPlayerName, boolean enabled, String rawType) {
        String type = "";
        if (rawType != null && !rawType.isBlank()) {
            type = PlayerBadgeManager.canonicalType(rawType);
            if (type.isBlank()) {
                feedback(source, "Unknown type '" + rawType + "'. Valid types: " + PlayerBadgeManager.validTypesText());
                return 0;
            }
        }

        BadgeTarget target = badgeTarget(source, rawPlayerName);
        AchievementConfig.PlayerBadgeConfig badge = existingBadge(target);
        if (badge == null) {
            badge = new AchievementConfig.PlayerBadgeConfig();
        }
        badge.uuid = target.uuid();
        badge.name = target.name();
        if (!type.isBlank()) badge.type = type;
        badge.gymLeader = enabled;
        badge.active = true;

        if (!enabled && (badge.type == null || badge.type.isBlank())) {
            removeBadge(target);
        } else {
            putBadge(target, badge);
        }

        config.save();
        applyBadgeTarget(target);
        feedback(source, (enabled ? "Added" : "Removed") + " gym leader tag for " + target.name() + ".");
        return 1;
    }

    private static int clearBadge(ServerCommandSource source, String rawPlayerName) {
        BadgeTarget target = badgeTarget(source, rawPlayerName);
        removeBadge(target);
        config.save();
        applyBadgeTarget(target);
        feedback(source, "Cleared badge for " + target.name() + ".");
        return 1;
    }

    private static BadgeTarget badgeTarget(ServerCommandSource source, String rawPlayerName) {
        PlayerTarget target = playerTarget(source, rawPlayerName);
        return new BadgeTarget(target.key(), target.uuid(), target.name(), target.online());
    }

    private static ServerPlayerEntity onlinePlayer(ServerCommandSource source, String name) {
        ServerPlayerEntity direct = source.getServer().getPlayerManager().getPlayer(name);
        if (direct != null) return direct;
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    private static AchievementConfig.PlayerBadgeConfig existingBadge(BadgeTarget target) {
        AchievementConfig.PlayerBadgeConfig badge = config.playerBadges.get(target.key());
        if (badge != null) return badge;
        if (target.online() != null) {
            badge = PlayerBadgeManager.badgeFor(target.online(), config);
            if (badge != null) return badge;
        }
        for (AchievementConfig.PlayerBadgeConfig candidate : config.playerBadges.values()) {
            if (candidate != null && candidate.name != null && candidate.name.equalsIgnoreCase(target.name())) return candidate;
        }
        return null;
    }

    private static void putBadge(BadgeTarget target, AchievementConfig.PlayerBadgeConfig badge) {
        removeBadge(target);
        config.playerBadges.put(target.key(), badge);
    }

    private static void removeBadge(BadgeTarget target) {
        config.playerBadges.entrySet().removeIf(entry -> {
            AchievementConfig.PlayerBadgeConfig badge = entry.getValue();
            if (entry.getKey().equals(target.key())) return true;
            if (!target.uuid().isBlank() && entry.getKey().equals(target.uuid())) return true;
            return badge != null && badge.name != null && badge.name.equalsIgnoreCase(target.name());
        });
    }

    private static void applyBadgeTarget(BadgeTarget target) {
        if (target.online() != null) PlayerBadgeManager.apply(target.online(), config);
    }

    private record BadgeTarget(String key, String uuid, String name, ServerPlayerEntity online) {
    }

    private static int listBadges(ServerCommandSource source) {
        if (config.playerBadges.isEmpty()) {
            feedback(source, "No player badges configured.");
            return 1;
        }
        for (AchievementConfig.PlayerBadgeConfig badge : config.playerBadges.values()) {
            String type = badge.type == null || badge.type.isBlank() ? "no type" : badge.type;
            String status = badge.active ? "active" : "inactive";
            feedback(source, badge.name + " -> " + (badge.gymLeader ? "Gym Leader, " : "") + type + " (" + status + ")");
        }
        return 1;
    }

    private static int applyBadges(ServerCommandSource source) {
        PlayerBadgeManager.applyAll(source.getServer(), config);
        feedback(source, "Applied player badges to online players.");
        return 1;
    }

    private static int ownerLevel(ServerCommandSource source, int slot, int level) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        Pokemon pokemon = party.get(slot);
        if (pokemon == null) {
            feedback(source, "No Pokemon in slot " + (slot + 1) + ".");
            return 0;
        }
        pokemon.setLevel(level);
        pokemon.heal();
        party.onPokemonChanged(pokemon);
        feedback(source, "Set " + pokemon.getDisplayName(false).getString() + " to level " + level + ".");
        return 1;
    }

    private static int remoteStatus(ServerCommandSource source) {
        RemoteManifestClient.Status status = RemoteManifestClient.status();
        reportRemoteStatus(source, "Remote manifest", status);
        return status.ok() ? 1 : 0;
    }

    private static int remoteRefresh(ServerCommandSource source) {
        return startRemoteRefresh(source.getServer(), source, true) ? 1 : 0;
    }

    private static void reportRemoteStatus(ServerCommandSource source, String label, RemoteManifestClient.Status status) {
        feedback(source, label + ": " + (status.ok() ? "ok" : "not ok") + " | " + status.message());
        if (!status.checkedAt().isBlank()) feedback(source, "Checked: " + status.checkedAt());
        if (!status.installedModVersion().isBlank()) feedback(source, "Installed version: " + status.installedModVersion());
        if (!status.latestModVersion().isBlank()) feedback(source, "Latest version: " + status.latestModVersion());
        if (!status.downloadedUpdatePath().isBlank()) feedback(source, "Downloaded update: " + status.downloadedUpdatePath());
        if (!status.installedUpdatePath().isBlank()) feedback(source, "Installed update: " + status.installedUpdatePath());
        if (status.updateAvailable()) feedback(source, "Restart required to load the update.");
    }

    private static boolean startRemoteRefresh(MinecraftServer server, ServerCommandSource source, boolean manual) {
        if (!REMOTE_REFRESH_RUNNING.compareAndSet(false, true)) {
            if (manual) feedback(source, "Remote refresh is already running.");
            return false;
        }
        if (manual) feedback(source, "Remote refresh started in the background.");
        AchievementConfig refreshConfig = config;
        REMOTE_REFRESH_EXECUTOR.execute(() -> {
            RemoteManifestClient.Status status;
            try {
                status = RemoteManifestClient.refresh(refreshConfig);
            } catch (Exception error) {
                LOGGER.warn("Remote manifest refresh failed", error);
                status = RemoteManifestClient.status();
            } finally {
                REMOTE_REFRESH_RUNNING.set(false);
            }

            RemoteManifestClient.Status finalStatus = status;
            try {
                server.execute(() -> {
                    if (manual && source != null) reportRemoteStatus(source, "Remote refresh", finalStatus);
                    if (!finalStatus.manifestChanged()) return;
                    String message = "[CobbleAchievements] GitHub update detected. " + finalStatus.message();
                    server.getPlayerManager().broadcast(Text.literal(message), false);
                });
            } catch (Exception error) {
                LOGGER.warn("Could not report remote manifest refresh result", error);
            }
        });
        return true;
    }

    private static int ownerGivePokemon(ServerCommandSource source, String rawProperties) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Pokemon pokemon = PokemonProperties.Companion.parse(rawProperties).create(player);
        boolean added = Cobblemon.INSTANCE.getStorage().getParty(player).add(pokemon);
        feedback(source, added ? "Added " + pokemon.getDisplayName(false).getString() + "." : "Could not add Pokemon.");
        return added ? 1 : 0;
    }

    private static int ownerGiveTeam(ServerCommandSource source, String summaryJsonPath) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        try {
            OptimizerTeamImporter.Result result = OptimizerTeamImporter.importTeam(player, summaryJsonPath);
            feedback(source, "Imported " + result.imported() + " Pokemon from optimizer team.");
            if (result.warnings() > 0) feedback(source, "Import warnings: " + result.warningText());
            return result.imported() > 0 ? 1 : 0;
        } catch (Exception error) {
            feedback(source, "Team import failed: " + error.getMessage());
            return 0;
        }
    }

    private static int ownerGiveItem(ServerCommandSource source, String rawItem, int count) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Identifier id = Identifier.tryParse(rawItem.contains(":") ? rawItem : "minecraft:" + rawItem);
        if (id == null || !Registries.ITEM.containsId(id)) {
            feedback(source, "Unknown item: " + rawItem);
            return 0;
        }
        Item item = Registries.ITEM.get(id);
        player.getInventory().offerOrDrop(new ItemStack(item, count));
        feedback(source, "Gave " + count + " " + id + ".");
        return 1;
    }

    private static int ownerCatchNear(ServerCommandSource source, int radius) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        PokemonEntity nearest = null;
        double best = radius * radius;
        Box searchBox = player.getBoundingBox().expand(radius);
        for (Entity entity : player.getWorld().getOtherEntities(player, searchBox)) {
            if (!(entity instanceof PokemonEntity pokemonEntity)) continue;
            double distance = entity.squaredDistanceTo(player);
            if (distance < best) {
                best = distance;
                nearest = pokemonEntity;
            }
        }
        if (nearest == null) {
            feedback(source, "No wild Pokemon found within " + radius + " blocks.");
            return 0;
        }
        Pokemon pokemon = nearest.getPokemon();
        nearest.discard();
        boolean added = Cobblemon.INSTANCE.getStorage().getParty(player).add(pokemon);
        feedback(source, added ? "Caught " + pokemon.getDisplayName(false).getString() + "." : "Could not add nearest Pokemon.");
        return added ? 1 : 0;
    }

    private static void exportSnapshot(MinecraftServer server) {
        try {
            JsonObject snapshot = ServerPokemonSnapshot.snapshot(server, config, state);
            ExportClient.exportAsync(config, snapshot);
        } catch (Exception error) {
            LOGGER.warn("Snapshot export failed", error);
        }
    }

    public static void sendOwnerBridgeSnapshot(ServerPlayerEntity player, String reason) {
        if (!config.isOwner(player)) return;
        try {
            JsonObject snapshot = ServerPokemonSnapshot.snapshot(player.getServer(), config, state);
            snapshot.addProperty("bridgeReason", reason == null ? "" : reason);
            BridgeNetworking.sendSnapshot(player, snapshot.toString());
        } catch (Exception error) {
            LOGGER.warn("Owner bridge snapshot failed", error);
        }
    }

    private static void feedback(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal("[CobbleAchievements] " + message), false);
    }
}
