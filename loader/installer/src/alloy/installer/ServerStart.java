package alloy.installer;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/**
 * --server-start [SERVER ARGS]: the one-line server start for scripts, hosts and panels, run in the server folder:
 *   java -Xmx4G -jar .alloy/alloy-installer.jar --server-start nogui
 * Runs --prep (drop-in Forge mods, first-start downloads), then starts the server as a child JVM on this same Java with
 * this JVM's options (-Xmx and so on) and @.alloy/server.args. stdin/stdout are passed straight through, so a panel's
 * "stop" command and its "Done (" detection work; the exit code is the server's. Stopping this JVM stops the server.
 * Paths are relative to the server folder (the working dir, or the folder above .alloy when started from elsewhere).
 * This is the interim start; a single alloy-server.jar that runs the server in the same JVM comes later.
 */
final class ServerStart {
    private ServerStart() { }

    static int run(String[] args) throws Exception {
        Path game = Path.of("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(game.resolve(".alloy/prep.properties"))) {   // started from elsewhere: the jar sits in <server>/.alloy/
            Path jar = Util.selfJar().toAbsolutePath().normalize();
            if (jar.getParent() != null && jar.getParent().getFileName().toString().equals(".alloy")) game = jar.getParent().getParent();
        }
        Path argsFile = game.resolve(".alloy/server.args");
        if (!Files.isRegularFile(argsFile)) {
            System.err.println("[alloy] not an Alloy server folder: " + argsFile + " is missing. Run this in the server folder.");
            return 2;
        }
        CrashRecovery.atLaunch(game);   // the last start crashed: one line with the -Dalloy.recover flag to use
        try {
            Prep.run(game, false);
        } catch (Prep.Blocked e) {
            System.err.println("[alloy] " + e.getMessage());
            return 2;
        }
        boolean nogui = Files.readAllLines(argsFile).stream().anyMatch(l -> l.trim().equals("nogui"));
        List<String> cmd = new ArrayList<>(List.of(Util.ownJava().toString()));
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments())
            if (!a.startsWith("-Dalloy.relaunched") && !a.startsWith("-agentlib:jdwp") && !a.startsWith("-javaagent")) cmd.add(a);
        cmd.add("@.alloy/server.args");
        for (String a : args) if (!(nogui && a.equals("nogui"))) cmd.add(a);   // server.args already ends with nogui
        if (Boolean.getBoolean("alloy.printStart")) { System.out.println("[alloy] server command: " + String.join(" ", cmd)); return 0; }
        Process p = new ProcessBuilder(cmd).directory(game.toFile()).inheritIO().start();
        Thread hook = new Thread(() -> {   // panel kill / Ctrl+C on this JVM: stop the server too, give it time to save
            if (!p.isAlive()) return;
            p.destroy();
            try { p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { /* exit */ }
            if (p.isAlive()) p.destroyForcibly();
        });
        Runtime.getRuntime().addShutdownHook(hook);
        int rc = p.waitFor();
        try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException e) { /* shutting down */ }
        return rc;
    }
}
