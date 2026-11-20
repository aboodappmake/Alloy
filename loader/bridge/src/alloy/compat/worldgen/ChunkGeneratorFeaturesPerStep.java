package alloy.compat.worldgen;

import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.common.util.ClearableLazy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.function.Supplier;

/**
 * Vanilla's ChunkGenerator.featuresPerStep is a Supplier; Forge made it a ClearableLazy. Fabric worldgen mods
 * (Lithostitched: ChunkGeneratorAccessor.setFeaturesPerStep(Supplier)) declare an @Accessor setter with the vanilla type,
 * which cannot apply on Forge, and then the whole accessor mixin (getBiomeSource, setBiomeSource, ...) was skipped and
 * the game crashed casting the generator to it. The fitter turns that one setter into an @Invoker of this method
 * (FitTargets), which wraps the Supplier in Forge's ClearableLazy.
 */
@Mixin(value = ChunkGenerator.class, priority = 100)   // before the mods' accessors that invoke it
public abstract class ChunkGeneratorFeaturesPerStep {
    @Shadow(remap = false) @Final @Mutable private ClearableLazy<List<FeatureSorter.StepFeatureData>> featuresPerStep;

    public void alloy$setFeaturesPerStep(Supplier<List<FeatureSorter.StepFeatureData>> features) {
        this.featuresPerStep = ClearableLazy.concurrentOf(features);
    }
}
