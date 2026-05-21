package dev.dwdow.cobbleachievements;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.abilities.Abilities;
import com.cobblemon.mod.common.api.abilities.AbilityTemplate;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Nature;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class OptimizerTeamImporter {
    private OptimizerTeamImporter() {
    }

    public static Result importTeam(ServerPlayerEntity player, String rawPath) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(Path.of(rawPath), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray team = findTeam(root);
        if (team == null || team.isEmpty()) return new Result(0, 0, "No team array found.");

        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        int imported = 0;
        int warnings = 0;
        StringBuilder warningText = new StringBuilder();
        int limit = Math.min(6, team.size());
        for (int i = 0; i < limit; i++) {
            JsonObject set = team.get(i).getAsJsonObject();
            ApplyResult applied = createAndApply(player, set);
            warnings += applied.warnings;
            if (!applied.warningText.isBlank()) {
                if (!warningText.isEmpty()) warningText.append("; ");
                warningText.append(applied.warningText);
            }
            if (party.add(applied.pokemon)) imported++;
        }
        return new Result(imported, warnings, warningText.toString());
    }

    private static ApplyResult createAndApply(ServerPlayerEntity player, JsonObject set) {
        int warnings = 0;
        StringBuilder warningText = new StringBuilder();
        String species = string(set, "species");
        Pokemon pokemon = createPokemon(player, species);
        pokemon.setLevel(100);

        String natureId = normalizedId(string(set, "nature"));
        Nature nature = natureId.isBlank() ? null : Natures.getNature(natureId);
        if (nature != null) pokemon.setNature(nature);
        else if (!natureId.isBlank()) warnings += warn(warningText, species + " nature " + natureId);

        String abilityId = normalizedId(string(set, "ability"));
        AbilityTemplate ability = abilityId.isBlank() ? null : Abilities.get(abilityId);
        if (ability != null) pokemon.setAbility$common(ability.create(true, Priority.LOWEST));
        else if (!abilityId.isBlank()) warnings += warn(warningText, species + " ability " + abilityId);

        warnings += applyStats(warningText, species, pokemon, set.getAsJsonObject("ivs"), true);
        warnings += applyStats(warningText, species, pokemon, set.getAsJsonObject("evs"), false);
        warnings += applyMoves(warningText, species, pokemon, set.getAsJsonArray("moves"));
        warnings += applyHeldItem(warningText, species, pokemon, string(set, "item"));
        applyGimmicks(pokemon, set.getAsJsonObject("gimmicks"));

        pokemon.heal();
        pokemon.updateAspects();
        pokemon.recalculateCharacteristic();
        return new ApplyResult(pokemon, warnings, warningText.toString());
    }

    private static Pokemon createPokemon(ServerPlayerEntity player, String species) {
        RuntimeException last = null;
        for (String properties : propertyCandidates(species)) {
            try {
                return PokemonProperties.Companion.parse(properties).create(player);
            } catch (RuntimeException error) {
                last = error;
            }
        }
        if (last != null) throw last;
        return PokemonProperties.Companion.parse("magikarp level=100").create(player);
    }

    private static String[] propertyCandidates(String species) {
        String simple = normalizedId(species);
        String resource = resourceId(species);
        if (species != null && species.contains("-")) {
            String[] parts = species.split("-", 2);
            return new String[]{
                species + " level=100",
                "species=" + normalizedId(parts[0]) + " form=" + normalizedId(parts[1]) + " level=100",
                "species=" + resourceId(parts[0]) + " form=" + resourceId(parts[1]) + " level=100",
                "species=" + simple + " level=100",
                "species=" + resource + " level=100"
            };
        }
        return new String[]{species + " level=100", "species=" + simple + " level=100", "species=" + resource + " level=100"};
    }

    private static int applyStats(StringBuilder warnings, String species, Pokemon pokemon, JsonObject stats, boolean ivs) {
        if (stats == null) return 0;
        int warningCount = 0;
        for (String key : stats.keySet()) {
            Stat stat = stat(key);
            if (stat == null) {
                warningCount += warn(warnings, species + " stat " + key);
                continue;
            }
            int value = stats.get(key).getAsInt();
            if (ivs) pokemon.getIvs().set(stat, value);
            else pokemon.getEvs().set(stat, value);
        }
        return warningCount;
    }

    private static int applyMoves(StringBuilder warnings, String species, Pokemon pokemon, JsonArray moves) {
        pokemon.getMoveSet().clear();
        if (moves == null) return 0;
        int warningCount = 0;
        for (int i = 0; i < Math.min(4, moves.size()); i++) {
            String moveName = moves.get(i).getAsString();
            MoveTemplate template = Moves.getByName(normalizedId(moveName));
            if (template == null) {
                warningCount += warn(warnings, species + " move " + moveName);
                continue;
            }
            pokemon.getMoveSet().setMove(i, template.create());
        }
        return warningCount;
    }

    private static int applyHeldItem(StringBuilder warnings, String species, Pokemon pokemon, String itemName) {
        if (itemName == null || itemName.isBlank()) return 0;
        Identifier id = Identifier.of("cobblemon", resourceId(itemName));
        if (!Registries.ITEM.containsId(id)) id = Identifier.of("minecraft", resourceId(itemName));
        if (!Registries.ITEM.containsId(id)) return warn(warnings, species + " item " + itemName);
        Item item = Registries.ITEM.get(id);
        pokemon.setHeldItem$common(new ItemStack(item, 1));
        pokemon.setHeldItemVisible(true);
        return 0;
    }

    private static void applyGimmicks(Pokemon pokemon, JsonObject gimmicks) {
        if (gimmicks == null) return;
        if (gimmicks.has("dynamax") && gimmicks.get("dynamax").getAsBoolean()) pokemon.setDmaxLevel(10);
    }

    private static JsonArray findTeam(JsonObject root) {
        if (root.has("team") && root.get("team").isJsonArray()) return root.getAsJsonArray("team");
        if (root.has("summaries") && root.get("summaries").isJsonArray() && !root.getAsJsonArray("summaries").isEmpty()) {
            JsonObject summary = root.getAsJsonArray("summaries").get(0).getAsJsonObject();
            if (summary.has("team") && summary.get("team").isJsonArray()) return summary.getAsJsonArray("team");
        }
        return null;
    }

    private static Stat stat(String key) {
        return switch (normalizedId(key)) {
            case "hp" -> Stats.HP;
            case "atk", "attack" -> Stats.ATTACK;
            case "def", "defence", "defense" -> Stats.DEFENCE;
            case "spa", "specialattack" -> Stats.SPECIAL_ATTACK;
            case "spd", "specialdefence", "specialdefense" -> Stats.SPECIAL_DEFENCE;
            case "spe", "speed" -> Stats.SPEED;
            default -> null;
        };
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        return object.get(key).getAsString();
    }

    private static String normalizedId(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String resourceId(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return normalized.replaceAll("^_+|_+$", "");
    }

    private static int warn(StringBuilder warnings, String message) {
        if (!warnings.isEmpty()) warnings.append(", ");
        warnings.append(message);
        return 1;
    }

    private record ApplyResult(Pokemon pokemon, int warnings, String warningText) {
    }

    public record Result(int imported, int warnings, String warningText) {
    }
}
