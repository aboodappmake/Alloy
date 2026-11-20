package alloy.config;

import alloy.Bridge;
import alloy.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.IConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A plain editor for a Forge mod's ForgeConfigSpec values, for Forge 64 and up (Minecraft 26.x), where no Forge build of
 * Configured, Catalogue, Cloth Config or YACL exists, so a Forge mod with only a spec had a grey Config button. {@link ForgeFactories} registers it as the Config screen of such mods.
 *
 * One row per value: booleans and enums are buttons that cycle, numbers (int, long, double, float) and strings are text
 * boxes checked against the spec (range and the mod's own rule; a bad value turns red and Done is off), lists and other
 * types are shown read only. The tooltip has the path, the spec comment and the range. Done writes the changed values with
 * ConfigValue.set and saves the file with ModConfig.save; Forge's file watcher then reloads it and the mod gets its
 * ModConfigEvent.Reloading. Cancel (or Esc) changes nothing. SERVER configs are listed only in single player.
 *
 * 26.x only: built with the rest of bridge/src for Forge 64, not part of the 1.x bridges (build_ui47.UI47).
 * -Dalloy.configscreens.spec=false keeps the open-config-file screen instead.
 */
public final class SpecScreen extends Screen {
    static final class Row {
        final ModConfig config;
        final List<String> path;
        final ForgeConfigSpec.ConfigValue<Object> value;
        final ForgeConfigSpec.ValueSpec spec;
        final SpecValues.Kind kind;
        final Object start;
        Object pending;
        String text;
        boolean valid = true;

        Row(ModConfig config, SpecValues.Value v) {
            this.config = config;
            this.path = v.path();
            this.value = v.value();
            this.spec = v.spec();
            this.start = value.get();
            this.pending = start;
            this.kind = SpecValues.kind(start);
            this.text = String.valueOf(start);
        }

        boolean changed() { return kind != SpecValues.Kind.READ_ONLY && valid && !Objects.equals(pending, start); }

        String name() { return path.get(path.size() - 1); }
    }

    private final Screen parent;
    private final ConfigScreens.Mod mod;
    private final List<Row> rows;
    private int page;
    private Button done;

    private SpecScreen(Screen parent, ConfigScreens.Mod mod, List<Row> rows) {
        super(Component.literal("Config of " + mod.name()));
        this.parent = parent;
        this.mod = mod;
        this.rows = rows;
    }

    // ------------------------------------------------------------------------------------------------------- found by name

    /** called by ForgeFactories by reflection: does this Forge mod have a ForgeConfigSpec config this screen can show? */
    public static boolean has(String modId) {
        try { return !configs(modId).isEmpty(); } catch (Throwable t) { return false; }
    }

    /** called by ForgeFactories' Config screen function by reflection: the editor, built from the specs as they are now */
    public static Screen create(Screen parent, String modId, String name) {
        ConfigScreens.Mod m = new ConfigScreens.Mod(modId, name, ConfigScreens.Side.FORGE);
        List<Row> rows = new ArrayList<>();
        for (ModConfig c : configs(modId)) {
            if (!(c.<ForgeConfigSpec>getSpec() instanceof ForgeConfigSpec spec) || !spec.isLoaded()) continue;
            for (SpecValues.Value v : SpecValues.walk(spec.getValues(), spec.getSpec())) {
                try { rows.add(new Row(c, v)); } catch (Throwable t) {
                    Bridge.LOG.debug("Alloy config: value {} of {} not shown: {}", String.join(".", v.path()), c.getFileName(), t.toString());
                }
            }
        }
        Bridge.LOG.info("Alloy config: SpecScreen for {}: {} values", m.label(), rows.size());
        return new SpecScreen(parent, m, rows);
    }

    /** the mod's ForgeConfigSpec configs (CLIENT, COMMON, then SERVER in single player only) */
    static List<ModConfig> configs(String modId) {
        boolean local = Minecraft.getInstance().getSingleplayerServer() != null;
        List<ModConfig> out = new ArrayList<>();
        for (Map.Entry<ModConfig.Type, Set<ModConfig>> e : ConfigTracker.configSets().entrySet()) {
            if (e.getKey() == ModConfig.Type.SERVER && !local) continue;
            for (ModConfig c : e.getValue()) {
                IConfigSpec<?> s = c.<ForgeConfigSpec>getSpec();
                if (modId.equals(c.getModId()) && s instanceof ForgeConfigSpec) out.add(c);
            }
        }
        out.sort(Comparator.comparing((ModConfig c) -> c.getType().ordinal()).thenComparing(ModConfig::getFileName));
        return out;
    }

    static String show(Object v) { return SpecValues.show(v); }

    String tooltip(Row r) {
        StringBuilder b = new StringBuilder(String.join(".", r.path));
        b.append("  (").append(r.config.getFileName()).append(')');
        String comment = r.spec.getComment();
        if (comment != null && !comment.isBlank()) b.append('\n').append(comment.trim());
        try {
            var range = r.spec.getRange();
            if (range != null) b.append("\nRange: ").append(range.getMin()).append(" to ").append(range.getMax());
        } catch (Throwable ignored) { }
        b.append("\nDefault: ").append(show(r.spec.getDefault()));
        if (r.spec.needsWorldRestart()) b.append("\nNeeds a world restart.");
        if (r.kind == SpecValues.Kind.READ_ONLY) b.append("\nRead only here: edit the config file to change it.");
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------------------------ screen

    @Override
    protected void init() {
        int h = 20, gap = 4, labelW = 150, ctlW = 150, x = this.width / 2 - (labelW + ctlW + gap) / 2;
        int per = Math.max(3, (this.height - 76) / (h + gap));
        int pages = Math.max(1, (rows.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        addRenderableWidget(new StringWidget(this.width / 2 - 150, 8, 300, 12,
                Component.literal(this.title.getString() + (pages > 1 ? "  " + (page + 1) + "/" + pages : "")), this.font));
        int y = 26;
        if (rows.isEmpty())
            addRenderableWidget(new StringWidget(this.width / 2 - 150, y, 300, 12, Component.literal("No values to show."), this.font));
        for (int i = page * per; i < Math.min(rows.size(), (page + 1) * per); i++) {
            Row r = rows.get(i);
            StringWidget label = new StringWidget(x, y, labelW, h, Component.literal(r.name()), this.font);
            label.setTooltip(Tooltip.create(Component.literal(tooltip(r))));
            addRenderableWidget(label);
            addRenderableWidget(control(r, x + labelW + gap, y, ctlW, h));
            y += h + gap;
        }
        int by = this.height - 28, bw = 90, x0 = this.width / 2 - 163;   // < Done Cancel Open file >
        Button prev = Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(x0, by, 20, h).build();
        Button next = Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(x0 + 306, by, 20, h).build();
        prev.active = page > 0;
        next.active = page < pages - 1;
        addRenderableWidget(prev);
        addRenderableWidget(next);
        done = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> { save(); Screens.set(parent); })
                .bounds(x0 + 24, by, bw, h).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x0 + 118, by, bw, h).build());
        List<Path> files = ConfigScreens.files(mod);
        Button open = Button.builder(Component.literal("Open file"), b -> { if (!files.isEmpty()) ConfigScreens.openFile(files.get(0)); })
                .bounds(x0 + 212, by, bw, h).build();
        open.active = !files.isEmpty();
        addRenderableWidget(open);
        refresh();
    }

    private Button cycle(Row r, int x, int y, int w, int h) {
        return Button.builder(Component.literal(show(r.pending)), b -> {
            r.pending = SpecValues.next(r.kind, r.spec, r.pending);
            r.text = show(r.pending);
            b.setMessage(Component.literal(r.text));
            refresh();
        }).bounds(x, y, w, h).build();
    }

    private net.minecraft.client.gui.components.AbstractWidget control(Row r, int x, int y, int w, int h) {
        switch (r.kind) {
            case BOOL, ENUM -> { return cycle(r, x, y, w, h); }
            case READ_ONLY -> {
                StringWidget s = new StringWidget(x, y, w, h, Component.literal(show(r.pending)), this.font);
                s.setTooltip(Tooltip.create(Component.literal(tooltip(r))));
                return s;
            }
            default -> {
                EditBox e = new EditBox(this.font, x, y, w, h, Component.literal(r.name()));
                e.setMaxLength(4096);
                e.setValue(r.text);
                e.setTooltip(Tooltip.create(Component.literal(tooltip(r))));
                e.setResponder(s -> {
                    r.text = s;
                    Object v = SpecValues.parse(r.kind, r.spec, s);
                    r.valid = v != null;
                    if (v != null) r.pending = v;
                    e.setTextColor(r.valid ? EditBox.DEFAULT_TEXT_COLOR : 0xFFFF5555);
                    refresh();
                });
                if (!r.valid) e.setTextColor(0xFFFF5555);
                return e;
            }
        }
    }

    private void refresh() {
        if (done != null) done.active = rows.stream().allMatch(r -> r.valid);
    }

    /** writes the changed values through the spec, then saves each changed config file once */
    void save() {
        Set<ModConfig> touched = new LinkedHashSet<>();
        int n = 0;
        for (Row r : rows) {
            if (!r.changed()) continue;
            try {
                r.value.set(r.pending);
                touched.add(r.config);
                n++;
            } catch (Throwable t) {
                Bridge.LOG.warn("Alloy config: {} of {} not set: {}", String.join(".", r.path), mod.label(), t.toString());
            }
        }
        for (ModConfig c : touched) {
            try {
                c.save();
            } catch (Throwable t) {
                Bridge.LOG.warn("Alloy config: {} not saved through ModConfig.save ({}), trying the spec", c.getFileName(), t.toString());
                try { ((ForgeConfigSpec) c.<ForgeConfigSpec>getSpec()).save(); } catch (Throwable e) {
                    Bridge.LOG.warn("Alloy config: {} not saved: {}", c.getFileName(), e.toString());
                }
            }
        }
        Bridge.LOG.info("Alloy config: SpecScreen saved {} changed value(s) of {} to {}", n, mod.label(),
                touched.stream().map(ModConfig::getFileName).toList());
    }

    @Override
    public void onClose() {
        Screens.set(parent);
    }
}
