package alloy.compat.sodiumforge;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import alloy.sodiumforge.ForgeDataModelAdapter;
import java.util.function.Predicate;

/**
 * Sodium (Fabric build) meshes a block through {@code FRAPIEmitter.emitModel} -> {@code FabricBlockStateModel.emitQuads},
 * whose default implementation calls the two-argument {@code BlockStateModel.collectParts(random, parts)}. On Forge-patched
 * Minecraft that is the model WITHOUT Forge's {@link ModelData}: vanilla's ModelBlockRenderer is patched to call
 * {@code getModelData(level, pos, state, base)} and {@code collectParts(random, parts, data)} instead. Without it every model
 * that reads ModelData renders as its default state, notably Fusion's connected textures ({@code fusion:connecting} ->
 * BlockModelModifierBakedModel, whose connection flags travel in a ModelProperty) and block-entity driven models.
 * Seen in the rendering screenshots: with Sodium, Rechiseled's *_connecting walls show the unconnected texture on every
 * block (6_ctm_wall.png), without Sodium they connect.
 *
 * <p>This wraps the emitQuads call: ask the model for its ModelData (base = the client's ModelDataManager entry for the
 * block, i.e. what a block entity supplied through getModelData). If there is any data, emit through
 * {@link ForgeDataModelAdapter}, a BlockStateModel whose two-argument collectParts forwards to the three-argument one. Models
 * that ignore ModelData and blocks without data keep the original path untouched.
 * Thread: runs on Sodium's mesh builder threads; ModelDataManager.getAtOrEmpty reads Forge's concurrent caches and Fusion's
 * getModelData only reads the BlockAndTintGetter (Sodium's LevelSlice), as vanilla's chunk compile does.
 *
 * <p>Not covered: Fusion's random-offset models ({@code ModelsByRandomOffset}); their parts are merged at the default offset.
 *
 * <p>Pending: enable by renaming ENABLED.pending to ENABLED and listing {@code "sodiumforge.SodiumForgeModelCompat"} under
 * "client" in alloy.compat.mixins.json. The target is named as a string, so the mixin is inert without Sodium.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.frapi.FRAPIEmitter", remap = false)
public abstract class SodiumForgeModelCompat {
    @WrapOperation(method = "emitModel", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/fabricmc/fabric/api/client/renderer/v1/model/FabricBlockStateModel;emitQuads(Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;Ljava/util/function/Predicate;)V"))
    private void alloy$emitWithModelData(FabricBlockStateModel self, QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos,
                                            BlockState state, RandomSource random, Predicate<Direction> cullTest,
                                            Operation<Void> original) {
        Object model = self;
        if (model instanceof BlockStateModel bsm) {
            ModelData base = ModelData.EMPTY;
            var mc = Minecraft.getInstance();
            if (mc.level != null) base = mc.level.getModelDataManager().getAtOrEmpty(pos);
            ModelData data = bsm.getModelData(level, pos, state, base);
            if (data != null && data != ModelData.EMPTY) model = new ForgeDataModelAdapter(bsm, data);
        }
        original.call((FabricBlockStateModel) model, emitter, level, pos, state, random, cullTest);
    }
}
