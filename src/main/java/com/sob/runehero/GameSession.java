package com.sob.runehero;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.inject.Singleton;

/**
 * Owns the single source of truth for a playthrough: a {@link System#nanoTime()} clock and the
 * generated {@link ChartNote} list. Both the falling notes and the hit-judgment read from this
 * one clock, so there is nothing to sync to the (un-queryable) in-game audio position.
 *
 * Judging is fret + strum, keypress-only: {@link #setFret} tracks which frets are held;
 * {@link #onStrum} scores the nearest note in a held lane within the timing window. We never
 * read the active inventory tab.
 *
 * Key callbacks (setFret/onStrum) may arrive on a different thread from the render-thread sweep,
 * so mutating methods are synchronized and cross-thread fields are volatile.
 */
@Singleton
final class GameSession
{
    enum Judgment
    {
        PERFECT, GOOD, MISS
    }

    private volatile boolean running;
    private volatile boolean armed;          // waiting for the track to (re)start
    private volatile boolean armSawMusicOff;  // seen music go quiet since arming
    private volatile List<ChartNote> chart = new ArrayList<>();
    private volatile Difficulty difficulty = Difficulty.MEDIUM;
    private volatile long startNanos; // nanoTime at which chart time == 0

    private final Set<Lane> heldFrets = EnumSet.noneOf(Lane.class);

    private int score;
    private int combo;
    private int maxCombo;
    private int perfects;
    private int goods;
    private int misses;
    private int sweepCursor;

    private volatile Judgment lastJudgment;
    private volatile Lane lastJudgmentLane;
    private volatile long lastJudgmentNanos;

    // Per-lane hit/miss flash (for the overlay's feedback animation), indexed by Lane.ordinal().
    private final long[] laneFlashNanos = new long[Lane.COUNT];
    private final Judgment[] laneFlashJudge = new Judgment[Lane.COUNT];

    private volatile long endedNanos = Long.MIN_VALUE; // nanoTime a chart ended naturally (end card)

    /**
     * Begin a playthrough. {@code startNanos} is the {@link System#nanoTime()} value at which
     * chart time equals 0 — the caller anchors it (e.g. to the moment the live track restarted),
     * which is what keeps the falling notes lined up with the audio.
     */
    synchronized void start(List<ChartNote> newChart, Difficulty diff, long startNanos)
    {
        this.chart = newChart != null ? newChart : new ArrayList<>();
        this.difficulty = diff;
        this.startNanos = startNanos;
        heldFrets.clear();
        score = combo = maxCombo = perfects = goods = misses = 0;
        sweepCursor = 0;
        lastJudgment = null;
        lastJudgmentLane = null;
        Arrays.fill(laneFlashNanos, Long.MIN_VALUE);
        endedNanos = Long.MIN_VALUE;
        armed = false;
        running = !this.chart.isEmpty();
    }

    synchronized void stop()
    {
        running = false;
        armed = false;
        endedNanos = Long.MIN_VALUE; // manual stop — no end card
        heldFrets.clear();
    }

    /** Enter the "waiting for the track to restart" state (the player toggles Music off→on). */
    synchronized void arm()
    {
        running = false;
        armed = true;
        armSawMusicOff = false;
    }

    void disarm()
    {
        armed = false;
    }

    boolean isArmed()
    {
        return armed;
    }

    void setArmSawMusicOff(boolean v)
    {
        armSawMusicOff = v;
    }

    boolean armSawMusicOff()
    {
        return armSawMusicOff;
    }

    boolean isRunning()
    {
        return running;
    }

    Difficulty difficulty()
    {
        return difficulty;
    }

    List<ChartNote> chart()
    {
        return chart;
    }

    /** Milliseconds since chart time 0; negative during the count-in. */
    long elapsedMs(long nowNanos)
    {
        return (nowNanos - startNanos) / 1_000_000L;
    }

    synchronized void setFret(Lane lane, boolean down)
    {
        if (down)
        {
            heldFrets.add(lane);
        }
        else
        {
            heldFrets.remove(lane);
        }
    }

    boolean isFretHeld(Lane lane)
    {
        // EnumSet.contains is an O(1) bit test — safe to read without locking.
        return heldFrets.contains(lane);
    }

    /**
     * The player strummed. Score the nearest un-consumed note within the good window whose lane
     * fret is currently held. A strum with the wrong (or no) fret held, or with no note in range,
     * is an overstrum: no points and the combo breaks.
     */
    synchronized void onStrum(long nowNanos)
    {
        if (!running)
        {
            return;
        }
        long now = elapsedMs(nowNanos);
        int good = difficulty.goodWindowMs;

        ChartNote target = null;
        long bestDelta = Long.MAX_VALUE;
        for (ChartNote c : chart)
        {
            if (c.consumed || !heldFrets.contains(c.lane))
            {
                continue;
            }
            long delta = Math.abs(c.hitTimeMs - now);
            if (delta <= good && delta < bestDelta)
            {
                bestDelta = delta;
                target = c;
            }
        }

        if (target == null)
        {
            combo = 0;
            recordJudgment(Judgment.MISS, null, nowNanos);
            return;
        }

        target.consumed = true;
        award(bestDelta, target.lane, nowNanos);
    }

    /**
     * No-strum mode: pressing a fret IS the hit on that lane. Scores the nearest un-consumed
     * note in {@code lane} within the window; a press with no note in range breaks the combo.
     */
    synchronized void onLaneHit(Lane lane, long nowNanos)
    {
        if (!running)
        {
            return;
        }
        long now = elapsedMs(nowNanos);
        int good = difficulty.goodWindowMs;

        ChartNote target = null;
        long bestDelta = Long.MAX_VALUE;
        for (ChartNote c : chart)
        {
            if (c.consumed || c.lane != lane)
            {
                continue;
            }
            long delta = Math.abs(c.hitTimeMs - now);
            if (delta <= good && delta < bestDelta)
            {
                bestDelta = delta;
                target = c;
            }
        }

        if (target == null)
        {
            combo = 0;
            recordJudgment(Judgment.MISS, lane, nowNanos);
            return;
        }

        target.consumed = true;
        award(bestDelta, target.lane, nowNanos);
    }

    /** Streak multiplier, Guitar-Hero style: 1x, then 2x/3x/4x at combo 10/20/30+. */
    int multiplier()
    {
        return 1 + Math.min(combo / 10, 3);
    }

    private void award(long delta, Lane lane, long nowNanos)
    {
        int mult = multiplier();
        if (delta <= difficulty.perfectWindowMs)
        {
            perfects++;
            score += 100 * mult;
            combo++;
            recordJudgment(Judgment.PERFECT, lane, nowNanos);
        }
        else
        {
            goods++;
            score += 50 * mult;
            combo++;
            recordJudgment(Judgment.GOOD, lane, nowNanos);
        }
        if (combo > maxCombo)
        {
            maxCombo = combo;
        }
    }

    /** Per-frame: mark notes whose window has fully passed as misses, and end the chart. */
    synchronized void sweepMisses(long nowNanos)
    {
        if (!running)
        {
            return;
        }
        long now = elapsedMs(nowNanos);
        int good = difficulty.goodWindowMs;
        while (sweepCursor < chart.size())
        {
            ChartNote c = chart.get(sweepCursor);
            if (c.hitTimeMs + good >= now)
            {
                break; // still hittable
            }
            if (!c.consumed)
            {
                c.consumed = true;
                misses++;
                combo = 0;
                recordJudgment(Judgment.MISS, c.lane, nowNanos);
            }
            sweepCursor++;
        }

        if (sweepCursor >= chart.size() && !chart.isEmpty())
        {
            long lastHit = chart.get(chart.size() - 1).hitTimeMs;
            if (now > lastHit + good + 1500)
            {
                running = false;
                endedNanos = nowNanos;
            }
        }
    }

    private void recordJudgment(Judgment j, Lane lane, long nowNanos)
    {
        lastJudgment = j;
        lastJudgmentLane = lane;
        lastJudgmentNanos = nowNanos;
        if (lane != null)
        {
            laneFlashNanos[lane.ordinal()] = nowNanos;
            laneFlashJudge[lane.ordinal()] = j;
        }
    }

    /** nanoTime of the last hit/miss in this lane (for the overlay flash), or Long.MIN_VALUE. */
    long flashNanos(Lane lane)
    {
        return laneFlashNanos[lane.ordinal()];
    }

    Judgment flashJudgment(Lane lane)
    {
        return laneFlashJudge[lane.ordinal()];
    }

    int score()
    {
        return score;
    }

    int combo()
    {
        return combo;
    }

    int maxCombo()
    {
        return maxCombo;
    }

    int totalNotes()
    {
        return chart.size();
    }

    int hits()
    {
        return perfects + goods;
    }

    int perfects()
    {
        return perfects;
    }

    int misses()
    {
        return misses;
    }

    /** nanoTime the chart ended naturally (for the end card), or Long.MIN_VALUE. */
    long endedNanos()
    {
        return endedNanos;
    }

    Judgment lastJudgment()
    {
        return lastJudgment;
    }

    Lane lastJudgmentLane()
    {
        return lastJudgmentLane;
    }

    long lastJudgmentNanos()
    {
        return lastJudgmentNanos;
    }

    /** Notes whose fall window overlaps [now, now+leadTime]; a fresh list, safe to iterate. */
    List<ChartNote> visibleNotes(long nowNanos)
    {
        if (!running)
        {
            return Collections.emptyList();
        }
        long now = elapsedMs(nowNanos);
        long lead = difficulty.leadTimeMs;
        int good = difficulty.goodWindowMs;
        List<ChartNote> vis = new ArrayList<>();
        for (ChartNote c : chart)
        {
            if (c.consumed)
            {
                continue;
            }
            // Visible from when it spawns at the top until its window closes.
            if (c.hitTimeMs - lead <= now && c.hitTimeMs + good >= now)
            {
                vis.add(c);
            }
        }
        return vis;
    }
}
