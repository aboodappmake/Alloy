package alloy.api;

import java.util.Objects;

/**
 * One loaded mod, from either side.
 *
 * @param id      the mod id (Fabric "id" or Forge "modId")
 * @param name    display name (falls back to the id)
 * @param version version string as the mod declares it
 * @param side    which side loaded it
 */
public record ModInfo(String id, String name, String version, Side side) {
    public ModInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(side, "side");
        if (name == null || name.isEmpty()) name = id;
        if (version == null) version = "";
    }
}
