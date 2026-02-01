package net.kendo.nightfall;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kendo.nightfall.Network.SkinNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NightfallSkin implements ModInitializer {
    public static final String MOD_ID = "skinchanger";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Skin Changer Mod Initialized!");

        // Register server-side receivers (works for both integrated and dedicated servers)
        SkinNetworkHandler.registerServerReceivers();

        // When a player joins, send them all existing skins
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            LOGGER.info("Player {} joined, will send existing skins after delay",
                    handler.getPlayer().getName().getString());

            server.execute(() -> {
                // Small delay to ensure client is ready
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Send all existing skins to the new player
                SkinNetworkHandler.sendAllSkinsToPlayer(
                        handler.getPlayer(),
                        server.getPlayerManager().getPlayerList()
                );
            });
        });
    }
}