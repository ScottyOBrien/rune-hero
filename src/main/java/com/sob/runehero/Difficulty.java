package com.sob.runehero;

/**
 * A difficulty mode bundles the lane set, density (how aggressively the chart is thinned), fall
 * speed (how long a note is visible while falling) and timing windows.
 *
 * The primary difficulty axis is LANE COUNT, not just density: lower difficulties keep MORE of
 * the song (denser) but on FEWER lanes, so they still feel like playing the song while being
 * easier. Throwing away most of the song (low density on all 5 lanes) just feels sparse and bad.
 *
 * MUST stay `public`: this enum is returned by {@link RuneHeroConfig#difficulty()}, and RuneLite's
 * config proxy (in the com.sun.proxy package) throws IllegalAccessError at runtime on a
 * package-private return type. Compilation and unit tests do NOT catch this — do not remove `public`.
 */
public enum Difficulty
{
    // 3 adjacent lanes (Inventory/Equipment/Prayer) — a clean contiguous strip, three fingers.
    EASY(new Lane[]{Lane.INVENTORY, Lane.EQUIPMENT, Lane.PRAYER}, 3.0, 2400, 80, 160),
    MEDIUM(new Lane[]{Lane.INVENTORY, Lane.EQUIPMENT, Lane.PRAYER}, 7.0, 1900, 60, 120),
    // All 5 lanes.
    HARD(Lane.ORDER, 8.0, 1500, 45, 90),
    EXPERT(Lane.ORDER, 99.0, 1200, 32, 65);

    private final Lane[] lanes;
    /** Maximum notes per second kept in the chart (99 ≈ no thinning = the full song). */
    final double targetNps;
    /** How long (ms) a note falls from spawn to the hit line. Smaller = faster. */
    final long leadTimeMs;
    /** |delta| ≤ this counts as Perfect. */
    final int perfectWindowMs;
    /** |delta| ≤ this counts as Good; beyond it the strum/tap misses. */
    final int goodWindowMs;

    Difficulty(Lane[] lanes, double targetNps, long leadTimeMs, int perfectWindowMs, int goodWindowMs)
    {
        this.lanes = lanes;
        this.targetNps = targetNps;
        this.leadTimeMs = leadTimeMs;
        this.perfectWindowMs = perfectWindowMs;
        this.goodWindowMs = goodWindowMs;
    }

    /** Lanes this mode uses, ordered left-to-right (ascending screen position). */
    Lane[] lanes()
    {
        return lanes;
    }

    /** Minimum spacing between kept notes, derived from {@link #targetNps}. */
    long minGapMs()
    {
        return Math.round(1000.0 / targetNps);
    }
}
