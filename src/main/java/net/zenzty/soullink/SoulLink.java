package net.zenzty.soullink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.api.ModInitializer;
import net.zenzty.soullink.server.command.CommandRegistry;
import net.zenzty.soullink.server.event.EventRegistry;

/**
 * SoulLink Speedrun mod entrypoint. This is a thin facade that delegates initialization to
 * specialized registry classes.
 */
public class SoulLink implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(SoulLink.class);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Soul Link Speedrun");

        // Register commands
        CommandRegistry.register();

        // Register all events (server lifecycle, connections, ticks, entities)
        EventRegistry.registerAll();

        LOGGER.info("Soul Link Speedrun initialized successfully!");
    }
}
