package net.zenzty.soullink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.screen.ScreenHandler;

/**
 * Accessor mixin to access the revision counter in ScreenHandler. Needed to properly sync cursor
 * slot updates for spectators without causing protocol revision errors.
 */
@Mixin(ScreenHandler.class)
public interface ScreenHandlerAccessor {

    @Accessor("revision")
    int getRevision();

    @Accessor("revision")
    void setRevision(int revision);

    /**
     * Invoke the private nextRevision method to atomically increment and return the next revision
     * number. This is essential for proper packet synchronization.
     */
    @Invoker("nextRevision")
    int invokeGetNextRevision();

    /**
     * Invoke the updateToClient method to force a complete inventory synchronization to the client.
     * This sends all slot contents and the cursor stack with a fresh revision, which bypasses any
     * revision counter mismatches that occur during spectator interactions.
     */
    @Invoker("updateToClient")
    void invokeUpdateToClient();
}

