package alloy.world;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The one screen class whose name differs between versions (26.x GenericMessageScreen; 1.20.1 in bridge/src47). */
final class WorldSafeScreens {
    private WorldSafeScreens() {}

    static Screen message(String text) {
        return new net.minecraft.client.gui.screens.GenericMessageScreen(Component.literal(text));
    }
}
