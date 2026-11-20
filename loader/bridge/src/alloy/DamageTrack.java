package alloy;

/**
 * Per-entity record of what Forge did to the current hurtServer call, added to LivingEntity by EntityEventsCompat.
 * Player (and anything else overriding actuallyHurt with its own Forge hooks) reports through it as well
 * (PlayerDamageCompat), so AFTER_DAMAGE sees Forge's final damage for players too.
 */
public interface DamageTrack {
    void alloy$forgeHurt(float in, float out);    // ForgeHooks.onLivingHurt result
    void alloy$forgeDamage(float in, float out);  // ForgeHooks.onLivingDamage result
}
