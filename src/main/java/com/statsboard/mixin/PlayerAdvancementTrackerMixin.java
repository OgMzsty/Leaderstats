package com.statsboard.mixin;

import com.statsboard.StatsManager;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fires every time a criterion is granted. Some advancements require several
 * criteria before they're considered complete, and the game keeps calling
 * grantCriterion for each remaining criterion even after the advancement is
 * already done - so we snapshot "was this already done before this call?"
 * at the start, and only count it if this specific call is what flipped it
 * from not-done to done. That way each advancement is only ever counted once
 * per player, no matter how many criteria it has.
 */
@Mixin(PlayerAdvancementTracker.class)
public class PlayerAdvancementTrackerMixin {
    @Unique
    private boolean statsboard$wasDoneBeforeThisGrant;

    @Inject(method = "grantCriterion", at = @At("HEAD"))
    private void statsboard$captureBeforeState(Advancement advancement, String criterionName,
                                                CallbackInfoReturnable<Boolean> cir) {
        PlayerAdvancementTracker self = (PlayerAdvancementTracker) (Object) this;
        statsboard$wasDoneBeforeThisGrant = self.getProgress(advancement).isDone();
    }

    @Inject(method = "grantCriterion", at = @At("RETURN"))
    private void statsboard$onGrantCriterion(Advancement advancement, String criterionName,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() || statsboard$wasDoneBeforeThisGrant) {
            return;
        }

        PlayerAdvancementTracker self = (PlayerAdvancementTracker) (Object) this;
        AdvancementProgress progress = self.getProgress(advancement);

        if (progress.isDone() && advancement.getDisplay() != null) {
            ServerPlayerEntity owner = ((PlayerAdvancementTrackerAccessor) self).getOwner();
            if (owner != null) {
                StatsManager.recordAdvancement(owner.getUuid(), owner.getGameProfile().getName());
            }
        }
    }
}
