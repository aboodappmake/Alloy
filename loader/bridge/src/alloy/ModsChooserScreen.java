package alloy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Shown by the merged "Mods" button (mixin.ModsButtonMixin): one entry per original button, labelled by the side it
 * came from (Fabric's Mod Menu or Forge's mod list), plus Done. Also holds the merge itself, so the per-version
 * ModsButtonMixin sources (Screen.init differs between Minecraft versions) only carry their injection points.
 *
 * Config screens (alloy.config): the chooser also offers "Mod configs (Alloy)" (every mod's config,
 * both sides, opened crash safe), and on the pause menu Mod Menu's lone Mods button is replaced by one that opens this
 * chooser with a "Forge mods" entry, so Forge configs are reachable in a world. -Dalloy.configscreens=off turns both off.
 */
public class ModsChooserScreen extends Screen {
    /** buttons Alloy created (never merged again) */
    private static final Set<AbstractButton> MERGED = Collections.newSetFromMap(new WeakHashMap<>());

    private final Screen parent;
    private final List<AbstractButton> originals;
    /** the Forge mod list without a Forge button to press (pause menu: Forge adds no Mods button there) */
    private final boolean forgeList;

    private ModsChooserScreen(Screen parent, String title, List<AbstractButton> originals, boolean forgeList) {
        super(Component.literal(title));
        this.parent = parent;
        this.originals = originals;
        this.forgeList = forgeList;
    }

    public static void open(Screen parent, String title, List<AbstractButton> originals) {
        Screens.set(new ModsChooserScreen(parent, title, originals, false));
    }

    /**
     * Buttons with the same text whose bounds overlap are hidden and replaced by one button (passed to add) that opens
     * the chooser. Called by ModsButtonMixin after every widget (re)build of a screen.
     */
    public static void merge(Screen self, List<? extends GuiEventListener> children, Consumer<Button> add) {
        boolean cfg = alloy.config.ConfigScreens.enabled();
        if ((self instanceof TitleScreen || self instanceof PauseScreen) && alloy.config.ConfigScreens.forgeButtons()) alloy.config.ForgeFactories.install();
        List<AbstractButton> buttons = new ArrayList<>();
        for (GuiEventListener g : children)
            if (g instanceof AbstractButton b && b.visible && !MERGED.contains(b)) buttons.add(b);
        List<AbstractButton> done = new ArrayList<>();
        for (AbstractButton a : buttons) {
            if (done.contains(a)) continue;
            List<AbstractButton> group = new ArrayList<>(List.of(a));
            String text = a.getMessage().getString();
            for (AbstractButton b : buttons)
                if (b != a && !done.contains(b) && b.getMessage().getString().equals(text) && overlap(a, b)) group.add(b);
            if (group.size() < 2) continue;
            done.addAll(group);
            for (AbstractButton b : group) { b.visible = false; b.active = false; }
            List<AbstractButton> originals = List.copyOf(group);
            // plain text (no copied style): the originals' messages may carry a grey/inactive style
            Button merged = Button.builder(Component.literal(text), btn -> open(self, text, originals))
                    .bounds(a.getX(), a.getY(), a.getWidth(), a.getHeight()).build();
            merged.active = true;
            MERGED.add(merged);
            add.accept(merged);
        }
        if (cfg && self instanceof PauseScreen && done.isEmpty() && !"chooser".equals(System.getProperty("alloy.modlist"))) pause(self, buttons, add);
    }

    /**
     * Pause menu. Mod Menu puts its Mods button in the button grid; newer Forge builds (47.4 on 1.20.1) add their own full-width
     * Mods button lower down, older ones add none, so in a world Forge configs had either a second, separate button or no way
     * in. Mod Menu's button is hidden and an Alloy button in its place opens the chooser: with Forge's button (same text,
     * anywhere on the screen) it is one more original there and hidden too; without it the chooser's "Forge mods" entry opens
     * Forge's ModListScreen directly. Without Mod Menu's button nothing changes (vanilla buttons are never moved).
     * -Dalloy.modlist=chooser keeps the old pause menu.
     */
    static void pause(Screen self, List<AbstractButton> buttons, Consumer<Button> add) {
        if (!alloy.config.ConfigScreens.forgeLoaded()) return;
        for (AbstractButton b : buttons) {
            if (!b.getClass().getName().startsWith("com.terraformersmc.modmenu")) continue;
            String text = b.getMessage().getString();
            List<AbstractButton> originals = new ArrayList<>(List.of(b));
            for (AbstractButton o : buttons)   // Forge's own pause Mods button (a plain Button with the same text)
                if (o != b && !text.isBlank() && o.getMessage().getString().equals(text) && label(o).equals("Forge mods")) originals.add(0, o);
            for (AbstractButton o : originals) { o.visible = false; o.active = false; }
            if (text.isBlank() || b.getWidth() < 60) text = "Mods";
            boolean forgeEntry = originals.size() == 1;
            List<AbstractButton> list = List.copyOf(originals);
            String title = text;
            Button mine = Button.builder(Component.literal(text), x -> Screens.set(new ModsChooserScreen(self, title, list, forgeEntry)))
                    .bounds(b.getX(), b.getY(), b.getWidth(), b.getHeight()).build();
            MERGED.add(mine);
            add.accept(mine);
            return;
        }
    }

    private static boolean overlap(AbstractButton a, AbstractButton b) {
        return a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
    }

    static String label(AbstractButton b) {
        String c = b.getClass().getName();
        if (c.startsWith("com.terraformersmc.modmenu")) return "Fabric mods (Mod Menu)";
        if (c.startsWith("net.minecraft.") || c.startsWith("net.minecraftforge.")) return "Forge mods";
        return b.getMessage().getString() + " (" + c.substring(c.lastIndexOf('.') + 1) + ")";
    }

    /**
     * Presses an original button. AbstractButton.onPress takes an InputWithModifiers on Minecraft 26.1 and nothing on
     * 1.20.1, so it is called by name (null input, as before).
     */
    static void press(AbstractButton b) {
        try {
            for (Method m : AbstractButton.class.getMethods()) {   // the public base method; the call dispatches to the override
                if (!m.getName().equals("onPress") || m.getParameterCount() > 1 || m.isBridge()) continue;
                m.invoke(b, m.getParameterCount() == 0 ? new Object[0] : new Object[] { null });
                return;
            }
            Bridge.LOG.warn("Alloy: button {} has no onPress method", b.getClass().getName());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) throw r;
            if (c instanceof Error er) throw er;
            throw new RuntimeException(c);
        } catch (ReflectiveOperationException e) {
            Bridge.LOG.warn("Alloy: cannot press button {}: {}", b.getClass().getName(), e.toString());
        }
    }

    @Override
    protected void init() {
        int w = 200, h = 20, gap = 4;
        boolean cfg = alloy.config.ConfigScreens.enabled();
        int n = originals.size() + (forgeList ? 1 : 0) + (cfg ? 1 : 0);
        int y = this.height / 2 - (n + 1) * (h + gap) / 2;
        for (AbstractButton b : originals) {
            addRenderableWidget(Button.builder(Component.literal(label(b)), x -> press(b))
                    .bounds(this.width / 2 - w / 2, y, w, h).build());
            y += h + gap;
        }
        if (forgeList) {
            addRenderableWidget(Button.builder(Component.literal("Forge mods"), x -> {
                Screen s = alloy.config.ConfigScreens.forgeModList(this);
                if (s != null) Screens.set(s);
            }).bounds(this.width / 2 - w / 2, y, w, h).build());
            y += h + gap;
        }
        if (cfg) {   // every mod's config, both sides, through alloy.config.SafeOpen
            addRenderableWidget(Button.builder(Component.literal("Mod configs (Alloy)"), x -> Screens.set(new alloy.config.AlloyModsScreen(this)))
                    .bounds(this.width / 2 - w / 2, y, w, h).build());
            y += h + gap;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), x -> onClose())
                .bounds(this.width / 2 - w / 2, y + gap, w, h).build());
    }

    @Override
    public void onClose() {
        Screens.set(parent);
    }
}
