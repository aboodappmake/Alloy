package alloy.perms;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.handler.IPermissionHandler;
import net.minecraftforge.server.permission.nodes.PermissionDynamicContextKey;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionType;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fabric mods' permission checks (Lucko's fabric-permissions-api, {@code Permissions.check}) answered by Forge's active
 * permission handler, so a node set with LuckPerms for Forge (or FTB Ranks, or any Forge handler) reaches Fabric mods.
 *
 * <ul>
 *   <li>One listener on {@code PermissionCheckEvent} and one on {@code OfflinePermissionCheckEvent}, registered in their own
 *       phase after the default one. A Fabric side provider (LuckPerms Fabric and the like) in the default phase keeps
 *       priority: the first listener that does not return DEFAULT wins.</li>
 *   <li>Only a {@code CommandSourceStack} whose entity is a {@code ServerPlayer} is asked about. Console, command blocks,
 *       functions, other entities and client side sources get DEFAULT (the mod's own fallback, as before).</li>
 *   <li>The node goes straight to Forge's active handler (the private static {@code PermissionAPI.activeHandler}), not
 *       through {@code PermissionAPI.getPermission}, which throws for nodes nobody registered in
 *       {@code PermissionGatherEvent.Nodes}. LuckPerms' handler resolves by name and does not need the registration.</li>
 *   <li>The node is a BOOLEAN node whose default resolver returns null. A handler with nothing set returns the resolver's
 *       result, so "not set" comes back as null and becomes DEFAULT: the Fabric caller applies its op level or boolean
 *       default. Mapping it to FALSE would lock ops out of every op-only Fabric command.</li>
 *   <li>No handler yet (before the server started), a failing handler or reflection that does not work: DEFAULT.</li>
 * </ul>
 * The fabric-permissions-api types (the events, Fabric API's Event and TriState) are reached by reflection and a Proxy,
 * so the bridge compiles without them and Fabric Loader's own copy of the library (the newest of the jar-in-jar copies)
 * is the one used. -Dalloy.permsBridge=false turns it off, -Dalloy.permsBridge.debug=true logs every answer.
 */
public final class PermsBridge {
    static final String API = "me.lucko.fabric.api.permissions.v0.";
    static final String PHASE_NS = "alloyloader", PHASE_PATH = "forge_permissions";
    static final int CACHE_MAX = 4096;

    /** Where the log lines go (PermsBridgeEntry points them at Alloy's log). */
    public static Consumer<String> info = s -> {}, warn = s -> {};
    static boolean debug = Boolean.getBoolean("alloy.permsBridge.debug");

    static Object TRUE, FALSE, DEFAULT;   // Fabric API TriState constants
    static final PermissionNode.PermissionResolver<Boolean> UNSET = (player, uuid, contexts) -> null;
    static final Map<String, Optional<PermissionNode<Boolean>>> NODES = new ConcurrentHashMap<>();
    static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    static volatile Field activeField;
    static volatile boolean fieldTried;
    static volatile Object lastHandler;
    static volatile boolean firstAnswer = true;

    private PermsBridge() {}

    /** Registers the listeners on the fabric-permissions-api classes {@code loader} sees. False when that failed. */
    public static boolean init(ClassLoader loader) {
        try {
            Class<?> online = Class.forName(API + "PermissionCheckEvent", true, loader);
            Method m = method(online, "onPermissionCheck");
            Class<?> tri = m.getReturnType();
            TRUE = constant(tri, "TRUE");
            FALSE = constant(tri, "FALSE");
            DEFAULT = constant(tri, "DEFAULT");
            String where = register(online.getField("EVENT").get(null), listener(online, false));
            String offline = "no offline event in this version";
            try {
                Class<?> off = Class.forName(API + "OfflinePermissionCheckEvent", true, loader);
                offline = "offline checks too";
                register(off.getField("EVENT").get(null), listener(off, true));
            } catch (ClassNotFoundException e) {
                // fabric-permissions-api before 0.2 has no offline event
            }
            info.accept("Alloy permissions: Fabric permission checks (fabric-permissions-api) for players are answered by Forge's"
                    + " permission handler; listener " + where + ", " + offline + "; nodes not set keep the mod's own fallback");
            return true;
        } catch (Throwable t) {
            warn.accept("Alloy permissions: bridge not started, Fabric mods keep their op-level fallback: " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------- Fabric side

    static Object listener(Class<?> type, boolean offline) {
        InvocationHandler h = (proxy, m, a) -> switch (m.getName()) {
            case "onPermissionCheck" -> offline
                    ? checkOfflineAsync((UUID) a[0], (String) a[1])
                    : check(a[0], (String) a[1]);
            case "toString" -> "Alloy Forge permissions bridge";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == a[0];
            default -> throw new UnsupportedOperationException(m.toString());
        };
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, h);
    }

    /** Event.register(phase, listener) in a phase ordered after Event.DEFAULT_PHASE; plain register(listener) if phases
     *  cannot be used. Returns how it was registered (for the log). */
    static String register(Object event, Object listener) throws ReflectiveOperationException {
        Class<?> ev = Class.forName("net.fabricmc.fabric.api.event.Event", false, event.getClass().getClassLoader());
        try {
            Field dp = ev.getField("DEFAULT_PHASE");
            Class<?> idType = dp.getType();
            Object def = dp.get(null);
            Object phase = id(idType, PHASE_NS, PHASE_PATH);
            ev.getMethod("addPhaseOrdering", idType, idType).invoke(event, def, phase);
            ev.getMethod("register", idType, Object.class).invoke(event, phase, listener);
            return "in phase " + phase + " after the default phase";
        } catch (ReflectiveOperationException | RuntimeException e) {
            ev.getMethod("register", Object.class).invoke(event, listener);
            return "in the default phase (" + e + ")";
        }
    }

    /** The game's resource id class (ResourceLocation, Identifier) from namespace and path, whichever factory it has. */
    static Object id(Class<?> c, String ns, String path) throws ReflectiveOperationException {
        for (String name : new String[]{"fromNamespaceAndPath", "of"}) {
            try {
                Method f = c.getMethod(name, String.class, String.class);
                if (Modifier.isStatic(f.getModifiers()) && c.isAssignableFrom(f.getReturnType())) return f.invoke(null, ns, path);
            } catch (NoSuchMethodException ignored) {
            }
        }
        try {
            Constructor<?> k = c.getConstructor(String.class, String.class);
            return k.newInstance(ns, path);
        } catch (NoSuchMethodException ignored) {
        }
        for (String name : new String[]{"tryParse", "parse"}) {
            try {
                Method f = c.getMethod(name, String.class);
                if (Modifier.isStatic(f.getModifiers()) && c.isAssignableFrom(f.getReturnType())) {
                    Object r = f.invoke(null, ns + ":" + path);
                    if (r != null) return r;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("no id factory on " + c.getName());
    }

    /** The PermissionCheckEvent answer for one check. */
    static Object check(Object source, String node) {
        if (!PermsMapping.usable(node) || !(source instanceof CommandSourceStack css)) return DEFAULT;
        if (!(css.getEntity() instanceof ServerPlayer player)) return DEFAULT;
        return answer(node, query(player, null, node));
    }

    /** The OfflinePermissionCheckEvent answer (the handler's offline lookup; LuckPerms answers for loaded users). */
    static Object checkOffline(UUID uuid, String node) {
        if (uuid == null || !PermsMapping.usable(node)) return DEFAULT;
        return answer(node, query(null, uuid, node));
    }

    /** The offline answer as a future. LuckPerms' Forge handler only knows loaded users, so for a player who is not online
     *  (and not cached) it returns "not set". Then, when LuckPerms is the active handler, the user is loaded through the
     *  LuckPerms API (UserManager.loadUser, from storage, off the server thread) and its permission data answers, which
     *  is what LuckPerms Fabric does. Any failure: DEFAULT. */
    static CompletableFuture<Object> checkOfflineAsync(UUID uuid, String node) {
        if (uuid == null || !PermsMapping.usable(node)) return CompletableFuture.completedFuture(DEFAULT);
        Object value = query(null, uuid, node);
        Object h = lastHandler;
        String id = h instanceof IPermissionHandler ph ? String.valueOf(ph.getIdentifier()) : null;
        if (!PermsMapping.useLuckPermsOffline(id, value)) return CompletableFuture.completedFuture(answer(node, value));
        try {
            return luckPermsOffline(h.getClass().getClassLoader(), uuid, node)
                    .handle((tri, t) -> {
                        if (t != null) {
                            once("lpoffline:" + t.getClass().getName(), "Alloy permissions: LuckPerms offline lookup failed (DEFAULT used, warned once): " + t);
                            return DEFAULT;
                        }
                        return answerName(node, PermsMapping.fromLuckPerms(tri), "LuckPerms offline " + tri);
                    });
        } catch (Throwable t) {
            once("lpoffline:" + t.getClass().getName(), "Alloy permissions: LuckPerms API not reachable for offline checks (DEFAULT used, warned once): " + t);
            return CompletableFuture.completedFuture(DEFAULT);
        }
    }

    /** LuckPermsProvider.get().getUserManager().loadUser(uuid) then getCachedData().getPermissionData().checkPermission(node),
     *  by reflection on the public API interfaces seen by LuckPerms' own class loader. Completes with the LP Tristate. */
    static CompletableFuture<Object> luckPermsOffline(ClassLoader cl, UUID uuid, String node) throws ReflectiveOperationException {
        Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, cl);
        Class<?> lpApi = Class.forName("net.luckperms.api.LuckPerms", false, cl);
        Class<?> users = Class.forName("net.luckperms.api.model.user.UserManager", false, cl);
        Class<?> user = Class.forName("net.luckperms.api.model.user.User", false, cl);
        Class<?> cached = Class.forName("net.luckperms.api.cacheddata.CachedDataManager", false, cl);
        Class<?> perms = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData", false, cl);
        Method getUserManager = lpApi.getMethod("getUserManager"), loadUser = users.getMethod("loadUser", UUID.class);
        Method getCachedData = user.getMethod("getCachedData"), getPermissionData = cached.getMethod("getPermissionData");
        Method checkPermission = perms.getMethod("checkPermission", String.class);
        Object um = getUserManager.invoke(provider.getMethod("get").invoke(null));
        CompletableFuture<?> f = (CompletableFuture<?>) loadUser.invoke(um, uuid);
        return f.thenApply(u -> {
            try {
                return checkPermission.invoke(getPermissionData.invoke(getCachedData.invoke(u)), node);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    static Object answerName(String node, String name, String what) {
        if (debug || firstAnswer && !"DEFAULT".equals(name)) {
            if (!"DEFAULT".equals(name)) firstAnswer = false;
            info.accept("Alloy permissions: " + node + " -> " + name + " (" + what + ")");
        }
        return switch (name) {
            case "TRUE" -> TRUE;
            case "FALSE" -> FALSE;
            default -> DEFAULT;
        };
    }

    static Object answer(String node, Object value) {
        return answerName(node, PermsMapping.triState(value), "Forge handler value " + value);
    }

    // ---------------------------------------------------------------------------------------------------- Forge side

    /** The active handler's value for a BOOLEAN node with the bridge's null resolver; null when nothing answers. */
    static Object query(ServerPlayer player, UUID uuid, String node) {
        PermissionNode<Boolean> n = node(node);
        if (n == null) return null;
        IPermissionHandler h = handler();
        try {
            if (h != null) return player != null ? h.getPermission(player, n) : h.getOfflinePermission(uuid, n);
            if (activeField != null) return null;   // field read fine: no handler yet (server not started)
            // no access to the field: Forge's public API, which only works for registered nodes
            return player != null ? PermissionAPI.getPermission(player, n) : PermissionAPI.getOfflinePermission(uuid, n);
        } catch (Throwable t) {
            once("query:" + t.getClass().getName(), "Alloy permissions: the Forge handler failed on " + node + " (DEFAULT used, warned once): " + t);
            return null;
        }
    }

    static IPermissionHandler handler() {
        if (activeField == null && !fieldTried) {
            fieldTried = true;
            try {
                Field f = PermissionAPI.class.getDeclaredField("activeHandler");
                f.setAccessible(true);
                activeField = f;
            } catch (Throwable t) {
                warn.accept("Alloy permissions: cannot read Forge's active permission handler, only registered nodes are bridged: " + t);
            }
        }
        Field f = activeField;
        if (f == null) return null;
        Object h;
        try {
            h = f.get(null);
        } catch (Throwable t) {
            return null;
        }
        if (h != lastHandler) {
            lastHandler = h;
            if (h instanceof IPermissionHandler ph) {
                String id = String.valueOf(ph.getIdentifier());
                info.accept("Alloy permissions: Forge permission handler is " + id + " (" + h.getClass().getName() + ")"
                        + ("forge:default_handler".equals(id) ? "; no permission mod, Fabric mods keep their own fallback" : ""));
            }
        }
        return h instanceof IPermissionHandler ph ? ph : null;
    }

    /** BOOLEAN node with exactly this name and the null resolver, cached by name. */
    static PermissionNode<Boolean> node(String name) {
        Optional<PermissionNode<Boolean>> o = NODES.get(name);
        if (o == null) {
            o = Optional.ofNullable(makeNode(name));
            if (NODES.size() < CACHE_MAX) NODES.put(name, o);
        }
        return o.orElse(null);
    }

    @SuppressWarnings("unchecked")
    static PermissionNode<Boolean> makeNode(String name) {
        String[] p = PermsMapping.split(name);
        if (p != null) return new PermissionNode<>(p[0], p[1], PermissionTypes.BOOLEAN, UNSET);
        try {   // no dot: Forge's private (nodeName, type, resolver, dynamics) constructor keeps the name as it is
            Constructor<?> k = PermissionNode.class.getDeclaredConstructor(String.class, PermissionType.class,
                    PermissionNode.PermissionResolver.class, PermissionDynamicContextKey[].class);
            k.setAccessible(true);
            return (PermissionNode<Boolean>) k.newInstance(name, PermissionTypes.BOOLEAN, UNSET, new PermissionDynamicContextKey<?>[0]);
        } catch (Throwable t) {
            once("node:" + name, "Alloy permissions: node '" + name + "' has no dot and cannot be built (DEFAULT used): " + t);
            return null;
        }
    }

    static void once(String key, String message) {
        if (WARNED.add(key)) warn.accept(message);
    }

    // ---------------------------------------------------------------------------------------------------- reflection

    static Method method(Class<?> c, String name) throws NoSuchMethodException {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && !m.isDefault() && !Modifier.isStatic(m.getModifiers())) return m;
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object constant(Class<?> c, String name) {
        return Enum.valueOf((Class) c, name);
    }
}
