package alloy.api.impl;

import alloy.api.Side;

/** Who a class belongs to: the side and mod id (mod may be null when only the side is known). */
record Owner(Side side, String mod) {
    static final Owner NONE = new Owner(Side.UNKNOWN, null);
    static final Owner ALLOY = new Owner(Side.UNKNOWN, "alloyloader");
}
