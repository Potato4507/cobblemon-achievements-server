package dev.dwdow.cobbleachievements;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCBox;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.time.Instant;
import java.util.function.Supplier;

public final class ServerPokemonSnapshot {
    private ServerPokemonSnapshot() {
    }

    public static JsonObject snapshot(MinecraftServer server, AchievementConfig config, AchievementState state) {
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("serverName", server == null ? "" : server.getServerMotd());
        root.add("achievements", state.toJson());

        JsonArray players = new JsonArray();
        if (server != null) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                players.add(playerSnapshot(player));
            }
        }
        root.add("players", players);
        return root;
    }

    public static JsonObject playerSnapshot(ServerPlayerEntity player) {
        JsonObject json = new JsonObject();
        json.addProperty("minecraftName", player.getGameProfile().getName());
        json.addProperty("minecraftUuid", player.getUuidAsString());
        try {
            PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
            PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
            json.add("party", party(party));
            json.add("pc", pc(pc));
            json.add("summary", summary(party, pc));
        } catch (Exception error) {
            json.addProperty("error", error.getClass().getSimpleName());
            json.addProperty("message", error.getMessage() == null ? "" : error.getMessage());
        }
        return json;
    }

    private static JsonObject party(PlayerPartyStore party) {
        JsonObject json = new JsonObject();
        JsonArray slots = new JsonArray();
        json.addProperty("uuid", safeString(() -> party.getUuid().toString()));
        for (int i = 0; i < party.size(); i++) {
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", i);
            Pokemon pokemon = party.get(i);
            if (pokemon != null) slot.add("pokemon", pokemon(pokemon));
            slots.add(slot);
        }
        json.add("slots", slots);
        return json;
    }

    private static JsonObject pc(PCStore pc) {
        JsonObject json = new JsonObject();
        JsonArray boxes = new JsonArray();
        json.addProperty("uuid", safeString(() -> pc.getUuid().toString()));
        json.addProperty("name", safeString(() -> pc.getName().getString()));
        for (int boxIndex = 0; boxIndex < pc.getBoxes().size(); boxIndex++) {
            PCBox box = pc.getBoxes().get(boxIndex);
            JsonObject boxJson = new JsonObject();
            JsonArray slots = new JsonArray();
            boxJson.addProperty("box", boxIndex);
            boxJson.addProperty("name", safeString(box::getName));
            for (int slotIndex = 0; slotIndex < 30; slotIndex++) {
                JsonObject slot = new JsonObject();
                slot.addProperty("slot", slotIndex);
                Pokemon pokemon = box.get(slotIndex);
                if (pokemon != null) slot.add("pokemon", pokemon(pokemon));
                slots.add(slot);
            }
            boxJson.add("slots", slots);
            boxes.add(boxJson);
        }
        json.add("boxes", boxes);
        return json;
    }

    private static JsonObject summary(PlayerPartyStore party, PCStore pc) {
        JsonObject json = new JsonObject();
        int pcPokemon = 0;
        for (Pokemon ignored : pc) pcPokemon++;
        json.addProperty("partySlots", party.size());
        json.addProperty("partyPokemon", party.occupied());
        json.addProperty("pcBoxes", pc.getBoxes().size());
        json.addProperty("pcPokemon", pcPokemon);
        json.addProperty("ownedPokemon", party.occupied() + pcPokemon);
        return json;
    }

    public static JsonObject pokemon(Pokemon pokemon) {
        JsonObject json = new JsonObject();
        if (pokemon == null) return json;
        json.addProperty("uuid", safeString(() -> pokemon.getUuid().toString()));
        json.addProperty("displayName", safeString(() -> pokemon.getDisplayName(false).getString()));
        json.addProperty("species", safeString(() -> pokemon.getSpecies().getName()));
        json.addProperty("form", safeString(() -> pokemon.getForm().getName()));
        json.addProperty("level", pokemon.getLevel());
        json.addProperty("currentHealth", pokemon.getCurrentHealth());
        json.addProperty("maxHealth", pokemon.getMaxHealth());
        json.addProperty("gender", safeString(() -> String.valueOf(pokemon.getGender())));
        json.addProperty("shiny", pokemon.getShiny());
        json.addProperty("nature", safeString(() -> pokemon.getNature().getName().toString()));
        json.addProperty("ability", safeString(() -> pokemon.getAbility().getName()));
        json.addProperty("teraType", safeString(() -> pokemon.getTeraType().getDisplayName().getString()));
        json.add("types", types(pokemon));
        json.add("moves", moves(pokemon));
        ItemStack held = pokemon.getHeldItem$common();
        if (held != null && !held.isEmpty()) {
            json.addProperty("heldItem", Registries.ITEM.getId(held.getItem()).toString());
            json.addProperty("heldItemName", held.getName().getString());
        }
        return json;
    }

    private static JsonArray types(Pokemon pokemon) {
        JsonArray array = new JsonArray();
        if (pokemon.getTypes() != null) {
            for (ElementalType type : pokemon.getTypes()) {
                if (type != null) array.add(type.getName());
            }
        }
        return array;
    }

    private static JsonArray moves(Pokemon pokemon) {
        JsonArray array = new JsonArray();
        if (pokemon.getMoveSet() == null) return array;
        for (Move move : pokemon.getMoveSet()) {
            if (move == null) continue;
            JsonObject json = new JsonObject();
            json.addProperty("name", safeString(move::getName));
            json.addProperty("displayName", safeString(() -> move.getDisplayName().getString()));
            json.addProperty("type", safeString(() -> move.getType().getName()));
            json.addProperty("category", safeString(() -> move.getDamageCategory().getName()));
            json.addProperty("power", move.getPower());
            json.addProperty("accuracy", move.getAccuracy());
            json.addProperty("pp", move.getCurrentPp());
            json.addProperty("maxPp", move.getMaxPp());
            array.add(json);
        }
        return array;
    }

    private static String safeString(Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
    }
}
