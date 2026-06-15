package dev.dwdow.cobbleachievements;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeNetworking {
    public static final int CHUNK_SIZE = 28000;
    private static final int MAX_TEAM_UPLOAD_CHUNKS = 64;
    private static final Map<String, TeamTransfer> TEAM_TRANSFERS = new ConcurrentHashMap<>();

    private BridgeNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(SnapshotRequestPayload.ID, SnapshotRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(TeamUploadChunkPayload.ID, TeamUploadChunkPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SnapshotChunkPayload.ID, SnapshotChunkPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TeamUploadStatusPayload.ID, TeamUploadStatusPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SnapshotRequestPayload.ID, (payload, context) ->
            CobbleAchievementsMod.sendOwnerBridgeSnapshot(context.player(), payload.reason())
        );
        ServerPlayNetworking.registerGlobalReceiver(TeamUploadChunkPayload.ID, BridgeNetworking::receiveTeamUpload);
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

    private static void receiveTeamUpload(TeamUploadChunkPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayerEntity player = context.player();
        if (player == null) return;
        if (!CobbleAchievementsMod.isOwnerBridgePlayer(player)) {
            sendTeamStatus(player, payload.transferId, false, "Team upload refused: this bridge is owner-only.");
            return;
        }
        if (payload.total < 1 || payload.total > MAX_TEAM_UPLOAD_CHUNKS || payload.index < 0 || payload.index >= payload.total) {
            sendTeamStatus(player, payload.transferId, false, "Team upload refused: invalid chunk data.");
            return;
        }
        String key = player.getUuidAsString() + ":" + payload.transferId;
        TeamTransfer transfer = TEAM_TRANSFERS.computeIfAbsent(key, ignored -> new TeamTransfer(payload.label, payload.total));
        if (payload.index >= 0 && payload.index < transfer.chunks.length) {
            transfer.chunks[payload.index] = payload.jsonChunk;
        }
        if (!transfer.complete()) return;
        TEAM_TRANSFERS.remove(key);
        String json = String.join("", transfer.chunks);
        context.server().execute(() -> {
            CobbleAchievementsMod.BridgeTeamImportResult result = CobbleAchievementsMod.importOwnerTeamFromBridge(player, transfer.label, json);
            sendTeamStatus(player, payload.transferId, result.ok(), result.message());
        });
    }

    private static void sendTeamStatus(ServerPlayerEntity player, String transferId, boolean ok, String message) {
        if (player == null || !ServerPlayNetworking.canSend(player, TeamUploadStatusPayload.ID)) return;
        ServerPlayNetworking.send(player, new TeamUploadStatusPayload(transferId, ok, message == null ? "" : message));
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

    public record TeamUploadChunkPayload(String transferId, String label, int index, int total, String jsonChunk) implements CustomPayload {
        public static final Id<TeamUploadChunkPayload> ID = new Id<>(Identifier.of(CobbleAchievementsMod.MOD_ID, "team_upload_chunk"));
        public static final PacketCodec<RegistryByteBuf, TeamUploadChunkPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeString(payload.transferId);
                buf.writeString(payload.label);
                buf.writeVarInt(payload.index);
                buf.writeVarInt(payload.total);
                buf.writeString(payload.jsonChunk);
            },
            buf -> new TeamUploadChunkPayload(buf.readString(), buf.readString(), buf.readVarInt(), buf.readVarInt(), buf.readString())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record TeamUploadStatusPayload(String transferId, boolean ok, String message) implements CustomPayload {
        public static final Id<TeamUploadStatusPayload> ID = new Id<>(Identifier.of(CobbleAchievementsMod.MOD_ID, "team_upload_status"));
        public static final PacketCodec<RegistryByteBuf, TeamUploadStatusPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeString(payload.transferId);
                buf.writeBoolean(payload.ok);
                buf.writeString(payload.message);
            },
            buf -> new TeamUploadStatusPayload(buf.readString(), buf.readBoolean(), buf.readString())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private record TeamTransfer(String label, String[] chunks) {
        private TeamTransfer(String label, int total) {
            this(label == null ? "" : label, new String[Math.max(1, total)]);
        }

        private boolean complete() {
            for (String chunk : chunks) {
                if (chunk == null) return false;
            }
            return true;
        }
    }
}
