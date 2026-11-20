package alloy.api;

/** The two mod sides of an Alloy game. */
public enum Side {
    /** a Fabric mod (from mods/), or the plain Fabric platform */
    FABRIC,
    /** a Forge mod (from forgemods/), or the plain Forge platform */
    FORGE,
    /** not a mod of either side: Minecraft, a library, Alloy itself, or the platform could not be told */
    UNKNOWN
}
