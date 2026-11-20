package alloy.report;

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Client screen shown instead of Forge's raw LoadingErrorScreen when mod loading failed: the plain-language findings plus
 * "Open report", "Open mods folder" and "Quit". CLIENT ONLY: never reference this class from common code.
 */
public final class ReportScreen extends Screen {
    private record Line(String text, int color) { }

    private final List<Entry> errors;
    private final Path report;
    private final Path gameDir;
    private final List<Line> lines = new ArrayList<>();

    public ReportScreen(List<Entry> errors, Path report, Path gameDir) {
        super(Component.literal("Alloy: a mod problem stopped the game"));
        this.errors = errors;
        this.report = report;
        this.gameDir = gameDir;
    }

    @Override
    protected void init() {
        int w = 150, y = this.height - 28, gap = 6, x0 = (this.width - (3 * w + 2 * gap)) / 2;
        addRenderableWidget(Button.builder(Component.literal("Open report"), b -> open(report)).bounds(x0, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open mods folder"), b -> open(modsFolder())).bounds(x0 + w + gap, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Quit"), b -> Minecraft.getInstance().stop()).bounds(x0 + 2 * (w + gap), y, w, 20).build());
        layout();
    }

    private Path modsFolder() {
        boolean forge = false;
        for (Entry e : errors) if (e.side.equals("Forge")) forge = true;
        Path p = gameDir.resolve(forge ? "forgemods" : "mods");
        return Files.isDirectory(p) ? p : gameDir;
    }

    /** Open a folder in the system file browser. Up to 26.2 {@code Util.getPlatform().openPath(p)}; 26.3 moved it to the
     *  static {@code com.mojang.blaze3d.Blaze3D.openPath(p)}. Looked up by reflection so one bridge serves both. */
    private static void open(Path p) {
        try {
            try {
                Class.forName("com.mojang.blaze3d.Blaze3D").getMethod("openPath", Path.class).invoke(null, p);
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                Object os = Util.class.getMethod("getPlatform").invoke(null);
                Util.class.getMethod("getPlatform").getReturnType().getMethod("openPath", Path.class).invoke(os, p);
            }
        } catch (Throwable t) { alloy.Bridge.LOG.warn("Alloy: cannot open {}: {}", p, t.toString()); }
    }

    private void layout() {
        lines.clear();
        int maxW = Math.min(this.width - 40, 520);
        int room = (this.height - 40 - 34 - 12) / (this.font.lineHeight + 2);   // lines that fit between header and buttons
        int shown = 0;
        for (Entry e : errors) {
            List<Line> block = new ArrayList<>();
            for (String l : wrapPx(e.title, maxW)) block.add(new Line(l, 0xFFFF6B6B));
            // mod and file on their own wrapped lines: a long jar name ran off the right edge (seen in a real boot)
            if (!e.mod.isEmpty()) for (String l : wrapPx(e.side + " mod: " + e.mod, maxW)) block.add(new Line(l, 0xFFAAAAAA));
            if (!e.jar.isEmpty()) for (String l : wrapPx("File: " + e.jar, maxW)) block.add(new Line(l, 0xFFAAAAAA));
            if (!e.detail.startsWith(e.title))   // text-rule entries repeat the title as detail: show it once
                for (String l : wrapPx(e.detail, maxW)) block.add(new Line(l, 0xFFFFFFFF));
            if (!e.fix.isEmpty()) for (String l : wrapPx("What to do: " + e.fix, maxW)) block.add(new Line(l, 0xFF7CE08A));
            block.add(new Line("", 0));
            if (lines.size() + block.size() > room - 1 && shown > 0) {
                lines.add(new Line("... and " + (errors.size() - shown) + " more problem(s) in the report file.", 0xFFFFD866));
                return;
            }
            lines.addAll(block);
            shown++;
        }
    }

    private List<String> wrapPx(String text, int maxPx) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split("\\s+")) {
                if (line.length() > 0 && this.font.width(line + " " + word) > maxPx) { out.add(line.toString()); line.setLength(0); }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        g.centeredText(this.font, "Details are also in " + report.getFileName(), this.width / 2, 24, 0xFFAAAAAA);
        int x = Math.max(20, (this.width - Math.min(this.width - 40, 520)) / 2), y = 42;
        for (Line l : lines) {
            if (!l.text().isEmpty()) g.text(this.font, l.text(), x, y, l.color());
            y += this.font.lineHeight + 2;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }   // the game cannot continue: only Quit leaves
}
