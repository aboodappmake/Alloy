package alloy.compat.wover;

import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraftforge.common.util.ClearableLazy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.function.Supplier;

/**
 * WorldWeaver (BCLib, Better End, Better Nether) reads and replaces ChunkGenerator's feature list through
 * an accessor mixin, de.ambertation.wover.biome.mixin.ChunkGeneratorAccessor. Forge changed featuresPerStep from a
 * Supplier to its ClearableLazy, so the accessor finds no field of the type it expects and fitting has to drop it; the
 * next call to it then fails ("Illegal classload request for accessor mixin") and worlds do not load.
 * compat_replaces.txt turns the two featuresPerStep accessors into invokers of these methods, which use Forge's field
 * type (the mixin stays an accessor mixin; its biomeSource accessor is unchanged). A Supplier set by WorldWeaver is wrapped in ClearableLazy.of, so Forge's
 * refreshFeaturesPerStep keeps working. Without WorldWeaver they are unused.
 */
@Mixin(value = net.minecraft.world.level.chunk.ChunkGenerator.class, priority = 500)   // before WorldWeaver's accessor mixin: its invokers need these
public abstract class ChunkGeneratorWoverAccess {
    @Shadow @Final @Mutable private ClearableLazy<List<FeatureSorter.StepFeatureData>> featuresPerStep;

    public void alloy$woverSetFeaturesPerStep(Supplier<List<FeatureSorter.StepFeatureData>> s) {
        this.featuresPerStep = s instanceof ClearableLazy<List<FeatureSorter.StepFeatureData>> c ? c : ClearableLazy.of(s);
    }

    public Supplier<List<FeatureSorter.StepFeatureData>> alloy$woverGetFeaturesPerStep() {
        return this.featuresPerStep;
    }
}
