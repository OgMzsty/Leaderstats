package com.statsboard;

/** Cached Mojang skin data for one player, used to render them while offline. */
public class PlayerStats {
    /** Mojang signed textures property used to reconstruct the player skin offline. */
    public String skinTextureValue;
    public String skinTextureSignature;
}
