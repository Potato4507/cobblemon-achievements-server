package dev.dwdow.cobbleachievements;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public final class BridgeNetworking {
    public static final int CHUNK_SIZE = 28000;

    private BridgeNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(SnapshotRequestPayload.ID, SnapshotRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SnapshotChunkPayload.ID, SnapshotChunkPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SnapshotRequestPayload.ID, (payload, context) ->
            CobbleAchievementsMod.sendOwnerBridgeSnapshot(context.player(), payload.reason())
        );
    }

    public static void sendSnapshot(ServerPlayerEntity player, String json) {
        if (!ServerPlayNetworking.canSend(player, SnapshotChunkPayload.ID)) return;
        String transferId = java.util.UUID.randomUUID().toString();
        int total = Math.max(1, (json.length() + CHUNK_SIZE - 1) / CHUNK_SIZE);
        for (int index = 0; index < total; index++) {
            int start = index * CHUNK_SIZE;
            int end = Math.min(json.length(), start + CHUNK_SIZE);
            ServerPlayNetworking.send(player, new SnapshotChunkPayload(transferId, index, total, json.substring(start, end)));
        }
    }

    public record SnapshotRequestPayload(String reason) implements CustomPayload {
        public static final Id<SnapshotRequestPayload> ID = new Id<>(Identifier.of(CobbleAchievementsMod.MOD_ID, "snapshot_request"));
        public static final PacketCodec<RegistryByteBuf, SnapshotRequestPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.reason),
            buf -> new SnapshotRequestPayload(buf.readString())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record SnapshotChunkPayload(String transferId, int index, int total, String jsonChunk) implements CustomPayload {
        public static final Id<SnapshotChunkPayload> ID = new Id<>(Identifier.of(CobbleAchievementsMod.MOD_ID, "snapshot_chunk"));
        public static final PacketCodec<RegistryByteBuf, SnapshotChunkPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeString(payload.transferId);
                buf.writeVarInt(payload.index);
                buf.writeVarInt(payload.total);
                buf.writeString(payload.jsonChunk);
            },
            buf -> new SnapshotChunkPayload(buf.readString(), buf.readVarInt(), buf.readVarInt(), buf.readString())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
