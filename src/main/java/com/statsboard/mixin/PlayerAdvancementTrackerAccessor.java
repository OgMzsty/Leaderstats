package com.statsboard.mixin;

import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerAdvancementTracker.class)
public interface PlayerAdvancementTrackerAccessor {
    @Accessor("owner")
    ServerPlayerEntity getOwner();
}
