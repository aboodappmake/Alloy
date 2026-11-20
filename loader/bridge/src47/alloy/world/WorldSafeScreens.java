package alloy.world;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The one screen class whose name differs between versions (1.20.1 GenericDirtMessageScreen). */
final class WorldSafeScreens {
    private WorldSafeScreens() {}

    static Screen message(String text) {
        return new net.minecraft.client.gui.screens.GenericDirtMessageScreen(Component.literal(text));
    }
}
