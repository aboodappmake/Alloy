package alloy.compat.recipesync;

import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two fixes to Forge's ingredient network codecs.
 *
 * <p>1. Fabric custom ingredients in Forge's {@code CONTENTS_STREAM_CODEC}. Forge replaced that field with
 * {@code ForgeHooks.ingredientStreamCodec()}, which asks {@code Ingredient.serializer()} for every ingredient. Fabric's
 * {@code CustomIngredientImpl} (fabric-recipe-api: {@code DefaultCustomIngredients.all/any/components/...}) is not a Forge
 * ingredient, so {@code serializer()} throws "Modders must implement Ingredient.codec in their custom Ingredients" and the
 * server drops the player on {@code recipe_book_add} (Tech Reborn recipes do this at the first join). Fabric's own wrapper
 * ({@code CustomIngredientStreamCodec}, put in by its {@code IngredientMixin} on the first {@code StreamCodec.map} in
 * {@code <clinit>}) lands on Forge's {@code VANILLA_CONTENTS_STREAM_CODEC} instead, which Forge's vanilla serializer uses.
 * Fix: such ingredients are written with {@code VANILLA_CONTENTS_STREAM_CODEC}, i.e. exactly as on Fabric: the custom
 * form ({@code -1}, Fabric serializer id, data) when the other side said it knows that serializer, else the matching items.
 * Reading: Forge and Fabric both mark custom ingredients with VarInt {@code -1} and an id. An id in Forge's ingredient
 * serializer registry goes to Forge's reader, any other id to Fabric's.
 *
 * <p>2. Fabric API's recipe sync (fabric-recipe-api OptionalCustomIngredientStreamCodec, used for every Optional&lt;Ingredient&gt;
 * such as smithing template/base/addition) falls back to {@code Ingredient.OPTIONAL_CONTENTS_STREAM_CODEC}. Vanilla encodes
 * {@code Optional.empty()} there as an empty holder set and decodes an empty holder set to {@code Optional.empty()}
 * without ever building an Ingredient. Forge rebuilt the codec on top of its own {@code CONTENTS_STREAM_CODEC}
 * (encode: {@code orElseGet(() -> Ingredient.of())}; decode: build an Ingredient first, then map "no items" to empty), and both
 * directions run the public constructor's non-empty check: "UnsupportedOperationException: Ingredients can't be empty"
 * (EncoderException on the server, then DecoderException on the client; the player is disconnected at join; seen with
 * JEI, which makes Fabric sync recipe types). Forge's own sync never hits it.
 * Fix: replace the field with a codec that keeps the contents codec from fix 1 for real ingredients (vanilla, Forge custom
 * serializers, Fabric custom ingredients) and handles the empty case the vanilla way: an empty/absent optional is written as
 * holder-set size 0 (VarInt 1) and VarInt 1 decodes to Optional.empty().
 */
@Mixin(value = Ingredient.class, remap = false)
public abstract class IngredientOptionalCodecMixin {
    @Shadow(remap = false) @Final @Mutable
    public static StreamCodec<RegistryFriendlyByteBuf, Optional<Ingredient>> OPTIONAL_CONTENTS_STREAM_CODEC;
    @Shadow(remap = false) @Final @Mutable
    public static StreamCodec<RegistryFriendlyByteBuf, Ingredient> CONTENTS_STREAM_CODEC;
    @Shadow(remap = false) @Final
    private static StreamCodec<RegistryFriendlyByteBuf, Ingredient> VANILLA_CONTENTS_STREAM_CODEC;

    @Inject(method = "<clinit>", at = @At("TAIL"), remap = false)
    private static void alloy$vanillaStyleOptionalCodec(CallbackInfo ci) {
        final StreamCodec<RegistryFriendlyByteBuf, Ingredient> forge = CONTENTS_STREAM_CODEC;
        final StreamCodec<RegistryFriendlyByteBuf, Ingredient> vanilla = VANILLA_CONTENTS_STREAM_CODEC;
        final StreamCodec<RegistryFriendlyByteBuf, Ingredient> contents = new StreamCodec<>() {
            @Override
            public Ingredient decode(RegistryFriendlyByteBuf buf) {
                int start = buf.readerIndex();
                if (buf.readVarInt() == -1) {
                    Identifier id = buf.readIdentifier();
                    buf.readerIndex(start);
                    if (!ForgeRegistries.INGREDIENT_SERIALIZERS.get().containsKey(id)) return vanilla.decode(buf);
                } else {
                    buf.readerIndex(start);
                }
                return forge.decode(buf);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, Ingredient value) {
                if (!value.isVanilla()) {
                    try {
                        value.serializer();
                    } catch (IllegalStateException notForge) {   // Fabric CustomIngredientImpl and the like
                        vanilla.encode(buf, value);
                        return;
                    }
                }
                forge.encode(buf, value);
            }
        };
        CONTENTS_STREAM_CODEC = contents;
        OPTIONAL_CONTENTS_STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Optional<Ingredient> decode(RegistryFriendlyByteBuf buf) {
                int start = buf.readerIndex();
                if (buf.readVarInt() == 1) return Optional.empty(); // direct holder set of size 0
                buf.readerIndex(start);
                Ingredient ing = contents.decode(buf);
                return ing.items().findAny().isPresent() ? Optional.of(ing) : Optional.empty();
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, Optional<Ingredient> value) {
                if (value.isEmpty() || value.get().items().findAny().isEmpty()) {
                    buf.writeVarInt(1);
                } else {
                    contents.encode(buf, value.get());
                }
            }
        };
    }
}
