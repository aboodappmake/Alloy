package alloy.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.*;
import java.util.Map;

/**
 * Minecraft's CompoundTag to Alloy's NBT model and back, through NbtIo.write/read (same signatures on 1.20.1 and 26.x,
 * so this file compiles for both). Only get(String) and put(String, Tag) are used on the tags themselves.
 */
public final class McNbt {
    private McNbt() {}

    public static Map<String, Object> toModel(CompoundTag t) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        NbtIo.write(t, new DataOutputStream(b));
        return Nbt.fromBytes(b.toByteArray());
    }

    public static CompoundTag fromModel(Map<String, Object> m) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(Nbt.toBytes(m))));
    }

    /** level.dat root before it is written: "alloy" compound, kept player data on Data.Player (1.20.1). */
    public static void levelSave(CompoundTag root) {
        try {
            java.util.Map<String, Object> tmp = new java.util.LinkedHashMap<>();
            Tag data = root.get("Data");
            Tag player = data instanceof CompoundTag d ? d.get("Player") : null;
            Map<String, Object> pm = null;
            if (player instanceof CompoundTag p && !Keeper.isEmpty()) {
                pm = toModel(p);
                tmp.put("Data", new java.util.LinkedHashMap<>(Map.of("Player", pm)));
            }
            WorldSafe.onLevelSave(tmp);
            root.put(WorldMods.TAG, fromModel(Nbt.compound(tmp.get(WorldMods.TAG))));
            if (pm != null) ((CompoundTag) data).put("Player", fromModel(pm));
        } catch (Throwable t) {
            WorldSafe.LOG.warn("[Alloy] world safety: could not add the alloy tag to level.dat: {}", t.toString());
        }
    }

    public static void playerRead(CompoundTag tag) {
        if (tag == null || !Keeper.enabled) return;
        try {
            WorldSafe.onPlayerRead(toModel(tag));
        } catch (Throwable t) {
            WorldSafe.LOG.warn("[Alloy] keeper: player data not read: {}", t.toString());
        }
    }

    public static CompoundTag playerWrite(CompoundTag tag) {
        if (tag == null || Keeper.isEmpty()) return tag;
        try {
            Map<String, Object> m = toModel(tag);
            return WorldSafe.onPlayerWrite(m) ? fromModel(m) : tag;
        } catch (Throwable t) {
            WorldSafe.LOG.warn("[Alloy] keeper: kept player data not written: {}", t.toString());
            return tag;
        }
    }
}
