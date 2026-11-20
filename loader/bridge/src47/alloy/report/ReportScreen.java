package alloy.report;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
 *
 * Minecraft 1.20.1 version (bridge/src47 replaces bridge/src in the Forge 47 build): GuiGraphics.render instead of 26.1's
 * extractRenderState, net.minecraft.Util and OS.openFile. Layout and texts are the same as the 26.1 source.
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

    private static void open(Path p) {
        try { Util.getPlatform().openFile(p.toFile()); } catch (Throwable t) { alloy.Bridge.LOG.warn("Alloy: cannot open {}: {}", p, t.toString()); }
    }

    private void layout() {
        lines.clear();
        int maxW = Math.min(this.width - 40, 520);
        int room = (this.height - 40 - 34 - 12) / (this.font.lineHeight + 2);   // lines that fit between header and buttons
        int shown = 0;
        for (Entry e : errors) {
            List<Line> block = new ArrayList<>();
            block.add(new Line(e.title, 0xFFFF6B6B));
            if (!e.mod.isEmpty() || !e.jar.isEmpty())
                block.add(new Line(e.side + (e.mod.isEmpty() ? "" : " mod: " + e.mod) + (e.jar.isEmpty() ? "" : "   file: " + e.jar), 0xFFAAAAAA));
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);   // 1.20.1: Screen.render does not draw the background itself
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        g.drawCenteredString(this.font, "Details are also in " + report.getFileName(), this.width / 2, 24, 0xFFAAAAAA);
        int x = Math.max(20, (this.width - Math.min(this.width - 40, 520)) / 2), y = 42;
        for (Line l : lines) {
            if (!l.text().isEmpty()) g.drawString(this.font, l.text(), x, y, l.color());
            y += this.font.lineHeight + 2;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }   // the game cannot continue: only Quit leaves
}
