package alloy;

import java.nio.file.Path;

/**
 * Dry boot is a developer check mode (-Dalloy.dryBoot=true) that is not part of this source release.
 * This stand-in keeps Bridge compiling; with it the flag does nothing.
 */
public final class DryBoot {
    public static final boolean ON = false;

    private DryBoot() {}

    public static void run(Path gameDir) {}
}
