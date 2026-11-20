package alloy.compat.packs;

import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Forge makes PackRepository's sources a mutable set and adds them later through addPackFinder (mod resource packs).
 * Vanilla keeps an ImmutableSet, and Fabric mods that add a source rebuild it as one (FrozenLib's PackRepositoryMixin:
 * ImmutableSet.builder().addAll(sources).add(source).build()). Forge's next addPackFinder then throws
 * UnsupportedOperationException from ImmutableCollection.add and the client never starts (Wilder Wild).
 * Before Forge adds, an immutable set is copied into a LinkedHashSet (same order, same entries).
 */
@Mixin(net.minecraft.server.packs.repository.PackRepository.class)
public abstract class PackFinderMutableCompat {
    @Shadow @Final @Mutable private Set<RepositorySource> sources;

    @Inject(method = "addPackFinder", at = @At("HEAD"), remap = false)
    private void alloy$mutableSources(RepositorySource source, CallbackInfo ci) {
        if (sources instanceof com.google.common.collect.ImmutableCollection<?>) sources = new LinkedHashSet<>(sources);
    }
}
