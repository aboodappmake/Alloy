package alloy.installer;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Swing front end (system look and feel): Client / Server tabs, launcher profile, Prism export, mod folders, Mod Doctor. */
final class Gui {
    private final JFrame frame = new JFrame("Alloy installer");
    private final JTextArea out = new JTextArea(12, 70);
    private final JProgressBar bar = new JProgressBar();
    private final JLabel status = new JLabel("Ready.");
    private final List<JComponent> lockable = new ArrayList<>();

    static void show() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new Gui().build());
    }

    /** One tab's inputs. */
    private final class Tab {
        final boolean client;
        final JTextField dir, forgeMods = new JTextField(30), fabricMods = new JTextField(30), mcDir = new JTextField(30),
                pack = new JTextField(30);   // modpack file(s), ';' between two packs
        final JCheckBox opt;   // client: download assets, server: accept EULA

        Tab(boolean client) {
            this.client = client;
            Path home = Path.of(System.getProperty("user.home"));
            Path mc = LauncherProfiles.defaultMcDir(Util.OS_NAME, System.getenv("APPDATA"), home);
            mcDir.setText(mc.toString());
            // the Flatpak launcher (Steam Deck) sees only its own folder: put the client install inside it
            Path flat = client ? LauncherProfiles.flatpakApp(mc) : null;
            dir = new JTextField((flat != null ? mc.resolve("Alloy-client") : home.resolve(client ? "Alloy-client" : "Alloy-server")).toString(), 30);
            opt = client ? new JCheckBox("Download game assets (about 450 to 700 MB)", true)
                    : new JCheckBox("I accept the Minecraft EULA (https://aka.ms/MinecraftEULA)");
        }

        Map<String, String> options() {
            Map<String, String> o = new HashMap<>();
            o.put("side", client ? "client" : "server");
            o.put("dir", dir.getText().trim());
            if (!forgeMods.getText().isBlank()) o.put("forge-mods", forgeMods.getText().trim());
            if (!fabricMods.getText().isBlank()) o.put("fabric-mods", fabricMods.getText().trim());
            if (!pack.getText().isBlank()) o.put("modpack", String.join("\n", pack.getText().trim().split("\\s*;\\s*")));
            if (client && !opt.isSelected()) o.put("no-assets", "1");
            if (!client && opt.isSelected()) o.put("eula", "1");
            return o;
        }
    }

    private JPanel row(String label, JTextField f, boolean browse) { return row(label, f, browse, false); }

    private JPanel row(String label, JTextField f, boolean browse, boolean packFiles) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(150, 24));
        p.add(l, BorderLayout.WEST);
        p.add(f, BorderLayout.CENTER);
        if (browse) {
            JButton b = new JButton("Browse...");
            b.addActionListener(e -> {
                JFileChooser c = new JFileChooser(f.getText().isBlank() ? null : new File(f.getText()));
                if (packFiles) {   // .mrpack or CurseForge .zip; pick two for a Forge pack + a Fabric pack
                    c.setFileSelectionMode(JFileChooser.FILES_ONLY);
                    c.setMultiSelectionEnabled(true);
                    c.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Modpacks (.mrpack, CurseForge .zip)", "mrpack", "zip"));
                    if (c.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                        List<String> ps = new ArrayList<>();
                        for (File x : c.getSelectedFiles()) ps.add(x.getPath());
                        f.setText(String.join("; ", ps));
                    }
                    return;
                }
                c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (c.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) f.setText(c.getSelectedFile().getPath());
            });
            lockable.add(b);
            p.add(b, BorderLayout.EAST);
        }
        lockable.add(f);
        return p;
    }

    private JPanel panel(Tab t) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        List<JComponent> rows = new ArrayList<>();
        rows.add(row("Install folder:", t.dir, true));
        rows.add(row("Forge mods folder:", t.forgeMods, true));
        rows.add(row("Fabric mods folder:", t.fabricMods, true));
        JPanel pr = row("Modpack (optional):", t.pack, true, true);
        t.pack.setToolTipText("A Modrinth .mrpack or CurseForge pack .zip. Pick two files for a Forge pack plus a Fabric pack.");
        rows.add(pr);
        JPanel o = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        o.add(t.opt);
        lockable.add(t.opt);
        rows.add(o);
        if (t.client) rows.add(row("Minecraft folder:", t.mcDir, true));
        for (JComponent r : rows) { r.setAlignmentX(Component.LEFT_ALIGNMENT); r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); p.add(r); p.add(Box.createVerticalStrut(6)); }

        JPanel btn = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (t.client) {
            JButton lau = new JButton("Install to the official Minecraft Launcher");
            lau.setToolTipText("Installs Alloy into the install folder and adds a launcher profile to the Minecraft folder (launcher_profiles.json is backed up first).");
            lau.addActionListener(e -> install(t, true, "Launcher profile"));
            JButton prism = new JButton("Export Prism / MultiMC instance");
            prism.setToolTipText("Installs Alloy and writes prism/Alloy <Minecraft version>.zip in the install folder.");
            prism.addActionListener(e -> install(t, false, "Prism / MultiMC instance"));
            btn.add(lau);
            btn.add(prism);
            lockable.add(lau);
            lockable.add(prism);
        } else {
            JButton srv = new JButton("Install server");
            srv.addActionListener(e -> install(t, false, "Server install"));
            btn.add(srv);
            lockable.add(srv);
        }
        JButton doc = new JButton("Run Mod Doctor");
        doc.setToolTipText("Checks the mods in the install folder (and the picked mod folders) and explains problems in plain words.");
        doc.addActionListener(e -> doctor(t));
        btn.add(doc);
        lockable.add(doc);
        JButton rep = new JButton("Make crash report");
        rep.setToolTipText("Collects the logs, the newest crash report, the mod list and the diagnosis of the install folder into one zip,"
                + " with the login token and other personal data removed. You see it before it is saved.");
        rep.addActionListener(e -> report(t));
        btn.add(rep);
        lockable.add(rep);
        p.add(btn);
        return p;
    }

    private void build() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Client", panel(new Tab(true)));
        tabs.addTab("Server", panel(new Tab(false)));
        out.setEditable(false);
        out.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        Util.sink = s -> SwingUtilities.invokeLater(() -> {
            out.append(s + "\n");
            out.setCaretPosition(out.getDocument().getLength());
            status.setText(s.length() > 110 ? s.substring(0, 110) + "..." : s);
        });
        JPanel prog = new JPanel(new BorderLayout(0, 4));
        prog.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(status, BorderLayout.NORTH);
        top.add(bar, BorderLayout.CENTER);
        prog.add(top, BorderLayout.NORTH);
        prog.add(new JScrollPane(out), BorderLayout.CENTER);
        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.add(versionRow(), BorderLayout.NORTH);
        north.add(tabs, BorderLayout.CENTER);
        frame.add(north, BorderLayout.NORTH);
        frame.add(prog, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** Minecraft version picker (versions bundled in this installer jar, default first). Applies to installs and Mod Doctor. */
    private JPanel versionRow() {
        JComboBox<String> box = new JComboBox<>(Installer.versions().toArray(new String[0]));
        box.setToolTipText("The Minecraft version to install. " + Installer.versions().get(0) + " is the tested default.");
        box.addActionListener(e -> {
            String v = (String) box.getSelectedItem();
            try { Installer.select(v); Util.log("Minecraft version: " + v + " (Forge " + Installer.FV + ", Fabric Loader " + Installer.LOADER + ")"); }
            catch (IOException ex) { Util.log("ERROR: " + ex.getMessage()); }
        });
        try { Installer.select((String) box.getSelectedItem()); } catch (IOException ignored) { }
        lockable.add(box);
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
        JLabel l = new JLabel("Minecraft version:");
        p.add(l);
        p.add(box);
        return p;
    }

    private void setBusy(boolean busy) {
        for (JComponent c : lockable) c.setEnabled(!busy);
        bar.setIndeterminate(busy);
        if (!busy) { bar.setValue(0); }
    }

    private void async(String label, ThrowingRunnable r) {
        setBusy(true);
        Util.log(label + ": started");
        new Thread(() -> {
            boolean ok = false;
            try { r.run(); ok = true; }
            catch (Throwable t) { Util.log("ERROR: " + (t.getMessage() != null ? t.getMessage() : t.toString())); }
            finally {
                Util.log(label + (ok ? ": finished" : ": failed"));
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        }, "alloy-gui-task").start();
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private void install(Tab t, boolean launcher, String label) {
        Map<String, String> o = t.options();
        if (o.get("dir").isEmpty()) { Util.log("Pick an install folder first."); return; }
        if (!t.client && !o.containsKey("eula")) Util.log("Note: EULA not accepted; eula.txt will be written with eula=false.");
        if (launcher) {
            if (t.mcDir.getText().isBlank()) { Util.log("Pick the Minecraft folder first."); return; }
            o.put("mc-dir", t.mcDir.getText().trim());
            String fp = LauncherProfiles.flatpakProblem(Path.of(o.get("mc-dir")), Path.of(o.get("dir")));
            if (fp != null) { Util.log(fp); return; }
        }
        if (o.containsKey("modpack")) async(label + " (modpack import)", () -> Modpack.cli(o));
        else async(label, () -> Main.run(o));
    }

    /** --report from the GUI: build the bundle, show every file (already cleaned), save only when the tester agrees */
    private void report(Tab t) {
        String dir = t.dir.getText().trim();
        Path game = dir.isEmpty() ? null : ReportBundle.gameDir(Path.of(dir));
        if (game == null) { Util.log("Crash report: " + (dir.isEmpty() ? "pick the install folder first." : dir + " has no logs, crash reports or mods yet.")); return; }
        async("Crash report", () -> {
            ReportBundle.Bundle b = ReportBundle.build(game);
            String text = b.preview(200_000);
            SwingUtilities.invokeLater(() -> {
                JTextArea area = new JTextArea(text, 30, 110);
                area.setEditable(false);
                area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                area.setCaretPosition(0);
                JPanel pane = new JPanel(new BorderLayout(0, 6));
                pane.add(new JLabel("<html>This is everything the zip will contain. The login token, account ids, player and user names,<br>"
                        + "e-mail and IP addresses were removed. Check it, then save it and send it privately (not on a public paste site).</html>"), BorderLayout.NORTH);
                pane.add(new JScrollPane(area), BorderLayout.CENTER);
                Object[] opts = {"Save zip...", "Cancel"};
                int c = JOptionPane.showOptionDialog(frame, pane, "Crash report preview", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
                if (c != 0) { Util.log("Crash report: not saved."); return; }
                JFileChooser fc = new JFileChooser(game.toFile());
                fc.setSelectedFile(game.resolve(ReportBundle.defaultName()).toFile());
                if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) { Util.log("Crash report: not saved."); return; }
                try {
                    ReportBundle.write(b, fc.getSelectedFile().toPath());
                    Util.log("Crash report saved: " + fc.getSelectedFile());
                } catch (IOException ex) { Util.log("ERROR: crash report not saved: " + ex.getMessage()); }
            });
        });
    }

    private void doctor(Tab t) {
        Map<String, String> o = t.options();
        List<String> a = new ArrayList<>(List.of(o.get("dir"), "--side", o.get("side")));
        if (o.containsKey("fabric-mods")) a.addAll(List.of("--mods", o.get("fabric-mods")));
        if (o.containsKey("forge-mods")) a.addAll(List.of("--forgemods", o.get("forge-mods")));
        async("Mod Doctor", () -> {
            PrintStream old = System.out;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int rc;
            try (PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
                System.setOut(ps);
                rc = alloy.ideas.ModDoctor.cli(a.toArray(new String[0]));
            } finally { System.setOut(old); }
            for (String line : buf.toString(StandardCharsets.UTF_8).split("\\R")) Util.log(line);
            Util.log(rc == 0 ? "Mod Doctor: no problems found." : rc == 1 ? "Mod Doctor: warnings (see above)." : "Mod Doctor: blockers found (see above).");
        });
    }
}
