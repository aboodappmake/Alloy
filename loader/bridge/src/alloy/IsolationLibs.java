package alloy;

import java.util.List;

/**
 * Common libraries that mods of both loaders ship unrelocated. Isolation never lets one mod own their packages:
 * Cobblemon (Fabric, 1.20.1) shades the Kotlin stdlib, so kotlin.* counted as Fabric-owned and every Kotlin reference of
 * the Forge mods Inventory Profiles Next and libIPN (whose Kotlin comes from Kotlin for Forge, a language provider that is
 * not indexed) was renamed to alloy/hidden/forge/kotlin/...: NoClassDefFoundError in IPN's mixin plugin and libIPN's mod
 * constructor failed (reg05 dry boot). Their classes stay shared, as without isolation.
 */
public final class IsolationLibs {
    private IsolationLibs() { }

    static final List<String> PREFIXES = List.of("kotlin/", "kotlinx/", "org/jetbrains/annotations/", "org/intellij/lang/annotations/");

    /** internal class name (a/b/C) of a common library class */
    public static boolean common(String internal) {
        for (String p : PREFIXES) if (internal.startsWith(p)) return true;
        return false;
    }
}
