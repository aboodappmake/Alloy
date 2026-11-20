package alloy.installer;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The crash recovery window (system look and feel, like the installer's): what crashed, the likely cause, and one button
 * per way out. "Open crash report" opens the file and keeps the window. Closing the window starts the game normally and
 * asks again next time. Blocks until the player picks something.
 *
 * Test hook (CrashRecovery's class comment): -Dalloy.recover.testClick=disable|alternate|normal|open makes the window
 * click that button itself with java.awt.Robot (a real mouse click at the button), after -Dalloy.recover.testShot=FILE.png
 * was written; doClick() if the mouse click did not land within 3 s.
 */
final class RecoveryWindow {
    private RecoveryWindow() { }

    static CrashRecovery.Choice ask(Path game, CrashRecovery.Plan p) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        AtomicReference<CrashRecovery.Choice> out = new AtomicReference<>(new CrashRecovery.Choice(CrashRecovery.Action.CLOSED, null));
        try {
            SwingUtilities.invokeAndWait(() -> build(game, p, out).setVisible(true));
        } catch (Exception e) {
            Util.log("crash recovery: window failed (" + e + "), starting normally");
        }
        return out.get();
    }

    record Button(String kind, JButton b) { }

    static JDialog build(Path game, CrashRecovery.Plan p, AtomicReference<CrashRecovery.Choice> out) {
        JDialog d = new JDialog((Frame) null, "Alloy: the last start crashed", true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        CrashRecovery.Culprit top = p.culprits().get(0);
        String when = p.ev().phase() == CrashRecovery.Phase.WORLD_LOAD ? "while a world was loading" : "while it was starting";
        StringBuilder text = new StringBuilder();
        text.append("Minecraft crashed ").append(when).append(" last time (").append(p.ev().file().getFileName()).append(").\n\n");
        text.append(p.diagnosis().line()).append("\n\n");
        text.append(top.confidence() == CrashRecovery.Confidence.HIGH ? "Alloy thinks the cause is " : "The most likely cause is ")
                .append(top.jar().title()).append(" (").append(top.jar().folder()).append('/').append(top.jar().fileName()).append(").");
        if (p.culprits().size() > 1) {
            CrashRecovery.Culprit two = p.culprits().get(1);
            text.append(" It could also be ").append(two.jar().title()).append(" (").append(two.jar().fileName()).append(").");
        }
        text.append("\n\nNothing is deleted: a switched-off mod waits in .alloy/disabled/ and comes back with\n")
                .append(CrashRecovery.installerCmd(game)).append("--restore-disabled .\n")
                .append("If a world used that mod, Alloy backs the world up and warns you before it opens.");
        JTextArea info = new JTextArea(text.toString());
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setOpaque(false);
        info.setFont(UIManager.getFont("Label.font"));
        info.setSize(new Dimension(560, 10));   // wrap at this width first, so pack() gets the full wrapped height
        info.setPreferredSize(new Dimension(560, info.getPreferredSize().height + 4));
        root.add(info, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        List<Button> all = new ArrayList<>();
        for (CrashRecovery.Culprit c : p.culprits()) {
            if (c.canDisable()) {
                String more = c.dependents().isEmpty() ? "" : " and " + c.dependents().size() + " mod(s) that need it";
                JButton b = new JButton("Start without " + c.jar().title() + more + " (moves it to .alloy/disabled/, reversible)");
                if (!c.dependents().isEmpty()) b.setToolTipText("Also switched off: " + CrashRecovery.names(c.dependents()));
                b.addActionListener(e -> { out.set(new CrashRecovery.Choice(CrashRecovery.Action.DISABLE, c)); d.dispose(); });
                buttons.add(b);
                all.add(new Button("disable", b));
            }
            if (c.alternate() != null) {
                JButton b = new JButton("Try the " + c.alternate().loaderName() + " build of " + c.jar().title() + " (from the pack, reversible)");
                b.addActionListener(e -> { out.set(new CrashRecovery.Choice(CrashRecovery.Action.ALTERNATE, c)); d.dispose(); });
                buttons.add(b);
                all.add(new Button("alternate", b));
            }
        }
        JButton normal = new JButton("Start normally anyway");
        normal.addActionListener(e -> { out.set(new CrashRecovery.Choice(CrashRecovery.Action.NORMAL, null)); d.dispose(); });
        buttons.add(normal);
        all.add(new Button("normal", normal));
        JButton open = new JButton("Open crash report");
        open.addActionListener(e -> {
            try { Desktop.getDesktop().open(p.ev().file().toFile()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(d, "Open this file yourself:\n" + p.ev().file(), "Alloy", JOptionPane.INFORMATION_MESSAGE); }
        });
        buttons.add(open);
        all.add(new Button("open", open));
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(null);
        d.getRootPane().setDefaultButton(all.get(0).b());
        testClick(d, all);
        return d;
    }

    /** -Dalloy.recover.testClick: screenshot, then a real mouse click on that button (automated in-game tests only) */
    static void testClick(JDialog d, List<Button> all) {
        String kind = System.getProperty("alloy.recover.testClick");
        if (kind == null) return;
        JButton target = null;
        for (Button b : all) if (b.kind().equals(kind) && target == null) target = b.b();
        if (target == null) { Util.log("crash recovery: test click: no '" + kind + "' button"); return; }
        JButton t = target;
        Thread th = new Thread(() -> {
            try {
                Thread.sleep(1500);
                Robot r = new Robot();
                String shot = System.getProperty("alloy.recover.testShot");
                Rectangle[] box = new Rectangle[1];
                Point[] at = new Point[1];
                SwingUtilities.invokeAndWait(() -> {
                    d.toFront();
                    box[0] = d.getBounds();
                    Point o = t.getLocationOnScreen();
                    at[0] = new Point(o.x + t.getWidth() / 2, o.y + t.getHeight() / 2);
                });
                if (shot != null) javax.imageio.ImageIO.write(r.createScreenCapture(box[0]), "png", new java.io.File(shot));
                Util.log("crash recovery: test click on '" + t.getText() + "'");
                r.mouseMove(at[0].x, at[0].y);
                r.delay(150);
                r.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                r.delay(60);
                r.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(3000);
                if (d.isDisplayable()) { Util.log("crash recovery: test click did not land, pressing the button directly"); SwingUtilities.invokeLater(t::doClick); }
            } catch (Exception e) { Util.log("crash recovery: test click failed (" + e + ")"); SwingUtilities.invokeLater(t::doClick); }
        }, "alloy-recover-testclick");
        th.setDaemon(true);
        th.start();
    }
}
