package kr.co.voxelient.render;

import java.util.function.LongSupplier;

/**
 * Small per-frame budget for main-thread render work.
 */
public final class RenderFrameBudget {
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final int maxItems;
    private final long maxNanos;
    private final LongSupplier clock;
    private final long startNanos;
    private int usedItems = 0;

    private RenderFrameBudget(int maxItems, long maxMillis, LongSupplier clock) {
        this.maxItems = Math.max(0, maxItems);
        this.maxNanos = Math.max(0L, maxMillis) * NANOS_PER_MILLI;
        this.clock = clock;
        this.startNanos = clock.getAsLong();
    }

    public static RenderFrameBudget of(int maxItems, long maxMillis) {
        return new RenderFrameBudget(maxItems, maxMillis, System::nanoTime);
    }

    static RenderFrameBudget usingClock(int maxItems, long maxMillis, LongSupplier clock) {
        return new RenderFrameBudget(maxItems, maxMillis, clock);
    }

    public boolean tryUse() {
        if (usedItems >= maxItems) {
            return false;
        }
        if (usedItems > 0 && elapsedNanos() >= maxNanos) {
            return false;
        }

        usedItems++;
        return true;
    }

    public int usedItems() {
        return usedItems;
    }

    private long elapsedNanos() {
        return Math.max(0L, clock.getAsLong() - startNanos);
    }
}
