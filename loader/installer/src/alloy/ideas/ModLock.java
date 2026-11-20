package alloy.ideas;

import alloy.ideas.ModMeta.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Alloy mod lock file (prototype): one JSON file that pins exactly which jars a pack uses, by SHA-512.
 *
 *   ModLock lock   GAMEDIR [-o alloy.lock.json]   write the lock for mods/ + forgemods/
 *   ModLock verify GAMEDIR LOCK                      changed / missing / extra jars (exit 2 on any change)
 *   ModLock diff   SERVER.lock CLIENT.lock           what a client must add/remove/update to join that server
 *   ModLock origin LOCK                              (online, opt-in) ask Modrinth which project/version each hash is
 *
 * The lock is what a server publishes and what a modpack ships; the same file feeds server/client sync,
 * download verification, and "what changed since it last worked" in crash help. Offline except `origin`.
 */
public final class ModLock {
    /** Minecraft version written into new lock files (the installer sets it to the selected version) */
    public static String minecraft = "26.1.2";
    record Entry(String folder, String file, long size, String sha1, String sha512, String loader, String env, List<String> ids, Map<String, String> versions) {}

    public static void main(String[] a) throws Exception {
        System.exit(cli(a));
    }

    public static int cli(String[] a) throws Exception {
        if (a.length < 2) { usage(); return 64; }
        switch (a[0]) {
            case "lock" -> {
                Path out = a.length > 3 && a[2].equals("-o") ? Path.of(a[3]) : Path.of(a[1]).resolve("alloy.lock.json");
                List<Entry> es = scan(Path.of(a[1]));
                Files.writeString(out, toJson(es));
                System.out.println("wrote " + out + " (" + es.size() + " jars)");
            }
            case "verify" -> { return verify(Path.of(a[1]), Path.of(a[2])); }
            case "diff" -> { return diff(read(Path.of(a[1])), read(Path.of(a[2]))); }
            case "origin" -> origin(read(Path.of(a[1])));
            default -> { usage(); return 64; }
        }
        return 0;
    }

    static void usage() {
        System.out.println("usage: ModLock lock GAMEDIR [-o out.json] | verify GAMEDIR LOCK | diff SERVER.lock CLIENT.lock | origin LOCK");
    }

    static List<Entry> scan(Path game) throws Exception {
        List<String[]> todo = new ArrayList<>();   // folder, path: the entry order stays the folder order
        for (String folder : List.of("mods", "forgemods")) {
            for (Path p : ModMeta.jars(game.resolve(folder))) {
                if (folder.equals("mods") && ModMeta.isAlloyGenerated(p.getFileName().toString())) continue;
                todo.add(new String[]{folder, p.toString()});
            }
        }
        // reading + hashing every jar is independent per jar: do it on a few threads, keep the order
        Entry[] res = new Entry[todo.size()];
        Exception[] err = new Exception[1];
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread th = new Thread(r, "alloy-lock"); th.setDaemon(true); th.setPriority(Thread.MIN_PRIORITY); return th;
        });
        try {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < todo.size(); i++) {
                int k = i;
                fs.add(pool.submit(() -> { try { res[k] = entry(todo.get(k)[0], Path.of(todo.get(k)[1])); } catch (Exception e) { synchronized (err) { if (err[0] == null) err[0] = e; } } }));
            }
            for (var f : fs) f.get();
        } finally { pool.shutdownNow(); }
        if (err[0] != null) throw err[0];
        return new ArrayList<>(Arrays.asList(res));
    }

    private static Entry entry(String folder, Path p) throws Exception {
        String n = p.getFileName().toString();
        JarInfo ji = ModMeta.read(p);
        ModMeta.Loader as = folder.equals("mods") ? ModMeta.Loader.FABRIC : ModMeta.Loader.FORGE;
        List<String> ids = new ArrayList<>(); Map<String, String> vers = new LinkedHashMap<>();
        String env = "*";
        for (Mod m : ji.mods) if (m.loader == as) { ids.add(m.id); vers.put(m.id, m.version); if (!m.env.equals("*")) env = m.env; }
        String loader = ids.isEmpty() ? (ji.loaders.isEmpty() ? "none" : ji.loaders.iterator().next().name().toLowerCase()) : as.name().toLowerCase();
        return new Entry(folder, n, Files.size(p), ModMeta.hash(p, "SHA-1"), ModMeta.hash(p, "SHA-512"), loader, env, ids, vers);
    }

    // ---------------- verify ----------------
    static int verify(Path game, Path lockFile) throws Exception {
        List<Entry> lock = read(lockFile), now = scan(game);
        Map<String, Entry> byFile = new HashMap<>(), nowByFile = new HashMap<>();
        lock.forEach(e -> byFile.put(e.folder + "/" + e.file, e));
        now.forEach(e -> nowByFile.put(e.folder + "/" + e.file, e));
        int bad = 0;
        for (var e : byFile.entrySet()) {
            Entry cur = nowByFile.get(e.getKey());
            if (cur == null) { System.out.println("MISSING  " + e.getKey()); bad++; }
            else if (!cur.sha512.equals(e.getValue().sha512)) { System.out.println("CHANGED  " + e.getKey() + "  (hash differs from the lock: re-downloaded, updated, or modified)"); bad++; }
        }
        for (String k : nowByFile.keySet()) if (!byFile.containsKey(k)) { System.out.println("EXTRA    " + k + "  (not in the lock)"); bad++; }
        System.out.println(bad == 0 ? "OK: all " + lock.size() + " jars match the lock" : bad + " difference(s) from the lock");
        return bad == 0 ? 0 : 2;
    }

    // ---------------- diff (server/client sync) ----------------
    static int diff(List<Entry> server, List<Entry> client) {
        Map<String, Entry> s = byId(server), c = byId(client);
        int must = 0;
        List<String> out = new ArrayList<>();
        for (var e : s.entrySet()) {
            String key = e.getKey(); Entry se = e.getValue(); Entry ce = c.get(key);
            if (ce == null) {
                if (se.env.equals("server")) continue;
                if (se.env.equals("either")) { out.add("OPTIONAL " + key + " " + ver(se, key) + "  (" + se.file + "; the mod says clients may join without it)"); continue; }
                out.add("ADD      " + key + " " + ver(se, key) + "  (" + se.file + ", into " + se.folder + "/)"); must++;
            } else if (!Objects.equals(ver(se, key), ver(ce, key))) {
                out.add("UPDATE   " + key + " " + ver(ce, key) + " -> " + ver(se, key) + "  (server has " + se.file + ")"); must++;
            } else if (!se.sha512.equals(ce.sha512)) {
                out.add("NOTE     " + key + " same version, different file hash (" + se.file + " vs " + ce.file + "); fine unless one is a modified build");
            }
        }
        for (var e : c.entrySet()) {
            if (s.containsKey(e.getKey())) continue;
            Entry ce = e.getValue();
            if (ce.env.equals("client") || ce.env.equals("either")) out.add("OK       " + e.getKey() + " is client-only, the server does not need it");
            else out.add("CHECK    " + e.getKey() + " is on the client but not the server; if it adds blocks/items/entities the server will refuse the join"
                    + " (" + ce.file + ")");
        }
        out.sort(Comparator.naturalOrder());
        out.forEach(System.out::println);
        System.out.println(must == 0 ? "Client matches the server's required mods." : must + " change(s) needed before this client can join.");
        return must == 0 ? 0 : 2;
    }

    static String ver(Entry e, String key) { String id = key.substring(key.indexOf(':') + 1); return Objects.toString(e.versions.get(id), "?"); }
    static Map<String, Entry> byId(List<Entry> es) {
        Map<String, Entry> m = new TreeMap<>();
        for (Entry e : es) for (String id : e.ids) m.putIfAbsent(e.loader + ":" + id, e);
        return m;
    }

    // ---------------- origin (online, opt-in; read-only GET per hash) ----------------
    static void origin(List<Entry> es) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        for (Entry e : es) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/version_file/" + e.sha512 + "?algorithm=sha512"))
                    .header("User-Agent", "alloy-modlock-prototype").timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) { System.out.println("UNKNOWN  " + e.folder + "/" + e.file + "  not a Modrinth file (local build, CurseForge, or modified)"); continue; }
            if (r.statusCode() != 200) { System.out.println("ERROR    " + e.file + " HTTP " + r.statusCode()); continue; }
            @SuppressWarnings("unchecked") Map<String, Object> v = (Map<String, Object>) ModMeta.MiniJson.parse(r.body());
            System.out.println("MODRINTH " + e.folder + "/" + e.file + "  project " + v.get("project_id") + " version " + v.get("version_number")
                    + " loaders " + v.get("loaders") + " lists " + minecraft + ": " + (v.get("game_versions") instanceof List<?> g && g.contains(minecraft) ? "yes" : "NO"));
            Thread.sleep(250);   // be polite to the public API
        }
    }

    // ---------------- JSON in/out ----------------
    static String toJson(List<Entry> es) {
        StringBuilder b = new StringBuilder("{\n  \"format\": 1,\n  \"minecraft\": \"" + minecraft + "\",\n  \"jars\": [\n");
        for (int i = 0; i < es.size(); i++) {
            Entry e = es.get(i);
            b.append("    {\"folder\": ").append(q(e.folder)).append(", \"file\": ").append(q(e.file)).append(", \"size\": ").append(e.size)
             .append(", \"sha1\": ").append(q(e.sha1)).append(", \"sha512\": ").append(q(e.sha512)).append(", \"loader\": ").append(q(e.loader))
             .append(", \"env\": ").append(q(e.env)).append(", \"mods\": {");
            int j = 0;
            for (var v : e.versions.entrySet()) b.append(j++ > 0 ? ", " : "").append(q(v.getKey())).append(": ").append(q(v.getValue()));
            b.append("}}").append(i + 1 < es.size() ? ",\n" : "\n");
        }
        return b.append("  ]\n}\n").toString();
    }
    static String q(String s) { return ModMeta.MiniJson.quote(s); }

    @SuppressWarnings("unchecked")
    static List<Entry> read(Path p) throws Exception {
        Map<String, Object> root = (Map<String, Object>) ModMeta.MiniJson.parse(Files.readString(p));
        List<Entry> out = new ArrayList<>();
        for (Object o : (List<Object>) root.get("jars")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, String> vers = new LinkedHashMap<>();
            ((Map<String, Object>) m.get("mods")).forEach((k, v) -> vers.put(k, (String) v));
            out.add(new Entry((String) m.get("folder"), (String) m.get("file"), Long.parseLong(String.valueOf(m.get("size"))), (String) m.get("sha1"),
                    (String) m.get("sha512"), (String) m.get("loader"), (String) m.get("env"), new ArrayList<>(vers.keySet()), vers));
        }
        return out;
    }
}
