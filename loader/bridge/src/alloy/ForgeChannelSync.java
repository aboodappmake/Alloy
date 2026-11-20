package alloy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Keeps Forge's per-connection remote channel list (read by Channel.isRemotePresent) in step with the channels the peer
 * announced through Fabric's minecraft:register / c:register. Everything is reflective: Forge classes are not on the
 * compile class path, and every call is a no-op when Forge's network layer is absent.
 *
 * Forge 64 (MC 26.1): NetworkContext.remoteChannels plus ForgeEventFactory.onChannelRegistrationChange and
 * NetworkInstance.registrationChange, as ChannelListManager.updateFrom does.
 * Forge 47 (MC 1.20.1, Fml.is47()): MCRegisterPacketHandler$ChannelList.updateFrom, the method Forge 47 itself runs for a
 * received minecraft:register / unregister payload (remote list + ChannelRegistrationChangeEvent per channel).
 * Forge 52 (MC 1.21.1, also Fml.is49()): the Forge 64 way with ResourceLocation, when the Forge 49 shapes below are missing.
 * Forge 49 (MC 1.20.4, Fml.is49()): the Forge 64 way with ResourceLocation, NetworkInstance.registrationChange(boolean) and
 * Forge's list from NetworkRegistry.buildChannelVersions() (non-minecraft ids, as ChannelListManager.addChannels does).
 */
public final class ForgeChannelSync {
    private ForgeChannelSync() {}

    private static boolean init;
    // Forge 64
    private static Method ctxGet, buildList, onChange, regChange, findTarget;
    private static boolean regChangeOneArg;   // Forge 49: registrationChange(boolean)
    private static Field remote, remoteSize;
    private static Object typeRegister, typeUnregister;
    // Forge 47
    private static Method channelVersions, channelListOf, updateFrom;
    private static Constructor<?> contextNew, friendlyBuf;
    private static Field receiving;
    private static Object toClient, toServer;
    private static Method wrappedBuffer;

    private static synchronized boolean ready() {
        if (!init) {
            init = true;
            boolean ok = false;
            try {
                if (Fml.is47()) { ready47(); ok = true; }
                else if (Fml.is49()) {
                    // Forge 49 (1.20.4) has registrationChange(boolean); Forge 52 (1.21.1) has the Forge 64 shape with
                    // ResourceLocation (registrationChange(ResourceLocation, boolean), buildRegisterList)
                    try { ready49(); ok = true; } catch (Throwable t49) { ok = ready64(); }
                }
                else ok = ready64();
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                ctxGet = null;
                updateFrom = null;   // not a Forge runtime (or layout changed): stay inert
                if (Fml.is47() || Fml.is49()) Bridge.LOG.warn("Alloy: Forge " + Fml.FAMILY.substring(3) + " channel list sync is off (Forge's network classes did not match); "
                        + "Forge's Channel.isRemotePresent may miss channels a Fabric peer registers");
            }
        }
        return ctxGet != null || updateFrom != null;
    }

    private static boolean ready64() {
        try {
            Class<?> ctx = Class.forName("net.minecraftforge.network.NetworkContext");
            Class<?> reg = Class.forName("net.minecraftforge.network.NetworkRegistry");
            Class<?> conn = Class.forName("net.minecraft.network.Connection");
            Class<?> ident;
            try { ident = Class.forName("net.minecraft.resources.Identifier"); }               // 26.x
            catch (ClassNotFoundException e) { ident = Class.forName("net.minecraft.resources.ResourceLocation"); }   // Forge 52
            Method get = ctx.getMethod("get", conn);
            Field rc = ctx.getDeclaredField("remoteChannels"); rc.setAccessible(true);
            Field rs = ctx.getDeclaredField("remoteChannelsSize"); rs.setAccessible(true);
            Method bl = reg.getDeclaredMethod("buildRegisterList"); bl.setAccessible(true);
            Class<?> type = Class.forName("net.minecraftforge.event.network.ChannelRegistrationChangeEvent$Type");
            typeRegister = type.getField("REGISTER").get(null);
            typeUnregister = type.getField("UNREGISTER").get(null);
            onChange = Class.forName("net.minecraftforge.event.ForgeEventFactory")
                    .getMethod("onChannelRegistrationChange", conn, type, HashSet.class);
            findTarget = reg.getDeclaredMethod("findTarget", ident); findTarget.setAccessible(true);
            regChange = Class.forName("net.minecraftforge.network.NetworkInstance")
                    .getDeclaredMethod("registrationChange", ident, boolean.class);
            regChange.setAccessible(true);
            remote = rc; remoteSize = rs; buildList = bl; ctxGet = get;
            regChangeOneArg = false; channelVersions49 = null;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Forge 49: ready64 with the 1.20.4 shapes. Throws if anything is missing. */
    private static void ready49() throws Exception {
        Class<?> ctx = Class.forName("net.minecraftforge.network.NetworkContext");
        Class<?> reg = Class.forName("net.minecraftforge.network.NetworkRegistry");
        Class<?> conn = Class.forName("net.minecraft.network.Connection");
        Class<?> ident = Class.forName("net.minecraft.resources.ResourceLocation");
        Method get = ctx.getMethod("get", conn);
        Field rc = ctx.getDeclaredField("remoteChannels"); rc.setAccessible(true);
        Field rs = ctx.getDeclaredField("remoteChannelsSize"); rs.setAccessible(true);
        Method cv = reg.getDeclaredMethod("buildChannelVersions"); cv.setAccessible(true);
        Class<?> type = Class.forName("net.minecraftforge.event.network.ChannelRegistrationChangeEvent$Type");
        typeRegister = type.getField("REGISTER").get(null);
        typeUnregister = type.getField("UNREGISTER").get(null);
        onChange = Class.forName("net.minecraftforge.event.ForgeEventFactory")
                .getMethod("onChannelRegistrationChange", conn, type, HashSet.class);
        findTarget = reg.getDeclaredMethod("findTarget", ident); findTarget.setAccessible(true);
        regChange = Class.forName("net.minecraftforge.network.NetworkInstance").getDeclaredMethod("registrationChange", boolean.class);
        regChange.setAccessible(true);
        regChangeOneArg = true;
        channelVersions49 = cv;
        remote = rc; remoteSize = rs; ctxGet = get;
    }

    private static Method channelVersions49;

    /** Forge 47: the classes and members MCRegisterPacketHandler uses. Throws if anything is missing. */
    private static void ready47() throws Exception {
        Class<?> reg = Class.forName("net.minecraftforge.network.NetworkRegistry");
        Class<?> conn = Class.forName("net.minecraft.network.Connection");
        Class<?> handler = Class.forName("net.minecraftforge.network.MCRegisterPacketHandler");
        Class<?> list = Class.forName("net.minecraftforge.network.MCRegisterPacketHandler$ChannelList");
        Class<?> ctx = Class.forName("net.minecraftforge.network.NetworkEvent$Context");
        Class<?> dir = Class.forName("net.minecraftforge.network.NetworkDirection");
        Class<?> type = Class.forName("net.minecraftforge.network.NetworkEvent$RegistrationChangeType");
        Class<?> fbb = Class.forName("net.minecraft.network.FriendlyByteBuf");
        Class<?> flow = Class.forName("net.minecraft.network.protocol.PacketFlow");
        Method cv = reg.getDeclaredMethod("buildChannelVersions"); cv.setAccessible(true);
        Method of = handler.getDeclaredMethod("getFrom", conn); of.setAccessible(true);   // creates the list if the connection has none
        Method uf = list.getMethod("updateFrom", Supplier.class, fbb, type);
        Constructor<?> cn = ctx.getDeclaredConstructor(conn, dir, int.class); cn.setAccessible(true);
        Field rv = null;   // Connection.receiving, found by type so it works with any member names
        for (Field f : conn.getDeclaredFields()) if (f.getType() == flow && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) { rv = f; break; }
        if (rv == null) throw new NoSuchFieldException("Connection: no PacketFlow field");
        rv.setAccessible(true);
        Class<?> unpooled = Class.forName("io.netty.buffer.Unpooled");
        Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf");
        wrappedBuffer = unpooled.getMethod("wrappedBuffer", byte[].class);
        friendlyBuf = fbb.getConstructor(byteBuf);
        typeRegister = type.getField("REGISTER").get(null);
        typeUnregister = type.getField("UNREGISTER").get(null);
        toClient = dir.getField("PLAY_TO_CLIENT").get(null);
        toServer = dir.getField("PLAY_TO_SERVER").get(null);
        receiving = rv; contextNew = cn; channelListOf = of; channelVersions = cv; updateFrom = uf;
    }

    /** Forge's own channel ids (what Forge itself would put in its minecraft:register payload). */
    public static List<?> forgeChannels() {
        if (!ready()) return List.of();
        try {
            if (updateFrom != null) {   // Forge 47: NetworkHooks.sendMCRegistryPackets sends every non-minecraft channel
                List<Object> out = new ArrayList<>();
                for (Object id : ((Map<?, ?>) channelVersions.invoke(null)).keySet())
                    if (!id.toString().startsWith("minecraft:")) out.add(id);
                return out;
            }
            if (channelVersions49 != null) {   // Forge 49: ChannelListManager.addChannels' list
                List<Object> out = new ArrayList<>();
                for (Object id : ((Map<?, ?>) channelVersions49.invoke(null)).keySet())
                    if (!id.toString().startsWith("minecraft:")) out.add(id);
                return out;
            }
            return new ArrayList<>((List<?>) buildList.invoke(null));
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Mirror of ChannelListManager.updateFrom (Forge 64) / ChannelList.updateFrom (Forge 47) for a peer register/unregister received through Fabric. */
    @SuppressWarnings("unchecked")
    public static void update(Object connection, Collection<?> ids, boolean register) {
        if (connection == null || ids.isEmpty() || !ready()) return;
        try {
            if (updateFrom != null) { update47(connection, ids, register); return; }
            HashSet<Object> set = new HashSet<>(ids);
            onChange.invoke(null, connection, register ? typeRegister : typeUnregister, set);
            Object ctx = ctxGet.invoke(null, connection);
            Set<Object> remoteSet = (Set<Object>) remote.get(ctx);
            long size = remoteSize.getLong(ctx);
            for (Object id : set) {
                boolean changed = register ? remoteSet.add(id) : remoteSet.remove(id);
                if (!changed) continue;
                size += register ? id.toString().length() : -id.toString().length();
                Object target = findTarget.invoke(null, id);
                if (target != null) { if (regChangeOneArg) regChange.invoke(target, register); else regChange.invoke(target, id, register); }
            }
            remoteSize.setLong(ctx, size);
        } catch (Throwable t) {
            System.err.println("[Alloy] could not mirror channel registration into Forge: " + t);
        }
    }

    /** Forge 47: hand Forge the payload it would have received (channel ids separated by NUL), as MCRegisterPacketHandler does. */
    private static void update47(Object connection, Collection<?> ids, boolean register) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (Object id : ids) {
            bytes.write(id.toString().getBytes(StandardCharsets.UTF_8));
            bytes.write(0);
        }
        // the receiving side: a client connection receives CLIENTBOUND packets (PLAY_TO_CLIENT)
        boolean client = "CLIENTBOUND".equals(((Enum<?>) receiving.get(connection)).name());
        Object ctx = contextNew.newInstance(connection, client ? toClient : toServer, 0);
        Supplier<Object> source = () -> ctx;
        Object buf = friendlyBuf.newInstance(wrappedBuffer.invoke(null, (Object) bytes.toByteArray()));
        updateFrom.invoke(channelListOf.invoke(null, connection), source, buf, register ? typeRegister : typeUnregister);
    }
}
