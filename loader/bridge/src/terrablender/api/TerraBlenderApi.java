package terrablender.api;

/**
 * Alloy's own copy of the entrypoint interface that Fabric TerraBlender defines (entrypoint key "terrablender").
 * Forge TerraBlender does not have it, so Fabric mods like Biomes O' Plenty Fabric could not even load their entrypoint
 * class next to Forge TerraBlender. Alloy calls it from Forge TerraBlender's common setup (alloy.terrablender.TerraBlenderBridge).
 * Written for Alloy, not copied from TerraBlender.
 */
public interface TerraBlenderApi {
    /** Register regions and surface rules here (terrablender.api.Regions, SurfaceRuleManager). */
    default void onTerraBlenderInitialized() {}
}
