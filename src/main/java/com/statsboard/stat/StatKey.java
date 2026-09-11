package com.statsboard.stat;

import net.minecraft.block.Block;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.stat.Stat;
import net.minecraft.stat.StatType;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Identifies one leaderboard stat: a vanilla stat type plus a value within
 * that type's registry, e.g. (minecraft:custom, minecraft:deaths) or
 * (minecraft:mined, minecraft:diamond_ore).
 *
 * <p><b>Threading.</b> {@link #resolve()} calls StatType#getOrCreateStat, which
 * mutates an unsynchronized IdentityHashMap on a global StatType singleton that
 * the server thread also writes to whenever a player first mines or crafts
 * something. It is therefore <b>server-thread only</b>. Everything else here -
 * {@link #isValid()}, {@link #displayName()} - is read-only registry access and
 * safe from any thread, which is what lets the client use this class without
 * ever touching that map.
 */
public record StatKey(Identifier typeId, Identifier valueId) {
    /** Synthetic type for stats we derive ourselves rather than reading from vanilla. */
    public static final Identifier SPECIAL_TYPE = new Identifier("statsboard", "special");

    public static final StatKey ADVANCEMENTS =
            new StatKey(SPECIAL_TYPE, new Identifier("statsboard", "advancements"));
    public static final StatKey DEATHS =
            new StatKey(new Identifier("minecraft", "custom"), new Identifier("minecraft", "deaths"));

    /** True for the advancement pseudo-stat, which has no backing vanilla Stat. */
    public boolean isAdvancements() {
        return ADVANCEMENTS.equals(this);
    }

    /**
     * Server thread only - see the class note. Empty when the type or value is
     * unknown (a stat from a mod that has since been removed), or for the
     * advancement pseudo-stat.
     */
    public Optional<Stat<?>> resolve() {
        if (isAdvancements()) {
            return Optional.empty();
        }
        return Registries.STAT_TYPE.getOrEmpty(typeId).flatMap(this::resolveTyped);
    }

    private <T> Optional<Stat<?>> resolveTyped(StatType<T> type) {
        return type.getRegistry().getOrEmpty(valueId).map(value -> type.getOrCreateStat(value));
    }

    /**
     * Whether this names a real stat, without creating one. Safe on any thread,
     * and the only validity check that may be applied to client-supplied input.
     *
     * <p>Note this uses getOrEmpty rather than get: BLOCK, ITEM and ENTITY_TYPE
     * are DefaultedRegistry, whose get(Identifier) substitutes minecraft:air
     * for an unknown id instead of returning null - so get() would happily
     * "validate" arbitrary garbage.
     */
    public boolean isValid() {
        if (isAdvancements()) {
            return true;
        }
        return Registries.STAT_TYPE.getOrEmpty(typeId)
                .map(type -> type.getRegistry().getOrEmpty(valueId).isPresent())
                .orElse(false);
    }

    /**
     * The same text the vanilla Statistics screen shows for this stat. Safe on
     * any thread; deliberately avoids StatType#getName, which lazily writes a
     * non-volatile field.
     */
    public Text displayName() {
        if (isAdvancements()) {
            return Text.translatable("statsboard.stat.advancements");
        }

        StatType<?> type = Registries.STAT_TYPE.getOrEmpty(typeId).orElse(null);
        if (type == null) {
            return Text.literal(valueId.toString());
        }

        // Custom stats have their own flat translation key; their registry
        // values are Identifiers, so the instanceof chain below can't name them.
        if (type == Stats.CUSTOM) {
            return Text.translatable("stat." + valueId.toString().replace(':', '.'));
        }

        Object value = type.getRegistry().getOrEmpty(valueId).orElse(null);
        Text name;
        if (value instanceof Block block) {
            name = block.getName();
        } else if (value instanceof Item item) {
            name = item.getName();
        } else if (value instanceof EntityType<?> entityType) {
            // Not getName(): that lazily caches a MutableText in a non-volatile
            // field, which the render thread and the integrated server thread
            // would race on. The translation key gives the same text.
            name = Text.translatable(entityType.getTranslationKey());
        } else {
            name = Text.literal(valueId.toString());
        }

        return Text.translatable(type.getTranslationKey()).append(" ").append(name);
    }

    public void write(PacketByteBuf buf) {
        buf.writeIdentifier(typeId);
        buf.writeIdentifier(valueId);
    }

    public static StatKey read(PacketByteBuf buf) {
        return new StatKey(buf.readIdentifier(), buf.readIdentifier());
    }

    /**
     * Empty instead of throwing. readIdentifier throws on a malformed string and
     * a truncated buffer throws on read, both of which happen on the netty thread
     * where Fabric turns the exception into a disconnect - so server-side
     * receivers must use this rather than {@link #read}.
     */
    public static Optional<StatKey> tryRead(PacketByteBuf buf) {
        try {
            return Optional.of(read(buf));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("Type", typeId.toString());
        nbt.putString("Value", valueId.toString());
        return nbt;
    }

    /** Empty rather than throwing, so a hand-edited block never breaks chunk load. */
    public static Optional<StatKey> fromNbt(NbtCompound nbt) {
        Identifier type = Identifier.tryParse(nbt.getString("Type"));
        Identifier value = Identifier.tryParse(nbt.getString("Value"));
        if (type == null || value == null) {
            return Optional.empty();
        }
        return Optional.of(new StatKey(type, value));
    }
}
