package alloy.compat.biome;

import java.util.function.Supplier;

import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fabric API's BiomeModificationImpl.finalizeWorldGen rebuilds the per-step feature list of every chunk generator after the
 * biome modifications ran, by assigning {@code ChunkGenerator.featuresPerStep} (a {@code Supplier}, made accessible with an access
 * widener). Forge turned that field into a {@code ClearableLazy} and added {@code refreshFeaturesPerStep()}, so the assignment
 * dies with {@code NoSuchFieldError: ... does not have member field 'java.util.function.Supplier featuresPerStep'}
 * as soon as any Fabric mod adds biome modifications (Waystones, TerraBlender users, ...), when the integrated server starts.
 *
 * <p>Fix: replace the field write with Forge's own refresh, which clears the lazy and recomputes the feature steps from the
 * (now modified) biome generation settings.
 */
@Mixin(targets = "net.fabricmc.fabric.impl.biome.modification.BiomeModificationImpl", remap = false)
public abstract class BiomeModificationFeaturesCompat {
    @Redirect(method = "lambda$finalizeWorldGen$1", remap = false,
            at = @At(value = "FIELD", opcode = 181, remap = false,
                    target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;featuresPerStep:Ljava/util/function/Supplier;"))
    private static void alloy$refreshFeaturesPerStep(ChunkGenerator generator, Supplier<?> fabricValue) {
        generator.refreshFeaturesPerStep();
    }
}
