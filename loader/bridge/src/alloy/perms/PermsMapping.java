package alloy.perms;

/**
 * The pure rules of the permissions bridge (PermsBridge), kept free of game classes so the unit tests run them alone.
 */
public final class PermsMapping {
    private PermsMapping() {}

    /** Name of the Fabric API TriState constant for a value from a Forge BOOLEAN node: true TRUE, false FALSE, anything else
     *  (null = the handler had nothing set and returned the bridge's default resolver result) DEFAULT, so the Fabric caller
     *  uses its own fallback (op level or its boolean default). */
    public static String triState(Object forgeValue) {
        if (forgeValue instanceof Boolean b) return b ? "TRUE" : "FALSE";
        return "DEFAULT";
    }

    /** Name of the Fabric API TriState constant for a LuckPerms API Tristate name (TRUE, FALSE, UNDEFINED). UNDEFINED and
     *  anything unknown are DEFAULT, for the same reason as above. */
    public static String fromLuckPerms(Object lpTristate) {
        String s = lpTristate == null ? "" : lpTristate.toString();
        return "TRUE".equals(s) || "FALSE".equals(s) ? s : "DEFAULT";
    }

    /** The offline LuckPerms path (loading the user from storage) only applies when LuckPerms is Forge's active handler
     *  and that handler had no answer: it only knows users that are loaded, so an offline player came back "not set". */
    public static boolean useLuckPermsOffline(String handlerId, Object handlerValue) {
        return handlerValue == null && "luckperms:permission_handler".equals(handlerId);
    }

    /** Forge's public PermissionNode constructor takes (modid, nodeName) and stores modid + "." + nodeName. Splitting the
     *  Fabric node at its first dot gives back the exact string. A node without a dot cannot be made that way: null. */
    public static String[] split(String node) {
        if (node == null) return null;
        int i = node.indexOf('.');
        if (i < 0) return null;
        return new String[]{node.substring(0, i), node.substring(i + 1)};
    }

    /** A node string the bridge asks Forge about. Empty or blank strings never are. */
    public static boolean usable(String node) {
        return node != null && !node.isBlank();
    }
}
