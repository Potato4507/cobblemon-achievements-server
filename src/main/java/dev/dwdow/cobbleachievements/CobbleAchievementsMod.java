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
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class CobbleAchievementsMod implements ModInitializer {
    public static final String MOD_ID = "cobblemon_achievements_server";
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
        List<ServerPlayerEntity> winners = playerActors(event.getWinners());
        List<ServerPlayerEntity> losers = playerActors(event.getLosers());
        if (winners.isEmpty() || losers.isEmpty()) return kotlin.Unit.INSTANCE;

        for (ServerPlayerEntity loser : losers) {
            AchievementConfig.TargetConfig target = config.targets.get(loser.getUuidAsString());
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
        int intervalTicks = Math.max(20, config.snapshotIntervalSeconds * 20);
        if (ticks % intervalTicks == 0) exportSnapshot(server);
        remoteTicks++;
        int remoteIntervalTicks = Math.max(1200, config.remoteManifestRefreshMinutes * 60 * 20);
        if (remoteTicks % remoteIntervalTicks == 0) RemoteManifestClient.refresh(config);
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            literal("cach")
                .then(literal("reload").requires(config::canManageTargets).executes(context -> {
                    config = AchievementConfig.load();
                    state = AchievementState.load();
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
        ));
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
        feedback(source, "Remote manifest: " + (status.ok() ? "ok" : "not ok") + " | " + status.message());
        if (!status.latestModVersion().isBlank()) feedback(source, "Latest version: " + status.latestModVersion());
        if (!status.downloadedUpdatePath().isBlank()) feedback(source, "Downloaded update: " + status.downloadedUpdatePath());
        return status.ok() ? 1 : 0;
    }

    private static int remoteRefresh(ServerCommandSource source) {
        RemoteManifestClient.Status status = RemoteManifestClient.refresh(config);
        feedback(source, "Remote refresh: " + (status.ok() ? "ok" : "failed") + " | " + status.message());
        if (!status.downloadedUpdatePath().isBlank()) feedback(source, "Downloaded update: " + status.downloadedUpdatePath());
        return status.ok() ? 1 : 0;
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
        JsonObject snapshot = ServerPokemonSnapshot.snapshot(server, config, state);
        ExportClient.exportAsync(config, snapshot);
    }

    public static void sendOwnerBridgeSnapshot(ServerPlayerEntity player, String reason) {
        if (!config.isOwner(player)) return;
        JsonObject snapshot = ServerPokemonSnapshot.snapshot(player.getServer(), config, state);
        snapshot.addProperty("bridgeReason", reason == null ? "" : reason);
        BridgeNetworking.sendSnapshot(player, snapshot.toString());
    }

    private static void feedback(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal("[CobbleAchievements] " + message), false);
    }
}
