package alloy.sodiumforge; // NOT alloy.compat.*: Mixin refuses to load non-mixin classes from a mixin package (source kept here so ENABLED gates it)

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.RandomSource;
import net.minecraftforge.client.model.data.ModelData;

import java.util.List;

/** A BlockStateModel that renders {@code delegate} with a fixed {@link ModelData}: the two-argument collectParts (what Fabric API's
 *  default emitQuads calls) forwards to Forge's three-argument one. Used by {@link SodiumForgeModelCompat}. */
public final class ForgeDataModelAdapter implements BlockStateModel {
    private final BlockStateModel delegate;
    private final ModelData data;

    public ForgeDataModelAdapter(BlockStateModel delegate, ModelData data) {
        this.delegate = delegate;
        this.data = data;
    }

    @Override public void collectParts(RandomSource random, List<BlockStateModelPart> parts) { delegate.collectParts(random, parts, data); }
    @Override public Material.Baked particleMaterial() { return delegate.particleMaterial(data); }
    @Override public int materialFlags() { return delegate.materialFlags(); }
}
