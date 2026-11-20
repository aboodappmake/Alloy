package alloy.config;

import alloy.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A short message about a mod's config, with "Open config file" / "Open config folder" when the mod has files, and Back.
 * Shown when a config screen broke (SafeOpen), when a mod has no screen, and as the Config screen Alloy gives Forge mods
 * that have config files but no screen (ForgeFactories). Only buttons and text widgets, no render override, so the same
 * source works on every Minecraft version the bridge builds for.
 */
public final class ConfigMessageScreen extends Screen {
    private final Screen parent;
    private final List<String> lines;
    private final List<Path> files;

    private ConfigMessageScreen(Screen parent, String title, List<String> lines, List<Path> files) {
        super(Component.literal(title));
        this.parent = parent;
        this.lines = lines;
        this.files = files;
    }

    public static ConfigMessageScreen failed(Screen parent, ConfigScreens.Mod m, String why) {
        List<Path> f = ConfigScreens.files(m);
        String reason = why.length() > 56 ? why.substring(0, 53) + "..." : why;   // the full text is in alloy-report.txt
        List<String> l = new ArrayList<>(List.of("Could not open the config of " + m.name() + ".",
                "Its config screen failed:", reason, "The game is fine; see alloy-report.txt for details."));
        if (!f.isEmpty()) l.add("You can edit its config file instead.");
        return new ConfigMessageScreen(parent, "Config of " + m.name(), l, f);
    }

    public static ConfigMessageScreen noScreen(Screen parent, ConfigScreens.Mod m) {
        List<Path> f = ConfigScreens.files(m);
        List<String> l = new ArrayList<>(List.of(m.name() + " has no config screen."));
        l.add(f.isEmpty() ? "No config file for it was found in the config folder." : "You can edit its config file:");
        return new ConfigMessageScreen(parent, "Config of " + m.name(), l, f);
    }

    /** Forge's Config button for a mod that only has config files (ForgeFactories) */
    public static ConfigMessageScreen filesOnly(Screen parent, ConfigScreens.Mod m) {
        List<Path> f = ConfigScreens.files(m);
        List<String> l = new ArrayList<>(List.of(m.name() + " has no config screen, only config files.",
                "Changes to a Forge config file are read back while the game runs."));
        return new ConfigMessageScreen(parent, "Config of " + m.name(), l, f);
    }

    @Override
    protected void init() {
        int w = 300, h = 20, gap = 4;
        int rows = lines.size() + 1 + Math.min(files.size(), 3) + (files.isEmpty() ? 0 : 1) + 1;
        int y = Math.max(10, this.height / 2 - rows * (h + gap) / 2);
        addRenderableWidget(new StringWidget(this.width / 2 - w / 2, y, w, h, this.title, this.font));
        y += h;
        for (String s : lines) {
            addRenderableWidget(new StringWidget(this.width / 2 - w / 2, y, w, 12, Component.literal(s), this.font));
            y += 12;
        }
        y += gap * 2;
        int shown = 0;
        for (Path p : files) {
            if (shown++ == 3) break;
            String label = "Open " + p.getFileName();
            addRenderableWidget(Button.builder(Component.literal(label), b -> ConfigScreens.openFile(p))
                    .bounds(this.width / 2 - 100, y, 200, h).build());
            y += h + gap;
        }
        if (!files.isEmpty()) {
            Path folder = files.get(0).getParent();
            addRenderableWidget(Button.builder(Component.literal("Open config folder"), b -> ConfigScreens.openFile(folder))
                    .bounds(this.width / 2 - 100, y, 200, h).build());
            y += h + gap;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(this.width / 2 - 100, y + gap, 200, h).build());
    }

    @Override
    public void onClose() {
        Screens.set(parent);
    }
}
