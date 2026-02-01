package net.kendo.nightfall;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ServerSkinStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path skinsDirectory;
    private static final Map<UUID, SkinData> skinCache = new HashMap<>();

    public static class SkinData {
        public String playerName;
        public UUID playerUuid;
        public String skinFileName;
        public boolean isSlim;
        public long timestamp;

        public SkinData(String playerName, UUID playerUuid, String skinFileName, boolean isSlim) {
            this.playerName = playerName;
            this.playerUuid = playerUuid;
            this.skinFileName = skinFileName;
            this.isSlim = isSlim;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Initialize storage directory
     */
    public static void initialize(MinecraftServer server) {
        try {
            NightfallSkin.LOGGER.info("Starting skin storage initialization...");

            // Get server directory
            Path serverDir = server.getSavePath(WorldSavePath.ROOT);
            NightfallSkin.LOGGER.info("Server directory: {}", serverDir.toAbsolutePath());

            skinsDirectory = serverDir.resolve("skinchanger");
            NightfallSkin.LOGGER.info("Skins directory will be: {}", skinsDirectory.toAbsolutePath());

            // Create directories
            Files.createDirectories(skinsDirectory);
            Files.createDirectories(skinsDirectory.resolve("images"));
            Files.createDirectories(skinsDirectory.resolve("data"));

            NightfallSkin.LOGGER.info("=== Skin storage initialized successfully ===");
            NightfallSkin.LOGGER.info("Location: {}", skinsDirectory.toAbsolutePath());
            NightfallSkin.LOGGER.info("Images: {}", skinsDirectory.resolve("images").toAbsolutePath());
            NightfallSkin.LOGGER.info("Data: {}", skinsDirectory.resolve("data").toAbsolutePath());

            // Verify directories were created
            if (Files.exists(skinsDirectory)) {
                NightfallSkin.LOGGER.info("✓ Main directory exists");
            } else {
                NightfallSkin.LOGGER.error("✗ Main directory was NOT created!");
            }

            // Load existing skins
            loadAllSkins();

        } catch (Exception e) {
            NightfallSkin.LOGGER.error("Failed to initialize skin storage", e);
            e.printStackTrace();
        }
    }

    /**
     * Initialize with manual path (fallback)
     */
    public static void initializeManual(Path serverWorldPath) {
        try {
            NightfallSkin.LOGGER.info("Manual initialization with path: {}", serverWorldPath.toAbsolutePath());

            skinsDirectory = serverWorldPath.resolve("skinchanger");

            // Create directories
            Files.createDirectories(skinsDirectory);
            Files.createDirectories(skinsDirectory.resolve("images"));
            Files.createDirectories(skinsDirectory.resolve("data"));

            NightfallSkin.LOGGER.info("Skin storage manually initialized at: {}", skinsDirectory.toAbsolutePath());

            // Load existing skins
            loadAllSkins();

        } catch (Exception e) {
            NightfallSkin.LOGGER.error("Failed to manually initialize skin storage", e);
            e.printStackTrace();
        }
    }

    /**
     * Check if storage is initialized
     */
    public static boolean isInitialized() {
        return skinsDirectory != null && Files.exists(skinsDirectory);
    }

    /**
     * Save a player's skin to server storage
     */
    public static boolean saveSkin(String playerName, UUID playerUuid, BufferedImage skinImage, boolean isSlim) {
        try {
            NightfallSkin.LOGGER.info("=== SAVING SKIN ===");
            NightfallSkin.LOGGER.info("Player: {} ({})", playerName, playerUuid);
            NightfallSkin.LOGGER.info("Image: {}x{}", skinImage.getWidth(), skinImage.getHeight());
            NightfallSkin.LOGGER.info("Slim: {}", isSlim);

            if (skinsDirectory == null) {
                NightfallSkin.LOGGER.error("✗ skinsDirectory is NULL! Storage not initialized!");
                return false;
            }

            NightfallSkin.LOGGER.info("Skins directory: {}", skinsDirectory.toAbsolutePath());
            NightfallSkin.LOGGER.info("Directory exists: {}", Files.exists(skinsDirectory));

            if (!Files.exists(skinsDirectory)) {
                NightfallSkin.LOGGER.warn("Directory doesn't exist, creating now...");
                Files.createDirectories(skinsDirectory);
                Files.createDirectories(skinsDirectory.resolve("images"));
                Files.createDirectories(skinsDirectory.resolve("data"));
                NightfallSkin.LOGGER.info("✓ Directories created");
            }

            // Verify subdirectories exist
            Path imagesDir = skinsDirectory.resolve("images");
            Path dataDir = skinsDirectory.resolve("data");

            NightfallSkin.LOGGER.info("Images dir exists: {}", Files.exists(imagesDir));
            NightfallSkin.LOGGER.info("Data dir exists: {}", Files.exists(dataDir));

            // Generate filenames
            String skinFileName = playerUuid.toString() + ".png";
            String dataFileName = playerUuid.toString() + ".json";

            // Save skin image
            Path imagePath = imagesDir.resolve(skinFileName);
            NightfallSkin.LOGGER.info("Saving image to: {}", imagePath.toAbsolutePath());

            File imageFile = imagePath.toFile();
            NightfallSkin.LOGGER.info("Parent directory writable: {}", imageFile.getParentFile().canWrite());

            boolean imageWritten = ImageIO.write(skinImage, "PNG", imageFile);

            if (!imageWritten) {
                NightfallSkin.LOGGER.error("✗ ImageIO.write returned false!");
                return false;
            }

            if (Files.exists(imagePath)) {
                long fileSize = Files.size(imagePath);
                NightfallSkin.LOGGER.info("✓ Image saved successfully ({} bytes)", fileSize);
            } else {
                NightfallSkin.LOGGER.error("✗ Image file doesn't exist after writing!");
                return false;
            }

            // Save skin data
            SkinData skinData = new SkinData(playerName, playerUuid, skinFileName, isSlim);
            Path dataPath = dataDir.resolve(dataFileName);
            NightfallSkin.LOGGER.info("Saving data to: {}", dataPath.toAbsolutePath());

            try (FileWriter writer = new FileWriter(dataPath.toFile())) {
                GSON.toJson(skinData, writer);
            }

            if (Files.exists(dataPath)) {
                NightfallSkin.LOGGER.info("✓ Data saved successfully");
            } else {
                NightfallSkin.LOGGER.error("✗ Data file doesn't exist after writing!");
                return false;
            }

            // Update cache
            skinCache.put(playerUuid, skinData);
            NightfallSkin.LOGGER.info("✓ Cache updated");

            NightfallSkin.LOGGER.info("=== SAVE COMPLETE ===");
            return true;

        } catch (Exception e) {
            NightfallSkin.LOGGER.error("=== SAVE FAILED ===", e);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Load a player's skin from server storage
     */
    public static BufferedImage loadSkinImage(UUID playerUuid) {
        try {
            if (skinsDirectory == null) {
                return null;
            }

            SkinData skinData = skinCache.get(playerUuid);
            if (skinData == null) {
                // Try loading from disk
                skinData = loadSkinData(playerUuid);
                if (skinData == null) {
                    return null;
                }
            }

            Path imagePath = skinsDirectory.resolve("images").resolve(skinData.skinFileName);
            if (!Files.exists(imagePath)) {
                NightfallSkin.LOGGER.warn("Skin image not found: {}", imagePath);
                return null;
            }

            return ImageIO.read(imagePath.toFile());

        } catch (Exception e) {
            NightfallSkin.LOGGER.error("Failed to load skin for player " + playerUuid, e);
            return null;
        }
    }

    /**
     * Load skin data (metadata) for a player
     */
    public static SkinData loadSkinData(UUID playerUuid) {
        try {
            if (skinsDirectory == null) {
                return null;
            }

            // Check cache first
            if (skinCache.containsKey(playerUuid)) {
                return skinCache.get(playerUuid);
            }

            // Load from disk
            Path dataPath = skinsDirectory.resolve("data").resolve(playerUuid.toString() + ".json");
            if (!Files.exists(dataPath)) {
                return null;
            }

            try (FileReader reader = new FileReader(dataPath.toFile())) {
                SkinData skinData = GSON.fromJson(reader, SkinData.class);
                skinCache.put(playerUuid, skinData);
                return skinData;
            }

        } catch (Exception e) {
            NightfallSkin.LOGGER.error("Failed to load skin data for player " + playerUuid, e);
            return null;
        }
    }

    /**
     * Check if a player has a custom skin stored
     */
    public static boolean hasSkin(UUID playerUuid) {
        if (skinCache.containsKey(playerUuid)) {
            return true;
        }

        if (skinsDirectory == null) {
            return false;
        }

        Path dataPath = skinsDirectory.resolve("data").resolve(playerUuid.toString() + ".json");
        return Files.exists(dataPath);
    }

    /**
     * Delete a player's skin
     */
    public static boolean deleteSkin(UUID playerUuid) {
        try {
            if (skinsDirectory == null) {
                return false;
            }

            SkinData skinData = loadSkinData(playerUuid);
            if (skinData == null) {
                return false;
            }

            // Delete files
            Path imagePath = skinsDirectory.resolve("images").resolve(skinData.skinFileName);
            Path dataPath = skinsDirectory.resolve("data").resolve(playerUuid.toString() + ".json");

            Files.deleteIfExists(imagePath);
            Files.deleteIfExists(dataPath);

            // Remove from cache
            skinCache.remove(playerUuid);

            NightfallSkin.LOGGER.info("Deleted skin for player {}", playerUuid);
            return true;

        } catch (Exception e) {
            NightfallSkin.LOGGER.error("Failed to delete skin for player " + playerUuid, e);
            return false;
        }
    }

    /**
     * Load all skins into cache
     */
    private static void loadAllSkins() {
        try {
            if (skinsDirectory == null) {
                return;
            }

            Path dataDir = skinsDirectory.resolve("data");
            if (!Files.exists(dataDir)) {
                return;
            }

            File[] dataFiles = dataDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (dataFiles == null) {
                return;
            }

            for (File file : dataFiles) {
                try (FileReader reader = new FileReader(file)) {
                    SkinData skinData = GSON.fromJson(reader, SkinData.class);
                    if (skinData != null && skinData.playerUuid != null) {
                        skinCache.put(skinData.playerUuid, skinData);
                    }
                } catch (Exception e) {
                    NightfallSkin.LOGGER.warn("Failed to load skin data from {}", file.getName(), e);
                }
            }

            NightfallSkin.LOGGER.info("Loaded {} skins from storage", skinCache.size());

        } catch (Exception e) {
            NightfallSkin.LOGGER.error("Failed to load skins", e);
        }
    }

    /**
     * Get all stored skins
     */
    public static Map<UUID, SkinData> getAllSkins() {
        return new HashMap<>(skinCache);
    }

    /**
     * Get storage directory path
     */
    public static Path getSkinsDirectory() {
        return skinsDirectory;
    }
}