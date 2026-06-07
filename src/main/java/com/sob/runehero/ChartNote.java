package com.sob.runehero;

/**
 * One playable note in a generated chart: when to hit it, which fret/lane, and the original
 * MIDI pitch (kept for note coloring). {@code consumed} is flipped once the note has been
 * scored (hit) or swept as a miss, so the overlay and judge never double-count it.
 */
final class ChartNote
{
    final long hitTimeMs;
    final Lane lane;
    final int note;
    boolean consumed;

    ChartNote(long hitTimeMs, Lane lane, int note)
    {
        this.hitTimeMs = hitTimeMs;
        this.lane = lane;
        this.note = note;
    }
}
