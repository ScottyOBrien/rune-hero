package com.sob.runehero;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MapGeneratorTest
{
    private static final int[] ALL = new int[0];      // empty = merge all non-drum channels
    private static final int[] CH0 = new int[]{0};

    /** The chart must be a single playable line: strictly increasing, ≥ minGap apart, lanes valid. */
    @Test
    public void monophonicAndInRange()
    {
        List<NoteEvent> timeline = new ArrayList<>();
        // A 3-note chord at t=0 (must collapse to one note).
        timeline.add(new NoteEvent(0, 0, 60, 100));
        timeline.add(new NoteEvent(0, 0, 64, 80));
        timeline.add(new NoteEvent(0, 0, 67, 90));
        for (int t = 50; t <= 2000; t += 50)
        {
            timeline.add(new NoteEvent(t, 0, 60 + (t / 50) % 12, 70));
        }
        timeline.add(new NoteEvent(100, 1, 50, 100));
        timeline.add(new NoteEvent(900, 1, 52, 100));
        Collections.sort(timeline);

        for (Difficulty d : Difficulty.values())
        {
            List<ChartNote> chart = MapGenerator.generate(timeline, d, ALL);
            assertFalse(d + ": chart should not be empty", chart.isEmpty());

            long minGap = d.minGapMs();
            long prev = Long.MIN_VALUE;
            for (ChartNote c : chart)
            {
                if (prev != Long.MIN_VALUE)
                {
                    assertTrue(d + ": notes too close together", c.hitTimeMs - prev >= minGap);
                }
                prev = c.hitTimeMs;
                int idx = c.lane.ordinal();
                assertTrue("lane index in range", idx >= 0 && idx < Lane.COUNT);
            }
        }
    }

    /** Harder difficulties thin less, so they keep at least as many notes. */
    @Test
    public void harderKeepsMoreNotes()
    {
        List<NoteEvent> timeline = new ArrayList<>();
        for (int t = 0; t <= 4000; t += 20) // ~50 notes/sec raw
        {
            timeline.add(new NoteEvent(t, 0, 60 + (t / 20) % 13, 80));
        }
        int easy = MapGenerator.generate(timeline, Difficulty.EASY, CH0).size();
        int medium = MapGenerator.generate(timeline, Difficulty.MEDIUM, CH0).size();
        int hard = MapGenerator.generate(timeline, Difficulty.HARD, CH0).size();
        int expert = MapGenerator.generate(timeline, Difficulty.EXPERT, CH0).size();
        assertTrue("EASY <= MEDIUM", easy <= medium);
        assertTrue("MEDIUM <= HARD", medium <= hard);
        assertTrue("HARD <= EXPERT", hard <= expert);
    }

    /**
     * "All channels" (empty set) must merge so the line stays dense when the lead instrument drops
     * out and another carries the tune — the "went cold mid-song" bug.
     */
    @Test
    public void allChannelsMergeAcrossSections()
    {
        List<NoteEvent> timeline = new ArrayList<>();
        for (int t = 0; t <= 2000; t += 100)
        {
            timeline.add(new NoteEvent(t, 0, 70, 90)); // section A on channel 0
        }
        for (int t = 2100; t <= 4000; t += 100)
        {
            timeline.add(new NoteEvent(t, 1, 72, 90)); // section B on channel 1
        }
        Collections.sort(timeline);

        int forcedCh0 = MapGenerator.generate(timeline, Difficulty.EXPERT, CH0).size();
        List<ChartNote> all = MapGenerator.generate(timeline, Difficulty.EXPERT, ALL);

        long notesInSectionB = all.stream().filter(c -> c.hitTimeMs >= 2100).count();
        assertTrue("merging should keep section B notes", notesInSectionB > 5);
        assertTrue("merging keeps more than the single cold channel", all.size() > forcedCh0);
    }

    /**
     * A dynamic melody must spread across the lanes even when a low bass line widens the absolute
     * pitch range — the "long string of one note in the same lane" bug.
     */
    @Test
    public void dynamicMelodySpreadsAcrossLanesDespiteBass()
    {
        List<NoteEvent> timeline = new ArrayList<>();
        int[] tune = {60, 62, 64, 65, 67, 69, 71, 72, 71, 69, 67, 65, 64, 62, 60};
        for (int k = 0; k < tune.length; k++)
        {
            timeline.add(new NoteEvent(k * 200L, 0, tune[k], 90));
            if (k % 3 == 0)
            {
                timeline.add(new NoteEvent(k * 200L + 80, 1, 36, 70));
            }
        }
        Collections.sort(timeline);

        List<ChartNote> chart = MapGenerator.generate(timeline, Difficulty.EXPERT, ALL);
        Set<Lane> lanes = new HashSet<>();
        for (ChartNote c : chart)
        {
            lanes.add(c.lane);
        }
        assertTrue("a dynamic melody should use several lanes, not collapse into one",
            lanes.size() >= 4);
    }

    /**
     * Smart picks the melodic lead plus a second melodic voice, while excluding the low bass
     * accompaniment (even though the bass has the most notes).
     */
    @Test
    public void smartPicksLeadAndSecondMelodicVoice()
    {
        List<NoteEvent> timeline = new ArrayList<>();
        // Lead on ch3: moving melody, high register, first half (0-1750ms).
        int[] tune = {72, 74, 76, 77, 79, 77, 76, 74};
        for (int s = 0; s < tune.length; s++)
        {
            timeline.add(new NoteEvent(s * 250L, 3, tune[s], 100));
        }
        // Bass-ish accompaniment on ch0: low, few distinct pitches, throughout (busiest by count).
        for (int t = 0; t < 4000; t += 250)
        {
            timeline.add(new NoteEvent(t, 0, 40 + (t / 1000) % 2, 80));
        }
        // Second high melodic voice on ch5: second half (2000-3750ms), fills the lead's gap.
        int[] tune2 = {79, 81, 83, 84, 83, 81, 79, 78};
        for (int s = 0; s < tune2.length; s++)
        {
            timeline.add(new NoteEvent(2000 + s * 250L, 5, tune2[s], 100));
        }
        Collections.sort(timeline);

        int[] smart = MapGenerator.smartChannels(timeline);
        Set<Integer> picked = new HashSet<>();
        for (int c : smart)
        {
            picked.add(c);
        }
        assertEquals("smart should pick two complementary channels", 2, smart.length);
        assertTrue("includes the high melodic lead (ch5)", picked.contains(5));
        assertTrue("includes the gap-filling melodic voice (ch3)", picked.contains(3));
        assertFalse("excludes the low bass accompaniment (ch0)", picked.contains(0));
    }

    /** A big contiguous hole (one voice owns a long stretch the other two don't) -> Smart picks 3. */
    @Test
    public void smartAddsThirdVoiceForBigHole()
    {
        List<NoteEvent> timeline = new ArrayList<>();
        int[] tune = {72, 74, 76, 77, 79, 77, 76, 74};
        // Three equally-melodic high voices, each owning a long (~6s) non-overlapping stretch.
        int[] channels = {2, 4, 6};
        long[] starts = {0L, 7000L, 14000L};
        for (int v = 0; v < channels.length; v++)
        {
            for (int s = 0; s < 24; s++)
            {
                timeline.add(new NoteEvent(starts[v] + s * 250L, channels[v], tune[s % tune.length], 100));
            }
        }
        Collections.sort(timeline);

        int[] smart = MapGenerator.smartChannels(timeline);
        assertEquals("a long uncovered melodic stretch should add a 3rd channel", 3, smart.length);
    }

    /**
     * A sparse channel that plays ONLY during a big hole (and would fail the global activity filter)
     * must still be added as the gap-filling 3rd voice.
     */
    @Test
    public void smartFillsBigHoleWithSparseGapChannel()
    {
        List<NoteEvent> timeline = new ArrayList<>();
        int[] tune = {72, 74, 76, 77, 79, 77, 76, 74};
        // ch2 + ch4: dense leads, present 0..6000ms and 14000..26000ms (a ~8s hole between).
        for (int t = 0; t <= 6000; t += 150)
        {
            timeline.add(new NoteEvent(t, 2, tune[(t / 150) % 8], 100));
            timeline.add(new NoteEvent(t, 4, tune[(t / 150) % 8], 100));
        }
        for (int t = 14000; t <= 26000; t += 150)
        {
            timeline.add(new NoteEvent(t, 2, tune[(t / 150) % 8], 100));
            timeline.add(new NoteEvent(t, 4, tune[(t / 150) % 8], 100));
        }
        // ch6: sparse, plays ONLY in the hole (~10 notes) — far below the busy leads' activity.
        for (int t = 6400; t <= 13600; t += 800)
        {
            timeline.add(new NoteEvent(t, 6, tune[(t / 800) % 8], 100));
        }
        Collections.sort(timeline);

        int[] smart = MapGenerator.smartChannels(timeline);
        boolean has6 = false;
        for (int c : smart)
        {
            if (c == 6)
            {
                has6 = true;
            }
        }
        assertTrue("a sparse channel that only plays during a big hole should be added", has6);
    }

    /** Only brief, scattered gaps (no long hole) -> Smart stays at two channels. */
    @Test
    public void smartStaysAtTwoForBriefGapsOnly()
    {
        List<NoteEvent> timeline = new ArrayList<>();
        int[] tune = {72, 74, 76, 77, 79, 77, 76, 74};
        // Two voices that together cover the whole span; a third voice only doubles existing time.
        for (int s = 0; s < 40; s++)
        {
            timeline.add(new NoteEvent(s * 250L, 2, tune[s % tune.length], 100));        // ch2 throughout
            timeline.add(new NoteEvent(s * 250L + 60, 4, tune[s % tune.length], 100));   // ch4 throughout
            timeline.add(new NoteEvent(s * 250L + 120, 6, tune[s % tune.length], 100));  // ch6 overlaps both
        }
        Collections.sort(timeline);

        int[] smart = MapGenerator.smartChannels(timeline);
        assertTrue("no big hole -> at most two channels", smart.length <= 2);
    }

    /** Easy/Medium confine notes to their 3 lanes; Hard/Expert can reach the outer lanes. */
    @Test
    public void difficultyLaneSetsAreRespected()
    {
        List<NoteEvent> timeline = new ArrayList<>();
        int[] tune = {48, 55, 60, 64, 67, 72, 76, 72, 67, 64, 60, 55, 48};
        for (int k = 0; k < tune.length; k++)
        {
            timeline.add(new NoteEvent(k * 250L, 0, tune[k], 90));
        }

        Set<Lane> three = new HashSet<>();
        three.add(Lane.INVENTORY);
        three.add(Lane.EQUIPMENT);
        three.add(Lane.PRAYER);

        for (Difficulty d : new Difficulty[]{Difficulty.EASY, Difficulty.MEDIUM})
        {
            Set<Lane> used = new HashSet<>();
            for (ChartNote c : MapGenerator.generate(timeline, d, CH0))
            {
                used.add(c.lane);
            }
            assertTrue(d + " must use only its 3 lanes", three.containsAll(used));
            assertTrue(d + " should still spread across lanes", used.size() >= 2);
        }

        Set<Lane> expertLanes = new HashSet<>();
        for (ChartNote c : MapGenerator.generate(timeline, Difficulty.EXPERT, CH0))
        {
            expertLanes.add(c.lane);
        }
        assertTrue("Expert should reach an outer lane",
            expertLanes.contains(Lane.COMBAT) || expertLanes.contains(Lane.MAGIC));
    }

    @Test
    public void emptyOrNullTimelineYieldsEmptyChart()
    {
        assertTrue(MapGenerator.generate(new ArrayList<>(), Difficulty.MEDIUM, ALL).isEmpty());
        assertTrue(MapGenerator.generate(null, Difficulty.MEDIUM, ALL).isEmpty());
    }

    /** Lowest pitch lands in the leftmost lane, highest in the rightmost. */
    @Test
    public void laneBandingSpansPitchRange()
    {
        List<NoteEvent> timeline = new ArrayList<>();
        timeline.add(new NoteEvent(0, 0, 40, 100));
        timeline.add(new NoteEvent(1000, 0, 100, 100));
        List<ChartNote> chart = MapGenerator.generate(timeline, Difficulty.EXPERT, CH0);
        assertEquals(2, chart.size());
        assertEquals(Lane.COMBAT, chart.get(0).lane);
        assertEquals(Lane.MAGIC, chart.get(1).lane);
    }
}
