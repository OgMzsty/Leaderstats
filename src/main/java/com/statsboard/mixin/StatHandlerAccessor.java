package com.statsboard.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.stat.Stat;
import net.minecraft.stat.StatHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the whole stat map so a player's profile can list every statistic
 * they have touched. StatHandler only offers getStat for one known Stat at a
 * time, and the map itself is protected.
 *
 * <p>Needed for online players specifically: the scanner deliberately skips
 * re-reading their save file, so their cached values date from before they
 * logged in. Reading the live handler keeps a profile current.
 */
@Mixin(StatHandler.class)
public interface StatHandlerAccessor {
    @Accessor("statMap")
    Object2IntMap<Stat<?>> getStatMap();
}
