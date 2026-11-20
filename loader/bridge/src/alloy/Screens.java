package alloy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The current screen, the same way on every supported Minecraft version (CLIENT only).
 * Up to 26.1: {@code Minecraft.screen} and {@code Minecraft.setScreen(Screen)}.
 * 26.2: {@code Minecraft.gui.screen()} and {@code Minecraft.gui.setScreen(Screen)}.
 * Names are Mojang names at runtime (26.x is not obfuscated), so plain lookups work.
 */
public final class Screens {
    private static final MethodHandle GET, SET;   // (Minecraft)Screen, (Minecraft, Screen)void

    static {
        MethodHandles.Lookup l = MethodHandles.publicLookup();
        MethodHandle get, set;
        try {
            get = l.findGetter(Minecraft.class, "screen", Screen.class);
            set = l.findVirtual(Minecraft.class, "setScreen", MethodType.methodType(void.class, Screen.class));
        } catch (ReflectiveOperationException old) {
            try {
                Class<?> gui = Class.forName("net.minecraft.client.gui.Gui", false, Minecraft.class.getClassLoader());
                MethodHandle field = l.findGetter(Minecraft.class, "gui", gui);
                get = MethodHandles.filterReturnValue(field, l.findVirtual(gui, "screen", MethodType.methodType(Screen.class)));
                set = MethodHandles.filterArguments(l.findVirtual(gui, "setScreen", MethodType.methodType(void.class, Screen.class)), 0, field);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        GET = get.asType(MethodType.methodType(Screen.class, Minecraft.class));
        SET = set.asType(MethodType.methodType(void.class, Minecraft.class, Screen.class));
    }

    private Screens() { }

    public static Screen current() {
        try { return (Screen) GET.invokeExact(Minecraft.getInstance()); } catch (Throwable t) { throw new IllegalStateException(t); }
    }

    public static void set(Screen s) {
        try { SET.invokeExact(Minecraft.getInstance(), s); } catch (Throwable t) { throw new IllegalStateException(t); }
    }
}
