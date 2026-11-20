package alloy.api.impl;

import alloy.api.CrossEvent;
import alloy.api.Message;
import alloy.api.Subscription;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Listener lists for cross-side events and channels. Only JDK and API types are passed around, so a Fabric mod and
 * a Forge mod never see each other's classes. Dispatch is synchronous, in registration order; a throwing listener
 * is logged and skipped.
 */
final class Hub {
    private static final class Sub<T> {
        final Consumer<T> listener;
        final Owner owner;
        Sub(Consumer<T> listener, Owner owner) { this.listener = listener; this.owner = owner; }
    }

    private final Map<String, CopyOnWriteArrayList<Sub<CrossEvent>>> events = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Sub<Message>>> channels = new ConcurrentHashMap<>();

    Subscription listenEvent(String name, Consumer<CrossEvent> l, Owner owner) {
        return add(events, name, new Sub<>(l, owner));
    }

    Subscription listenChannel(String name, Consumer<Message> l, Owner owner) {
        return add(channels, name, new Sub<>(l, owner));
    }

    private static <T> Subscription add(Map<String, CopyOnWriteArrayList<Sub<T>>> map, String name, Sub<T> s) {
        CopyOnWriteArrayList<Sub<T>> list = map.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>());
        list.add(s);
        return () -> list.remove(s);
    }

    boolean hasEventListeners(String name) {
        var l = events.get(name);
        return l != null && !l.isEmpty();
    }

    int postEvent(String name, Map<String, Object> data, Owner from) {
        var list = events.get(name);
        if (list == null || list.isEmpty()) return 0;
        CrossEvent ev = new CrossEvent(name, data, from.side(), from.mod());
        int n = 0;
        for (Sub<CrossEvent> s : list) {
            n++;
            call(s, ev, name);
        }
        return n;
    }

    /** local message: every listener of the channel except the sending mod's own */
    int sendLocal(Message m, Owner from) {
        var list = channels.get(m.channel());
        if (list == null || list.isEmpty()) return 0;
        int n = 0;
        for (Sub<Message> s : list) {
            if (from.mod() != null && Objects.equals(from.mod(), s.owner.mod())) continue;
            n++;
            call(s, m, m.channel());
        }
        return n;
    }

    /** network message: every listener of the channel on this machine */
    int deliverRemote(Message m) {
        var list = channels.get(m.channel());
        if (list == null || list.isEmpty()) return 0;
        int n = 0;
        for (Sub<Message> s : list) {
            n++;
            call(s, m, m.channel());
        }
        return n;
    }

    private static <T> void call(Sub<T> s, T value, String what) {
        try {
            s.listener.accept(value);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            ApiBackend.LOG.error("Alloy API: a listener of {} in mod {} ({}) threw; other listeners still run", what,
                    s.owner.mod(), s.owner.side(), t);
        }
    }
}
