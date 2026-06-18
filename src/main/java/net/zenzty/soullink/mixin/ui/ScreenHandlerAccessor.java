package net.zenzty.soullink.mixin.ui;

import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor mixin to access the revision counter in ScreenHandler. Needed to properly sync cursor
 * slot updates for spectators without causing protocol revision errors.
 */
@Mixin(AbstractContainerMenu.class)
public interface ScreenHandlerAccessor {

    @Accessor("stateId")
    int getRevision();

    /**
     * Set the revision number. WARNING: Direct writes can desynchronize the revision counter and
     * cause race conditions. Use {@link #invokeGetNextRevision()} for atomic increments whenever
     * possible.
     */
    @Accessor("stateId")
    void setRevision(int revision);

    /**
     * Invoke the private nextRevision method to atomically increment and return the next revision
     * number. This is essential for proper packet synchronization.
     */
    @Invoker("incrementStateId")
    int invokeGetNextRevision();

    /**
     * Invoke the updateToClient method to force a complete inventory synchronization to the client.
     * This sends all slot contents and the cursor stack with a fresh revision, which bypasses any
     * revision counter mismatches that occur during spectator interactions.
     */
    @Invoker("broadcastFullState")
    void invokeUpdateToClient();
}
