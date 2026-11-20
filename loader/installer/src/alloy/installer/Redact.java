package alloy.installer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal data from log text before it goes into a crash report bundle (--report): the login token and the
 * other account arguments, session ids, JWT-shaped tokens, the Windows (or macOS / Linux) user name in paths, the player
 * name and UUID, e-mail addresses and network addresses.
 *
 * Two passes: {@link #learn} reads every text first and remembers the values it finds (player name from --username or
 * "Setting user:", the account UUID, the user folder name), then {@link #apply} replaces the patterns and every learned
 * value in each text. A value seen in one file (hs_err's command line) is then also removed from the others (latest.log).
 */
public final class Redact {
    public static final String TOKEN = "<token>", UUID = "<uuid>", PLAYER = "<player>", USER = "<user>", EMAIL = "<email>", IP = "<ip>", HIDDEN = "<redacted>";

    /** learned literal values -> their replacement, longest first when applied */
    private final Map<String, String> values = new HashMap<>();
    /** learned player or user names: replaced as whole words only */
    private final Map<String, String> words = new HashMap<>();

    /** account arguments of the game's command line; the value after them is removed (space, '=', ',' or a JSON list) */
    private static final Pattern ARG = Pattern.compile(
            "(?i)(--(accessToken|session|sessionId|uuid|xuid|clientId|username|userProperties|profileProperties|token|authToken|msaToken|refreshToken)\\b)"
            + "(\"?'?(?:\\s*[=,:]\\s*|\\s+)\"?'?)(\\{[^}\\n]*\\}|\\[[^\\]\\n]*\\]|[^\\s,\"'\\]\\[]+)");
    private static final Pattern KEYED = Pattern.compile(
            "(?i)([\"']?\\b(?:access_?token|refresh_?token|id_?token|client_?token|auth_?token|xsts_?token|xbl_?token|user_?token|msa_?token"
            + "|session_?id|session_?token|session_?key|api_?key|password|passwd)[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',;}{\\]\\[]{4,})");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    /** pre-1.20 session ids: token:<access token>:<uuid> */
    private static final Pattern SESSION = Pattern.compile("\\btoken:[A-Za-z0-9._~+/=-]{8,}:[0-9a-fA-F-]{32,36}");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}(?:\\.[A-Za-z0-9_-]*)?");
    /** C:\Users\NAME, C:\\Users\\NAME (escaped), C:/Users/NAME, C:\Documents and Settings\NAME */
    private static final Pattern WIN_HOME = Pattern.compile("(?i)\\b([A-Z]:(?:\\\\{1,2}|/)(?:Users|Documents and Settings)(?:\\\\{1,2}|/))([^\\\\/\\s\"':;|*?<>,\\]\\[]+)");
    private static final Pattern NIX_HOME = Pattern.compile("(?<![\\w.])(/(?:home|Users)/)([^/\\s\"':;,\\]\\[]+)");
    /** environment and system property lines that name the user or the PC (hs_err "Environment Variables", crash report properties) */
    private static final Pattern ENV = Pattern.compile("(?im)^(\\s*(?:USERNAME|USER|LOGNAME|USERDOMAIN|USERDOMAIN_ROAMINGPROFILE|COMPUTERNAME|HOSTNAME|LOGONSERVER"
            + "|HOMEPATH|USERPROFILE|ONEDRIVE|ONEDRIVECONSUMER|ONEDRIVECOMMERCIAL)\\s*=\\s*)(.+)$");
    private static final Pattern PROP = Pattern.compile("(?i)(\\buser\\.name\\s*[=:]\\s*)([^\\s,;]+)");
    private static final Pattern EMAIL_RX = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.(?!(?:jar|json|class|java|toml|txt|png|log|zip|properties|cfg)\\b)[A-Za-z]{2,}\\b");
    private static final String V4 = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}";
    /** an IPv4 address in a network context: "/1.2.3.4", "1.2.3.4:25565", or after ip / address / host / connecting to */
    private static final Pattern IP4 = Pattern.compile("(?i)(?<![\\w.])(?:(?<=/)(" + V4 + ")(?![\\w.])|(" + V4 + ")(?=:\\d{2,5}\\b)"
            + "|(?<=(?:\\bip|address|host|connecting to|connect to|server)[\\s:=(\\[]{1,3})(" + V4 + ")(?![\\w.]))");
    private static final Pattern IP6 = Pattern.compile("(?<=/)\\[?((?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4})(?:%\\w+)?\\]?(?=:\\d{2,5}\\b|[\\s,)]|$)"
            + "|\\[((?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4})(?:%\\w+)?\\](?=:\\d{2,5}\\b)");
    private static final Set<String> KEEP_IP = Set.of("127.0.0.1", "0.0.0.0", "255.255.255.255");
    /** names that are too common to replace everywhere as a word (they are still removed in paths and arguments) */
    private static final Set<String> COMMON = Set.of("user", "users", "admin", "administrator", "public", "default", "owner", "player", "test", "guest",
            "minecraft", "java", "server", "client", "steve", "alex", "home", "root", "game", "local", "system");

    // learning
    private static final Pattern L_ARG = Pattern.compile("(?i)--(username|uuid|xuid|accessToken|clientId|session|sessionId)\\b[\"']?(?:\\s*[=,:]\\s*|\\s+)[\"']?([^\\s,\"'\\]\\[]+)");
    private static final Pattern L_SETTING = Pattern.compile("Setting user: ([A-Za-z0-9_]{2,16})\\b");
    private static final Pattern L_UUIDOF = Pattern.compile("UUID of player ([A-Za-z0-9_]{2,16}) is ([0-9a-fA-F-]{32,36})");
    private static final Pattern L_ENVUSER = Pattern.compile("(?im)^\\s*USERNAME\\s*=\\s*([^\\s\\\\/]+)\\s*$");

    public Redact() {
        String u = System.getProperty("user.name");
        if (u != null) learnUser(u);
    }

    /** remember the personal values in {@code text} (call for every file before {@link #apply}) */
    public Redact learn(String text) {
        if (text == null) return this;
        Matcher m = L_ARG.matcher(text);
        while (m.find()) {
            String k = m.group(1).toLowerCase(Locale.ROOT), v = m.group(2);
            if (v.startsWith("$") || v.startsWith("<") || v.equals("[redacted]") || v.length() < 2) continue;   // placeholders: ${auth_player_name}
            switch (k) {
                case "username" -> learnPlayer(v);
                case "uuid" -> learnUuid(v);
                default -> { if (v.length() >= 6) values.put(v, TOKEN); }
            }
        }
        m = L_SETTING.matcher(text);
        while (m.find()) learnPlayer(m.group(1));
        m = L_UUIDOF.matcher(text);
        while (m.find()) { learnPlayer(m.group(1)); learnUuid(m.group(2)); }
        m = WIN_HOME.matcher(text);
        while (m.find()) learnUser(m.group(2));
        m = NIX_HOME.matcher(text);
        while (m.find()) learnUser(m.group(2));
        m = L_ENVUSER.matcher(text);
        while (m.find()) learnUser(m.group(1));
        return this;
    }

    void learnPlayer(String name) {
        if (name.length() < 3) return;
        if (!COMMON.contains(name.toLowerCase(Locale.ROOT))) words.put(name, PLAYER);
    }

    void learnUser(String name) {
        if (name.length() < 2 || name.startsWith("<")) return;
        if (!COMMON.contains(name.toLowerCase(Locale.ROOT)) && name.length() >= 3) words.put(name, USER);
    }

    void learnUuid(String v) {
        String plain = v.replace("-", "");
        if (!plain.matches("[0-9a-fA-F]{32}")) return;
        values.put(plain.toLowerCase(Locale.ROOT), UUID);
        values.put(plain.toUpperCase(Locale.ROOT), UUID);
        String dashed = plain.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
        values.put(dashed.toLowerCase(Locale.ROOT), UUID);
        values.put(dashed.toUpperCase(Locale.ROOT), UUID);
    }

    /** the text with every personal value replaced */
    public String apply(String text) {
        if (text == null || text.isEmpty()) return text;
        String t = text;
        t = replace(ARG, t, m -> m.group(1) + m.group(3) + switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "username" -> PLAYER;
            case "uuid", "xuid" -> UUID;
            case "userproperties", "profileproperties" -> HIDDEN;
            default -> TOKEN;
        });
        t = replace(L_SETTING, t, m -> "Setting user: " + PLAYER);
        t = replace(L_UUIDOF, t, m -> "UUID of player " + PLAYER + " is " + UUID);
        t = replace(SESSION, t, m -> "token:" + TOKEN + ":" + UUID);
        t = replace(JWT, t, m -> TOKEN);
        t = replace(BEARER, t, m -> m.group(1) + TOKEN);
        t = replace(KEYED, t, m -> m.group(1) + (m.group(2).startsWith("<") ? m.group(2) : TOKEN));
        t = replace(WIN_HOME, t, m -> m.group(1) + USER);
        t = replace(NIX_HOME, t, m -> m.group(1) + USER);
        t = replace(ENV, t, m -> m.group(1) + HIDDEN);
        t = replace(PROP, t, m -> m.group(1) + USER);
        t = replace(EMAIL_RX, t, m -> EMAIL);
        t = replace(IP4, t, m -> {
            String ip = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            return KEEP_IP.contains(ip) ? m.group() : m.group().replace(ip, IP);
        });
        t = replace(IP6, t, m -> {
            String ip = m.group(1) != null ? m.group(1) : m.group(2);
            String n = ip.replace("0", "").replace(":", "");
            return n.isEmpty() || n.equals("1") ? m.group() : m.group().replace(ip, IP);   // :: and ::1 (loopback) stay
        });
        // learned values: exact strings (tokens, UUIDs), then names as whole words
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        for (String k : keys) if (k.length() >= 3) t = t.replace(k, values.get(k));
        List<String> ws = new ArrayList<>(words.keySet());
        ws.sort(Comparator.comparingInt(String::length).reversed());
        for (String w : ws) t = Pattern.compile("(?<![\\w<])" + Pattern.quote(w) + "(?![\\w>])").matcher(t).replaceAll(Matcher.quoteReplacement(words.get(w)));
        return t;
    }

    /** one text on its own: learn from it, then apply */
    public static String clean(String text) { return new Redact().learn(text).apply(text); }

    private static String replace(Pattern p, String t, java.util.function.Function<Matcher, String> f) {
        Matcher m = p.matcher(t);
        if (!m.find()) return t;
        StringBuilder sb = new StringBuilder(t.length());
        do m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m))); while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Files the report never opens, whatever folder they are in: launcher account stores, token and credential files,
     * keys. The bundle only reads a fixed list of log files, this is the second lock.
     */
    public static boolean isSecretFile(java.nio.file.Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.startsWith("launcher_accounts") || n.startsWith("launcher_msa") || n.startsWith("launcher_profiles") || n.startsWith("launcher_entitlements")
                || n.equals("accounts.json") || n.contains("token") || n.contains("credential") || n.contains("secret") || n.contains("password")
                || n.contains("account") || n.endsWith(".key") || n.endsWith(".pem") || n.endsWith(".p12") || n.endsWith(".jks") || n.equals("options.txt")
                || n.equals("servers.dat") || n.equals("usercache.json") || n.equals("usernamecache.json") || n.equals("ops.json") || n.equals("whitelist.json");
    }
}
