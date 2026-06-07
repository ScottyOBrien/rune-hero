package com.sob.runehero;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns an extracted MIDI timeline into a playable single-lane chart.
 *
 * Because the player can only occupy one tab at a time, the chart MUST be monophonic — a
 * single melodic line, never a chord. The pipeline is:
 *   1. select source notes — a chosen SET of channels (Smart auto-pick or Manual A/B), or all
 *      non-drum channels merged if the set is empty,
 *   2. collapse to one note per instant (skyline: keep the highest pitch) + thin to the
 *      difficulty's note density,
 *   3. band each surviving note into one of the lanes by its deviation from the LOCAL pitch
 *      center (a rolling median), so melodic movement drives lane movement.
 *
 * Many tracks hand the melody between instruments, so charting one channel can go cold; charting
 * the right 1-2 channels (see {@link #smartChannels}) keeps the lead intact.
 *
 * Pure and client-free, so it is unit-testable in isolation.
 */
final class MapGenerator
{
    /** Window over which the local pitch center is taken, so each phrase recenters on the lanes. */
    private static final long BAND_WINDOW_MS = 6000;
    /**
     * Smart only adds a 3rd channel when the first two leave a CONTIGUOUS hole at least this long
     * — a stretch where another instrument has melody but our two lanes are silent. Brief pauses
     * are normal and don't trigger a third voice.
     */
    private static final long BIG_HOLE_MS = 4000;
    /** Semitones of deviation from the local center per lane step. Smaller = more lane movement. */
    private static final double SEMIS_PER_LANE = 3.0;

    private MapGenerator() {}

    /**
     * @param timeline   time-sorted note events from {@link MidiTrackResolver#flatten(byte[])}
     * @param difficulty controls density (thinning), lane set and timing
     * @param channels   the channels to merge; null/empty = all non-drum channels
     * @return monophonic chart, strictly increasing in hit time with ≥ {@code minGap} spacing
     */
    static List<ChartNote> generate(List<NoteEvent> timeline, Difficulty difficulty, int[] channels)
    {
        List<ChartNote> out = new ArrayList<>();
        if (timeline == null || timeline.isEmpty())
        {
            return out;
        }

        List<NoteEvent> source = (channels == null || channels.length == 0)
            ? tonalNotes(timeline)
            : onChannels(timeline, channels);
        if (source.isEmpty())
        {
            source = tonalNotes(timeline); // chosen channels silent — fall back to all tonal
        }
        if (source.isEmpty())
        {
            source = new ArrayList<>(timeline); // percussion-only track — fall back to all notes
        }
        if (source.isEmpty())
        {
            return out;
        }

        // Step 1: skyline collapse + density thinning -> kept notes as [timestampMs, pitch].
        long minGap = difficulty.minGapMs();
        long lastKept = Long.MIN_VALUE;
        List<long[]> kept = new ArrayList<>();
        int i = 0;
        int n = source.size();
        while (i < n)
        {
            long ts = source.get(i).timestampMs;
            NoteEvent best = source.get(i);
            int j = i + 1;
            while (j < n && source.get(j).timestampMs == ts)
            {
                NoteEvent cand = source.get(j);
                if (cand.note > best.note || (cand.note == best.note && cand.velocity > best.velocity))
                {
                    best = cand;
                }
                j++;
            }
            i = j;

            if (lastKept != Long.MIN_VALUE && ts - lastKept < minGap)
            {
                continue;
            }
            lastKept = ts;
            kept.add(new long[]{ts, best.note});
        }
        if (kept.isEmpty())
        {
            return out;
        }

        // Step 2: lane = local center lane ± deviation, banded into this difficulty's lane set.
        Lane[] lanes = difficulty.lanes();
        int laneN = lanes.length;
        int middle = (laneN - 1) / 2;
        int m = kept.size();
        for (int k = 0; k < m; k++)
        {
            long ts = kept.get(k)[0];
            int pitch = (int) kept.get(k)[1];
            double center = localMedianPitch(kept, k, ts);
            int lane = middle + (int) Math.round((pitch - center) / SEMIS_PER_LANE);
            if (lane < 0)
            {
                lane = 0;
            }
            else if (lane >= laneN)
            {
                lane = laneN - 1;
            }
            out.add(new ChartNote(ts, lanes[lane], pitch));
        }
        return out;
    }

    /**
     * Auto-pick the 1-2 channels that best carry the melody. Each candidate channel is scored on
     * register (melody sits high), pitch variety (a moving line, not a drone), activity, and —
     * weighted heavily — how MONOPHONIC it is (one note per onset). The melody is a single line;
     * pads and string ensembles play chords (many notes per onset), so the mono term steers the
     * pick to the real lead rather than a busy chordal background. Primary = top score; secondary
     * = the next channel that's still melodic (not a deep bass, not far weaker), so tracks whose
     * tune is split across two instruments get both. Empty array = no clear lead (use all).
     */
    static int[] smartChannels(List<NoteEvent> timeline)
    {
        if (timeline == null || timeline.isEmpty())
        {
            return new int[0];
        }
        int[] count = new int[16];          // raw note-ons (chords inflate this)
        int[] onsetTimes = new int[16];     // distinct onset timestamps (chord-agnostic activity)
        long[] sum = new long[16];
        boolean[][] seen = new boolean[16][128];
        int[] distinct = new int[16];
        long[] lastTs = new long[16];
        Arrays.fill(lastTs, Long.MIN_VALUE);
        for (NoteEvent e : timeline)
        {
            int c = e.channel;
            if (c < 0 || c >= 16 || c == 9)
            {
                continue; // skip drums
            }
            count[c]++;
            sum[c] += e.note;
            int p = e.note & 0x7F;
            if (!seen[c][p])
            {
                seen[c][p] = true;
                distinct[c]++;
            }
            if (e.timestampMs != lastTs[c])
            {
                onsetTimes[c]++;
                lastTs[c] = e.timestampMs;
            }
        }
        int busiest = 0;
        for (int c = 0; c < 16; c++)
        {
            busiest = Math.max(busiest, onsetTimes[c]);
        }
        if (busiest == 0)
        {
            return new int[0];
        }
        int minActivity = Math.max(8, (int) (0.12 * busiest));
        List<Integer> cand = new ArrayList<>();
        for (int c = 0; c < 16; c++)
        {
            if (onsetTimes[c] >= minActivity)
            {
                cand.add(c);
            }
        }
        if (cand.isEmpty())
        {
            return new int[0];
        }
        if (cand.size() == 1)
        {
            return new int[]{cand.get(0)};
        }

        double minAvg = 1e9, maxAvg = -1e9, minDis = 1e9, maxDis = -1e9, minOn = 1e9, maxOn = -1e9;
        for (int c : cand)
        {
            double a = (double) sum[c] / count[c];
            minAvg = Math.min(minAvg, a);
            maxAvg = Math.max(maxAvg, a);
            minDis = Math.min(minDis, distinct[c]);
            maxDis = Math.max(maxDis, distinct[c]);
            minOn = Math.min(minOn, onsetTimes[c]);
            maxOn = Math.max(maxOn, onsetTimes[c]);
        }

        double[] score = new double[16];
        double[] avg = new double[16];
        for (int c : cand)
        {
            avg[c] = (double) sum[c] / count[c];
            double mono = (double) onsetTimes[c] / count[c]; // 1.0 = pure single line; lower = chordal
            score[c] = norm(avg[c], minAvg, maxAvg)             // higher register
                + 0.7 * norm(distinct[c], minDis, maxDis)        // pitch variety
                + 0.5 * norm(onsetTimes[c], minOn, maxOn)        // activity
                + 1.5 * mono;                                    // single-line, not chordal
        }

        cand.sort((x, y) -> Double.compare(score[y], score[x]));
        int primary = cand.get(0);

        int secondary = -1;
        for (int i = 1; i < cand.size(); i++)
        {
            int c = cand.get(i);
            if (avg[c] >= avg[primary] - 18 && score[c] >= 0.5 * score[primary])
            {
                secondary = c;
                break;
            }
        }
        if (secondary < 0)
        {
            return new int[]{primary};
        }

        // Add a 3rd voice ONLY if the first two leave a BIG contiguous hole — a long stretch where
        // some other tonal channel has melody but our two are silent. Brief pauses (and genuine
        // song-wide silence, where nothing plays) don't count.
        long bucketMs = 700;
        Set<Long> covered = new HashSet<>();
        addBuckets(covered, timeline, primary, bucketMs);
        addBuckets(covered, timeline, secondary, bucketMs);
        if (longestHoleMs(timeline, new int[]{primary, secondary}) < BIG_HOLE_MS)
        {
            return new int[]{primary, secondary}; // only brief pauses — two voices is enough
        }

        // There's a big hole: fill it with whatever tonal channel covers the most of it. A gap-filler
        // typically plays ONLY during the gap, so it has few total notes and would fail the global
        // activity filter — search ALL tonal channels here, not just the busy candidate list.
        int tertiary = -1;
        int bestNew = 0;
        for (int c = 0; c < 16; c++)
        {
            if (c == 9 || c == primary || c == secondary || count[c] < 8)
            {
                continue;
            }
            if ((double) sum[c] / count[c] < avg[primary] - 24)
            {
                continue; // a deep bass relative to the lead — skip
            }
            int newB = newBuckets(covered, timeline, c, bucketMs);
            if (newB > bestNew)
            {
                bestNew = newB;
                tertiary = c;
            }
        }
        if (tertiary >= 0 && bestNew * bucketMs >= BIG_HOLE_MS / 2)
        {
            return new int[]{primary, secondary, tertiary};
        }
        return new int[]{primary, secondary};
    }

    /** Longest gap (ms) between consecutive notes in a generated chart — the actual on-screen pause. */
    static long longestNoteGapMs(List<ChartNote> chart)
    {
        long longest = 0;
        for (int i = 1; i < chart.size(); i++)
        {
            long gap = chart.get(i).hitTimeMs - chart.get(i - 1).hitTimeMs;
            if (gap > longest)
            {
                longest = gap;
            }
        }
        return longest;
    }

    /** Longest contiguous stretch (ms) where some tonal channel has melody but none of {@code channels} play. */
    static long longestHoleMs(List<NoteEvent> timeline, int[] channels)
    {
        if (timeline == null || timeline.isEmpty())
        {
            return 0;
        }
        long bucketMs = 700;
        Set<Long> covered = new HashSet<>();
        for (int c : channels)
        {
            addBuckets(covered, timeline, c, bucketMs);
        }
        Set<Long> songBuckets = new HashSet<>();
        long minB = Long.MAX_VALUE;
        long maxB = Long.MIN_VALUE;
        for (NoteEvent e : timeline)
        {
            if (e.channel == 9)
            {
                continue;
            }
            long b = e.timestampMs / bucketMs;
            songBuckets.add(b);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
        }
        if (songBuckets.isEmpty())
        {
            return 0;
        }
        long longest = 0;
        long run = 0;
        for (long b = minB; b <= maxB; b++)
        {
            if (songBuckets.contains(b) && !covered.contains(b))
            {
                run++;
                longest = Math.max(longest, run);
            }
            else
            {
                run = 0;
            }
        }
        return longest * bucketMs;
    }

    private static void addBuckets(Set<Long> set, List<NoteEvent> timeline, int channel, long bucketMs)
    {
        for (NoteEvent e : timeline)
        {
            if (e.channel == channel)
            {
                set.add(e.timestampMs / bucketMs);
            }
        }
    }

    private static int newBuckets(Set<Long> covered, List<NoteEvent> timeline, int channel, long bucketMs)
    {
        Set<Long> b = new HashSet<>();
        for (NoteEvent e : timeline)
        {
            if (e.channel == channel)
            {
                long k = e.timestampMs / bucketMs;
                if (!covered.contains(k))
                {
                    b.add(k);
                }
            }
        }
        return b.size();
    }

    private static double norm(double v, double lo, double hi)
    {
        return hi > lo ? (v - lo) / (hi - lo) : 0.0;
    }

    /** Median pitch of the kept notes within ±{@link #BAND_WINDOW_MS} of {@code kept[k]}. */
    private static double localMedianPitch(List<long[]> kept, int k, long ts)
    {
        int lo = k;
        int hi = k;
        while (lo - 1 >= 0 && ts - kept.get(lo - 1)[0] <= BAND_WINDOW_MS)
        {
            lo--;
        }
        while (hi + 1 < kept.size() && kept.get(hi + 1)[0] - ts <= BAND_WINDOW_MS)
        {
            hi++;
        }
        int cnt = hi - lo + 1;
        int[] pitches = new int[cnt];
        for (int x = 0; x < cnt; x++)
        {
            pitches[x] = (int) kept.get(lo + x)[1];
        }
        Arrays.sort(pitches);
        int mid = cnt / 2;
        return (cnt % 2 == 0) ? (pitches[mid - 1] + pitches[mid]) / 2.0 : pitches[mid];
    }

    private static List<NoteEvent> onChannels(List<NoteEvent> timeline, int[] channels)
    {
        boolean[] want = new boolean[16];
        for (int c : channels)
        {
            if (c >= 0 && c < 16)
            {
                want[c] = true;
            }
        }
        List<NoteEvent> r = new ArrayList<>();
        for (NoteEvent e : timeline)
        {
            if (e.channel >= 0 && e.channel < 16 && want[e.channel])
            {
                r.add(e);
            }
        }
        return r;
    }

    /** All note-ons except channel 9 (GM percussion), preserving time order. */
    private static List<NoteEvent> tonalNotes(List<NoteEvent> timeline)
    {
        List<NoteEvent> r = new ArrayList<>();
        for (NoteEvent e : timeline)
        {
            if (e.channel != 9)
            {
                r.add(e);
            }
        }
        return r;
    }
}
