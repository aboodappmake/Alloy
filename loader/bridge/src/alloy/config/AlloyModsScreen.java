package alloy.config;

import alloy.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * "All mods (Alloy)": every Forge and Fabric mod with a config screen or a config file, one button per mod, in pages. A
 * click goes through {@link SafeOpen}: the mod's screen, or a short message with its config files when it has no screen
 * or the screen breaks. The first version of research5's merged Alloy Mods list; only buttons and text widgets, so the
 * same source works on every Minecraft version the bridge builds for.
 */
public final class AlloyModsScreen extends Screen {
    private final Screen parent;
    private List<ConfigScreens.Mod> mods;
    private int page;

    public AlloyModsScreen(Screen parent) {
        super(Component.literal("Mod configs (Alloy)"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (mods == null) mods = ConfigScreens.mods();   // read when shown, never at startup
        int w = 260, h = 20, gap = 2;
        int per = Math.max(3, (this.height - 90) / (h + gap));
        int pages = Math.max(1, (mods.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        addRenderableWidget(new StringWidget(this.width / 2 - w / 2, 8, w, 12,
                Component.literal(this.title.getString() + (pages > 1 ? "  " + (page + 1) + "/" + pages : "")), this.font));
        int y = 26;
        if (mods.isEmpty())
            addRenderableWidget(new StringWidget(this.width / 2 - w / 2, y, w, 12, Component.literal("No mod has a config screen or a config file."), this.font));
        for (int i = page * per; i < Math.min(mods.size(), (page + 1) * per); i++) {
            ConfigScreens.Mod m = mods.get(i);
            ConfigScreens.Has has = ConfigScreens.has(m);
            String tag = SafeOpen.failed(m) ? "  [broken]" : has == ConfigScreens.Has.NO ? "  [file]" : "";
            addRenderableWidget(Button.builder(Component.literal(m.label() + tag), b -> SafeOpen.open(m, this))
                    .bounds(this.width / 2 - w / 2, y, w, h).build());
            y += h + gap;
        }
        int by = this.height - 28;
        Button prev = Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(this.width / 2 - w / 2, by, 40, h).build();
        Button next = Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(this.width / 2 + w / 2 - 40, by, 40, h).build();
        prev.active = page > 0;
        next.active = page < pages - 1;
        addRenderableWidget(prev);
        addRenderableWidget(next);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(this.width / 2 - 80, by, 160, h).build());
    }

    @Override
    public void onClose() {
        Screens.set(parent);
    }
}
