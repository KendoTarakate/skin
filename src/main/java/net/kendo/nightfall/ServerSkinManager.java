package net.kendo.nightfall;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side skin data storage
 * Separate from SkinManager to avoid client-only dependencies
 */
public class ServerSkinManager {
    // Store skins by UUID on server
    private static final Map<UUID, SkinData> serverSkins = new HashMap<>();

    public static class SkinData {
        public final byte[] imageData;
        public final boolean isSlim;
        public final long timestamp;

        public SkinData(byte[] imageData, boolean isSlim) {
            this.imageData = imageData;
            this.isSlim = isSlim;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Store a player's skin data on the server
     */
    public static void storeSkin(UUID playerUuid, byte[] imageData, boolean isSlim) {
        serverSkins.put(playerUuid, new SkinData(imageData, isSlim));
        NightfallSkin.LOGGER.info("Stored skin for player {} ({} bytes)", playerUuid, imageData.length);
    }

    /**
     * Get a player's skin data
     */
    public static SkinData getSkinData(UUID playerUuid) {
        return serverSkins.get(playerUuid);
    }

    /**
     * Check if a player has a custom skin
     */
    public static boolean hasSkin(UUID playerUuid) {
        return serverSkins.containsKey(playerUuid);
    }

    /**
     * Remove a player's skin
     */
    public static void removeSkin(UUID playerUuid) {
        serverSkins.remove(playerUuid);
        NightfallSkin.LOGGER.info("Removed skin for player {}", playerUuid);
    }

    /**
     * Get all stored skins
     */
    public static Map<UUID, SkinData> getAllSkins() {
        return new HashMap<>(serverSkins);
    }

    /**
     * Clear all skins
     */
    public static void clearAllSkins() {
        serverSkins.clear();
    }
}