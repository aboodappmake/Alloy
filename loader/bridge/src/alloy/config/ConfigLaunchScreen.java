package alloy.config;

import alloy.Screens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A blank screen that stands for one client tick between Forge's own Config button and the mod's config screen. Forge's
 * ModListScreen (and Catalogue) call setScreen(factory result) themselves, so a crash in that screen's init() came from
 * Forge's call, outside anything Alloy could catch. {@link SafeOpen#guard} now hands Forge this screen instead; on its
 * first tick it opens the mod's screen through {@link SafeOpen#launch}, inside Alloy's try, so an init() crash shows the
 * same message (and the same one WARN line in alloy-report.txt) as from the Alloy list.
 *
 * No widgets and no render override, so the same source works on every Minecraft version the bridge builds for.
 */
public final class ConfigLaunchScreen extends Screen {
    private final ConfigScreens.Mod mod;
    private final Screen target, parent;
    private boolean launched;

    ConfigLaunchScreen(ConfigScreens.Mod mod, Screen target, Screen parent) {
        super(Component.literal("Config of " + mod.name()));
        this.mod = mod;
        this.target = target;
        this.parent = parent;
    }

    Screen target() { return target; }

    @Override
    public void tick() {
        if (launched) return;
        launched = true;
        SafeOpen.launch(mod, target, parent);
    }

    @Override
    public void onClose() {
        launched = true;
        Screens.set(parent);
    }
}
