package alloy.energy;

import alloy.Bridge;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.AttachCapabilitiesEvent;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Forge Energy (FE, IEnergyStorage capability) and Team Reborn Energy (E, team.reborn.energy.api.EnergyStorage, used by
 * Tech Reborn and most Fabric tech mods) as one network, for blocks:
 * <ul>
 *   <li>a Fabric block with a TR storage also answers Forge's ENERGY capability (attached to every block entity, looked up lazily);</li>
 *   <li>a Forge block with an FE capability is found by EnergyStorage.SIDED (registered as a fallback provider).</li>
 * </ul>
 * Ratio: config/alloy-energy.properties {@code fePerE} (default 10, the value Connector Extras uses); -Dalloy.energy=false
 * turns it off. Only active when Team Reborn Energy is installed. Items (data components) are not bridged yet.
 * The TR interface is used by reflection and a Proxy, so the bridge needs no TR jar to compile. A thread-local lock stops
 * the two lookups from finding each other's wrappers.
 */
public final class EnergyBridge {
    static long fePerE = 10;
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);
    private static final Identifier ID = Identifier.fromNamespaceAndPath("alloyloader", "fabric_energy");

    private static Class<?> trType;
    private static Method mInsert, mExtract, mAmount, mCapacity, mCanInsert, mCanExtract;
    private static BlockApiLookup<Object, Direction> sided;
    static volatile int fabricToForge, forgeToFabric;

    /** Called by EnergyBridgeEntry only when Team Reborn Energy (and so Fabric API) is installed. */
    static void init() {
        try {
            readConfig();
            trType = Class.forName("team.reborn.energy.api.EnergyStorage", true, EnergyBridge.class.getClassLoader());
            mInsert = trType.getMethod("insert", long.class, TransactionContext.class);
            mExtract = trType.getMethod("extract", long.class, TransactionContext.class);
            mAmount = trType.getMethod("getAmount");
            mCapacity = trType.getMethod("getCapacity");
            mCanInsert = trType.getMethod("supportsInsertion");
            mCanExtract = trType.getMethod("supportsExtraction");
            @SuppressWarnings("unchecked") BlockApiLookup<Object, Direction> s = (BlockApiLookup<Object, Direction>) trType.getField("SIDED").get(null);
            sided = s;
            sided.registerFallback(EnergyBridge::forgeAsFabric);
            AttachCapabilitiesEvent.BlockEntities.BUS.addListener(e -> e.addCapability(ID, new FabricAsForge(e.getObject())));
            Bridge.LOG.info("Alloy energy: Forge Energy <-> Team Reborn Energy bridge on ({} FE = 1 E), blocks only", fePerE);
        } catch (Throwable t) {
            Bridge.LOG.error("Alloy energy: bridge not started", t);
        }
    }

    private static void readConfig() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("alloy-energy.properties");
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, "# Alloy energy bridge: how many Forge Energy (FE) units one Team Reborn Energy (E) unit is worth\nfePerE=10\n");
            }
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f)) { p.load(r); }
            fePerE = Math.max(1, Long.parseLong(p.getProperty("fePerE", "10").trim()));
        } catch (IOException | RuntimeException e) {
            Bridge.LOG.warn("Alloy energy: cannot read {}: {} (using {} FE = 1 E)", f, e.toString(), fePerE);
        }
    }

    // ------------------------------------------------------------------------------------------ Forge -> Fabric

    /** EnergyStorage.SIDED fallback: a block entity with an FE capability, seen as a TR storage. */
    private static Object forgeAsFabric(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
                                        net.minecraft.world.level.block.state.BlockState state, BlockEntity be, Direction dir) {
        if (be == null || BUSY.get()) return null;
        BUSY.set(true);
        try {
            IEnergyStorage fe = be.getCapability(ForgeCapabilities.ENERGY, dir).orElse(null);
            if (fe == null || fe instanceof FeView) return null;
            if (forgeToFabric++ == 0) Bridge.LOG.info("Alloy energy: first Forge FE block seen as Team Reborn Energy: {} at {}", be.getType(), pos);
            return Proxy.newProxyInstance(trType.getClassLoader(), new Class<?>[]{trType}, new TrView(fe));
        } catch (Throwable t) {
            return null;
        } finally {
            BUSY.set(false);
        }
    }

    /** TR EnergyStorage over an FE IEnergyStorage. FE has no transactions: simulate now, do it when the outer transaction commits. */
    static final class TrView implements InvocationHandler {
        final IEnergyStorage fe;
        TrView(IEnergyStorage fe) { this.fe = fe; }

        @Override
        public Object invoke(Object proxy, Method m, Object[] a) {
            switch (m.getName()) {
                case "insert": {
                    long max = (Long) a[0];
                    int want = clamp(max * fePerE);
                    long e = fe.receiveEnergy(want, true) / fePerE;
                    if (e > 0) onCommit((TransactionContext) a[1], () -> fe.receiveEnergy(clamp(e * fePerE), false));
                    return e;
                }
                case "extract": {
                    long max = (Long) a[0];
                    int want = clamp(max * fePerE);
                    long e = fe.extractEnergy(want, true) / fePerE;
                    if (e > 0) onCommit((TransactionContext) a[1], () -> fe.extractEnergy(clamp(e * fePerE), false));
                    return e;
                }
                case "getAmount": return (long) fe.getEnergyStored() / fePerE;
                case "getCapacity": return (long) fe.getMaxEnergyStored() / fePerE;
                case "supportsInsertion": return fe.canReceive();
                case "supportsExtraction": return fe.canExtract();
                case "toString": return "Alloy FE->E view of " + fe;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == a[0];
                default: throw new UnsupportedOperationException(m.toString());
            }
        }
    }

    /** Runs {@code action} once the transaction and all its parents have committed (nothing if any of them aborts). */
    static void onCommit(TransactionContext tx, Runnable action) {
        tx.addCloseCallback((t, result) -> {
            if (!result.wasCommitted()) return;
            if (t.nestingDepth() == 0) action.run();
            else onCommit(t.getOpenTransaction(t.nestingDepth() - 1), action);
        });
    }

    static int clamp(long v) { return (int) Math.max(0, Math.min(Integer.MAX_VALUE, v)); }

    // ------------------------------------------------------------------------------------------ Fabric -> Forge

    /** Attached to every block entity: answers ENERGY when Team Reborn Energy finds a storage for this block. */
    static final class FabricAsForge implements ICapabilityProvider {
        final BlockEntity be;
        FabricAsForge(BlockEntity be) { this.be = be; }

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
            if (cap != ForgeCapabilities.ENERGY || BUSY.get() || be.getLevel() == null) return LazyOptional.empty();
            BUSY.set(true);
            Object tr;
            try {
                tr = sided.find(be.getLevel(), be.getBlockPos(), be.getBlockState(), be, side);
            } catch (Throwable t) {
                tr = null;
            } finally {
                BUSY.set(false);
            }
            if (tr == null || Proxy.isProxyClass(tr.getClass()) && Proxy.getInvocationHandler(tr) instanceof TrView) return LazyOptional.empty();
            if (fabricToForge++ == 0) Bridge.LOG.info("Alloy energy: first Team Reborn Energy block seen as Forge FE: {} at {}", be.getType(), be.getBlockPos());
            Object storage = tr;
            return LazyOptional.of(() -> new FeView(storage)).cast();
        }
    }

    /** FE IEnergyStorage over a TR EnergyStorage. */
    static final class FeView implements IEnergyStorage {
        final Object tr;
        FeView(Object tr) { this.tr = tr; }

        private long call(Method m, long amount, boolean simulate) {
            if (Transaction.isOpen()) return 0;   // FE caller inside a Fabric transfer on this thread: stay out of it
            try (Transaction t = Transaction.openOuter()) {
                long r = (Long) m.invoke(tr, amount, t);
                if (!simulate) t.commit();
                return r;
            } catch (ReflectiveOperationException e) {
                return 0;
            }
        }

        @Override public int receiveEnergy(int fe, boolean simulate) { return clamp(call(mInsert, fe / fePerE, simulate) * fePerE); }
        @Override public int extractEnergy(int fe, boolean simulate) { return clamp(call(mExtract, fe / fePerE, simulate) * fePerE); }
        @Override public int getEnergyStored() { return clamp(get(mAmount) * fePerE); }
        @Override public int getMaxEnergyStored() { return clamp(get(mCapacity) * fePerE); }
        @Override public boolean canExtract() { return bool(mCanExtract); }
        @Override public boolean canReceive() { return bool(mCanInsert); }

        private long get(Method m) {
            try { return (Long) m.invoke(tr); } catch (ReflectiveOperationException e) { return 0; }
        }

        private boolean bool(Method m) {
            try { return (Boolean) m.invoke(tr); } catch (ReflectiveOperationException e) { return false; }
        }
    }
}
