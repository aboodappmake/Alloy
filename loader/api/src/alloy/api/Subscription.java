package alloy.api;

/** Handle for a registered listener. {@link #close()} removes it; closing twice is harmless. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    /** A subscription that does nothing (used when Alloy is not present). */
    Subscription NONE = () -> { };

    @Override
    void close();
}
