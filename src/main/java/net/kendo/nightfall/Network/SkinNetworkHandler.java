package net.kendo.nightfall.Network;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.kendo.nightfall.NightfallSkin;
import net.kendo.nightfall.ServerSkinManager;
import net.kendo.nightfall.SkinManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SkinNetworkHandler {
    public static final Identifier SYNC_SKIN_START = new Identifier("skinchanger", "sync_skin_start");
    public static final Identifier SYNC_SKIN_CHUNK = new Identifier("skinchanger", "sync_skin_chunk");
    public static final Identifier SYNC_SKIN_END = new Identifier("skinchanger", "sync_skin_end");
    public static final Identifier RESET_SKIN = new Identifier("skinchanger", "reset_skin");

    private static final int CHUNK_SIZE = 20000;
    private static int maxMultiplayerSize = 512;

    // Storage for assembling chunked data
    private static final Map<UUID, ChunkedSkinData> receivingData = new ConcurrentHashMap<>();

    private static class ChunkedSkinData {
        final int totalChunks;
        final Map<Integer, byte[]> chunks;
        final boolean isSlim;
        final long timestamp;

        ChunkedSkinData(int totalChunks, boolean isSlim) {
            this.totalChunks = totalChunks;
            this.chunks = new HashMap<>();
            this.isSlim = isSlim;
            this.timestamp = System.currentTimeMillis();
        }

        void addChunk(int index, byte[] data) {
            chunks.put(index, data);
        }

        boolean isComplete() {
            return chunks.size() == totalChunks;
        }

        byte[] assemble() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                if (chunk == null) {
                    throw new RuntimeException("Missing chunk " + i);
                }
                baos.write(chunk, 0, chunk.length);
            }
            return baos.toByteArray();
        }
    }

    public static void setMaxMultiplayerSize(int size) {
        maxMultiplayerSize = size;
        NightfallSkin.LOGGER.info("Set max multiplayer skin size to: {}x{}", size, size);
    }

    public static void registerClientReceivers() {
        // Start receiving skin
        ClientPlayNetworking.registerGlobalReceiver(SYNC_SKIN_START, (client, handler, buf, responseSender) -> {
            UUID playerUuid = buf.readUuid();
            boolean isSlim = buf.readBoolean();
            int totalChunks = buf.readInt();
            int totalSize = buf.readInt();

            NightfallSkin.LOGGER.info("Starting to receive skin for player {} ({} bytes in {} chunks)",
                    playerUuid, totalSize, totalChunks);

            client.execute(() -> {
                receivingData.put(playerUuid, new ChunkedSkinData(totalChunks, isSlim));
            });
        });

        // Receive skin chunk
        ClientPlayNetworking.registerGlobalReceiver(SYNC_SKIN_CHUNK, (client, handler, buf, responseSender) -> {
            UUID playerUuid = buf.readUuid();
            int chunkIndex = buf.readInt();
            int chunkSize = buf.readInt();
            byte[] chunkData = new byte[chunkSize];
            buf.readBytes(chunkData);

            client.execute(() -> {
                ChunkedSkinData data = receivingData.get(playerUuid);
                if (data != null) {
                    data.addChunk(chunkIndex, chunkData);
                    NightfallSkin.LOGGER.debug("Received chunk {}/{} for player {}",
                            chunkIndex + 1, data.totalChunks, playerUuid);
                }
            });
        });

        // Complete skin reception
        ClientPlayNetworking.registerGlobalReceiver(SYNC_SKIN_END, (client, handler, buf, responseSender) -> {
            UUID playerUuid = buf.readUuid();

            client.execute(() -> {
                ChunkedSkinData data = receivingData.remove(playerUuid);
                if (data != null && data.isComplete()) {
                    try {
                        byte[] fullData = data.assemble();
                        SkinManager.applyRemoteSkin(client, playerUuid, fullData, data.isSlim);
                        NightfallSkin.LOGGER.info("Successfully assembled and applied skin for player {}", playerUuid);
                    } catch (Exception e) {
                        NightfallSkin.LOGGER.error("Failed to assemble skin for player " + playerUuid, e);
                    }
                } else {
                    NightfallSkin.LOGGER.error("Failed to receive complete skin for player {}", playerUuid);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RESET_SKIN, (client, handler, buf, responseSender) -> {
            UUID playerUuid = buf.readUuid();
            NightfallSkin.LOGGER.info("Received skin reset for player {}", playerUuid);
            client.execute(() -> {});
        });
    }

    public static void registerServerReceivers() {
        // Receive start from client
        ServerPlayNetworking.registerGlobalReceiver(SYNC_SKIN_START, (server, player, handler, buf, responseSender) -> {
            UUID senderUuid = player.getUuid();
            boolean isSlim = buf.readBoolean();
            int totalChunks = buf.readInt();
            int totalSize = buf.readInt();

            NightfallSkin.LOGGER.info("Receiving skin from {} ({} bytes in {} chunks)",
                    player.getName().getString(), totalSize, totalChunks);

            server.execute(() -> {
                receivingData.put(senderUuid, new ChunkedSkinData(totalChunks, isSlim));
            });
        });

        // Receive chunk from client
        ServerPlayNetworking.registerGlobalReceiver(SYNC_SKIN_CHUNK, (server, player, handler, buf, responseSender) -> {
            UUID senderUuid = player.getUuid();
            int chunkIndex = buf.readInt();
            int chunkSize = buf.readInt();
            byte[] chunkData = new byte[chunkSize];
            buf.readBytes(chunkData);

            server.execute(() -> {
                ChunkedSkinData data = receivingData.get(senderUuid);
                if (data != null) {
                    data.addChunk(chunkIndex, chunkData);
                }
            });
        });

        // Receive end from client - broadcast to all players
        ServerPlayNetworking.registerGlobalReceiver(SYNC_SKIN_END, (server, player, handler, buf, responseSender) -> {
            UUID senderUuid = player.getUuid();

            server.execute(() -> {
                ChunkedSkinData data = receivingData.remove(senderUuid);
                if (data != null && data.isComplete()) {
                    try {
                        byte[] fullData = data.assemble();

                        // Store on server for new players joining later
                        ServerSkinManager.storeSkin(senderUuid, fullData, data.isSlim);

                        // Broadcast to all other players
                        for (ServerPlayerEntity targetPlayer : server.getPlayerManager().getPlayerList()) {
                            if (targetPlayer.getUuid().equals(senderUuid)) {
                                continue;
                            }

                            sendChunkedSkinToPlayer(targetPlayer, senderUuid, fullData, data.isSlim);
                        }

                        NightfallSkin.LOGGER.info("Broadcasted skin from {} to {} players",
                                player.getName().getString(), server.getPlayerManager().getPlayerList().size() - 1);
                    } catch (Exception e) {
                        NightfallSkin.LOGGER.error("Failed to broadcast skin", e);
                    }
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RESET_SKIN, (server, player, handler, buf, responseSender) -> {
            UUID senderUuid = player.getUuid();
            server.execute(() -> {
                // Remove from server storage
                ServerSkinManager.removeSkin(senderUuid);

                // Broadcast reset to all other players
                for (ServerPlayerEntity targetPlayer : server.getPlayerManager().getPlayerList()) {
                    if (targetPlayer.getUuid().equals(senderUuid)) continue;
                    try {
                        PacketByteBuf resetBuf = new PacketByteBuf(Unpooled.buffer());
                        resetBuf.writeUuid(senderUuid);
                        ServerPlayNetworking.send(targetPlayer, RESET_SKIN, resetBuf);
                    } catch (Exception e) {
                        NightfallSkin.LOGGER.error("Failed to send reset", e);
                    }
                }
            });
        });
    }

    public static void sendSkinData(byte[] imageData, boolean isSlim) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));

            // Always downscale to max size first
            if (image.getWidth() > maxMultiplayerSize || image.getHeight() > maxMultiplayerSize) {
                NightfallSkin.LOGGER.info("Downscaling skin from {}x{} to {}x{}",
                        image.getWidth(), image.getHeight(), maxMultiplayerSize, maxMultiplayerSize);
                image = downscaleImage(image, maxMultiplayerSize, maxMultiplayerSize);
            }

            // Convert to PNG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            byte[] pngData = baos.toByteArray();

            // If still too large, downscale more aggressively
            while (pngData.length > 100000 && image.getWidth() > 64) {
                int newSize = image.getWidth() / 2;
                NightfallSkin.LOGGER.warn("Skin still too large ({} bytes), downscaling to {}x{}",
                        pngData.length, newSize, newSize);
                image = downscaleImage(image, newSize, newSize);
                baos.reset();
                ImageIO.write(image, "PNG", baos);
                pngData = baos.toByteArray();
            }

            NightfallSkin.LOGGER.info("Final skin size: {}x{}, {} bytes",
                    image.getWidth(), image.getHeight(), pngData.length);

            sendChunkedData(pngData, isSlim);

        } catch (Exception e) {
            NightfallSkin.LOGGER.error("Failed to send skin data", e);
            throw new RuntimeException("Failed to send skin: " + e.getMessage());
        }
    }

    private static void sendChunkedData(byte[] data, boolean isSlim) {
        int totalChunks = (int) Math.ceil((double) data.length / CHUNK_SIZE);

        // Send start packet
        PacketByteBuf startBuf = new PacketByteBuf(Unpooled.buffer());
        startBuf.writeBoolean(isSlim);
        startBuf.writeInt(totalChunks);
        startBuf.writeInt(data.length);
        ClientPlayNetworking.send(SYNC_SKIN_START, startBuf);

        // Send chunks
        for (int i = 0; i < totalChunks; i++) {
            int offset = i * CHUNK_SIZE;
            int chunkSize = Math.min(CHUNK_SIZE, data.length - offset);

            PacketByteBuf chunkBuf = new PacketByteBuf(Unpooled.buffer());
            chunkBuf.writeInt(i);
            chunkBuf.writeInt(chunkSize);
            chunkBuf.writeBytes(data, offset, chunkSize);
            ClientPlayNetworking.send(SYNC_SKIN_CHUNK, chunkBuf);
        }

        // Send end packet
        PacketByteBuf endBuf = new PacketByteBuf(Unpooled.buffer());
        ClientPlayNetworking.send(SYNC_SKIN_END, endBuf);

        NightfallSkin.LOGGER.info("Sent {} chunks ({} bytes total)", totalChunks, data.length);
    }

    private static void sendChunkedSkinToPlayer(ServerPlayerEntity player, UUID skinOwnerUuid, byte[] data, boolean isSlim) {
        try {
            int totalChunks = (int) Math.ceil((double) data.length / CHUNK_SIZE);

            // Send start
            PacketByteBuf startBuf = new PacketByteBuf(Unpooled.buffer());
            startBuf.writeUuid(skinOwnerUuid);
            startBuf.writeBoolean(isSlim);
            startBuf.writeInt(totalChunks);
            startBuf.writeInt(data.length);
            ServerPlayNetworking.send(player, SYNC_SKIN_START, startBuf);

            // Send chunks
            for (int i = 0; i < totalChunks; i++) {
                int offset = i * CHUNK_SIZE;
                int chunkSize = Math.min(CHUNK_SIZE, data.length - offset);

                PacketByteBuf chunkBuf = new PacketByteBuf(Unpooled.buffer());
                chunkBuf.writeUuid(skinOwnerUuid);
                chunkBuf.writeInt(i);
                chunkBuf.writeInt(chunkSize);
                chunkBuf.writeBytes(data, offset, chunkSize);
                ServerPlayNetworking.send(player, SYNC_SKIN_CHUNK, chunkBuf);
            }

            // Send end
            PacketByteBuf endBuf = new PacketByteBuf(Unpooled.buffer());
            endBuf.writeUuid(skinOwnerUuid);
            ServerPlayNetworking.send(player, SYNC_SKIN_END, endBuf);

            NightfallSkin.LOGGER.debug("Sent skin {} to {} in {} chunks", skinOwnerUuid, player.getName().getString(), totalChunks);
        } catch (Exception e) {
            NightfallSkin.LOGGER.error("Failed to send chunked skin", e);
        }
    }

    private static BufferedImage downscaleImage(BufferedImage original, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return resized;
    }

    public static void sendSkinReset() {
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            ClientPlayNetworking.send(RESET_SKIN, buf);
        } catch (Exception e) {
            NightfallSkin.LOGGER.error("Failed to send skin reset", e);
        }
    }

    public static void sendAllSkinsToPlayer(ServerPlayerEntity newPlayer, java.util.Collection<ServerPlayerEntity> allPlayers) {
        for (ServerPlayerEntity player : allPlayers) {
            if (player.getUuid().equals(newPlayer.getUuid())) continue;

            // Use ServerSkinManager instead of SkinManager
            ServerSkinManager.SkinData skinData = ServerSkinManager.getSkinData(player.getUuid());
            if (skinData != null && skinData.imageData != null) {
                try {
                    sendChunkedSkinToPlayer(newPlayer, player.getUuid(), skinData.imageData, skinData.isSlim);
                } catch (Exception e) {
                    NightfallSkin.LOGGER.error("Failed to send skin to new player", e);
                }
            }
        }
    }
}