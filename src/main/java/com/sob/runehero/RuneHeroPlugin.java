package com.sob.runehero;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MidiRequest;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
    name = "RuneHero",
    description = "A Guitar Hero-style rhythm minigame played on the inventory tabs.",
    tags = {"music", "rhythm", "minigame", "guitar", "hero", "midi", "tabs"}
)
public class RuneHeroPlugin extends Plugin implements KeyListener
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private RuneHeroConfig config;
    @Inject private OverlayManager overlayManager;
    @Inject private RuneHeroOverlay overlay;
    @Inject private GameSession session;
    @Inject private MidiCacheLoader cacheLoader;
    @Inject private KeyManager keyManager;

    private ExecutorService loadExec;
    private ScheduledExecutorService pollExec;
    private boolean wasRunning; // for posting results when a chart ends (client thread only)

    @Override
    protected void startUp()
    {
        loadExec = Executors.newSingleThreadExecutor(r ->
        {
            Thread t = new Thread(r, "runehero-load");
            t.setDaemon(true);
            return t;
        });
        pollExec = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "runehero-poll");
            t.setDaemon(true);
            return t;
        });
        pollExec.scheduleAtFixedRate(
            () -> clientThread.invoke(this::pollArm), 500, 250, TimeUnit.MILLISECONDS);
        overlayManager.add(overlay);
        keyManager.registerKeyListener(this);
    }

    @Override
    protected void shutDown()
    {
        keyManager.unregisterKeyListener(this);
        overlayManager.remove(overlay);
        session.stop();
        if (pollExec != null)
        {
            pollExec.shutdownNow();
            pollExec = null;
        }
        if (loadExec != null)
        {
            loadExec.shutdownNow();
            loadExec = null;
        }
    }

    @Provides
    RuneHeroConfig provideConfig(ConfigManager cm)
    {
        return cm.getConfig(RuneHeroConfig.class);
    }

    // ---- KeyListener: judging is keypress-only; we never read the active tab. ----

    @Override
    public void keyTyped(KeyEvent e)
    {
    }

    @Override
    public void keyPressed(KeyEvent e)
    {
        if (config.startKey().matches(e))
        {
            toggleSession();
            return;
        }
        if (config.analyzeKey().matches(e))
        {
            clientThread.invoke(this::analyzeCurrentTrack);
            return;
        }
        if (!session.isRunning())
        {
            return;
        }
        // Only the lanes this difficulty uses are live, so an unused lane's key can't break combo.
        Lane[] active = session.difficulty().lanes();
        if (config.requireStrum())
        {
            if (config.strumKey().matches(e))
            {
                session.onStrum(System.nanoTime());
                return;
            }
            for (Lane lane : active)
            {
                if (fretBind(lane).matches(e))
                {
                    session.setFret(lane, true);
                    return;
                }
            }
        }
        else
        {
            // No-strum: a fresh fret press is the hit. Ignore OS key auto-repeat (held key
            // re-fires keyPressed) so repeated notes need a genuine re-tap.
            for (Lane lane : active)
            {
                if (fretBind(lane).matches(e))
                {
                    if (!session.isFretHeld(lane))
                    {
                        session.setFret(lane, true);
                        session.onLaneHit(lane, System.nanoTime());
                    }
                    return;
                }
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e)
    {
        for (Lane lane : Lane.ORDER)
        {
            if (fretBind(lane).matches(e))
            {
                session.setFret(lane, false);
                return;
            }
        }
    }

    private void toggleSession()
    {
        if (session.isRunning())
        {
            session.stop();
            return;
        }
        if (session.isArmed())
        {
            session.disarm(); // press again to cancel arming
            return;
        }
        // Wait for the player to restart the track (Music off→on) so chart t=0 = audio t=0.
        session.arm();
    }

    /**
     * Client-thread poll (250ms). While armed, watch the live track: it must go quiet and come
     * back (the player toggling Music off→on) before we begin, so the chart's t=0 lands on the
     * instant the track restarts and the falling notes line up with the audio.
     */
    private void pollArm()
    {
        // Detect a chart ending (running -> not running) and post the results once.
        if (session.isRunning())
        {
            wasRunning = true;
            return;
        }
        if (wasRunning)
        {
            wasRunning = false;
            postResults();
            return;
        }
        if (!session.isArmed())
        {
            return;
        }
        List<MidiRequest> active = client.getActiveMidiRequests();
        boolean musicOn = active != null && !active.isEmpty();
        if (!musicOn)
        {
            session.setArmSawMusicOff(true);
            return;
        }
        if (!session.armSawMusicOff())
        {
            return; // music has been playing since arming — wait for an off→on toggle
        }
        long detectionNanos = System.nanoTime();
        MidiRequest req = active.get(0);
        session.disarm();
        long startNanos = detectionNanos + TimeUnit.MILLISECONDS.toNanos(config.syncOffsetMs());
        beginChart(req.getArchiveId(), req.isJingle(), startNanos);
    }

    /** Client thread: load archive bytes, then parse + chart off-thread and start at startNanos. */
    private void beginChart(int archiveId, boolean jingle, long startNanos)
    {
        byte[] bytes = cacheLoader.load(archiveId, jingle);
        if (bytes == null)
        {
            log.info("RuneHero: couldn't load music archive {} from your cache.", archiveId);
            return;
        }
        final byte[] cacheBytes = bytes;
        final Difficulty diff = config.difficulty();
        final int[] manual = manualChannels();
        loadExec.submit(() ->
        {
            try
            {
                List<NoteEvent> timeline = MidiTrackResolver.flatten(cacheBytes);
                // Channel A/B set -> use those; both -1 -> Smart auto-pick.
                int[] channels = manual.length > 0 ? manual : MapGenerator.smartChannels(timeline);
                List<ChartNote> chart = MapGenerator.generate(timeline, diff, channels);
                if (chart.isEmpty())
                {
                    log.info("RuneHero: empty chart (no notes on the chosen channel).");
                    return;
                }
                session.start(chart, diff, startNanos);
                final int noteCount = chart.size();
                final String chDesc = describeChannels(channels, MidiTrackResolver.analyze(cacheBytes));
                clientThread.invoke(() ->
                    chat("[RuneHero] " + diff + ", " + noteCount + " notes — " + chDesc + ". Go!"));
                log.debug("RuneHero: started {}-note chart ({}) from {}", noteCount, diff, chDesc);
            }
            catch (Throwable t)
            {
                log.warn("RuneHero: failed to build chart", t);
            }
        });
    }

    /** Client thread: load + analyze the current/configured track and print its channels to chat. */
    private void analyzeCurrentTrack()
    {
        List<MidiRequest> active = client.getActiveMidiRequests();
        if (active == null || active.isEmpty())
        {
            chat("RuneHero: no music playing to analyze.");
            return;
        }
        MidiRequest req = active.get(0);
        byte[] bytes = cacheLoader.load(req.getArchiveId(), req.isJingle());
        if (bytes == null)
        {
            chat("RuneHero: couldn't load the current track to analyze.");
            return;
        }
        final byte[] cacheBytes = bytes;
        loadExec.submit(() ->
        {
            try
            {
                List<ChannelInfo> infos = MidiTrackResolver.analyze(cacheBytes);
                List<NoteEvent> timeline = MidiTrackResolver.flatten(cacheBytes);
                int[] smart = MapGenerator.smartChannels(timeline);
                int[] manual = manualChannels();
                boolean isManual = manual.length > 0;
                int[] chosen = isManual ? manual : smart;
                List<ChartNote> preview = MapGenerator.generate(timeline, config.difficulty(), chosen);
                long noteGapMs = MapGenerator.longestNoteGapMs(preview);
                int gapChannel = busiestUncoveredChannelInLongestGap(preview, timeline, chosen);
                clientThread.invoke(() -> postAnalysis(infos, smart, chosen, isManual, noteGapMs, gapChannel));
            }
            catch (Throwable t)
            {
                log.warn("RuneHero: failed to analyze track", t);
            }
        });
    }

    /**
     * The non-chosen, non-drum channel that plays the most during the chart's longest note gap —
     * i.e. the instrument carrying the melody while your lanes rest. -1 if the gap is genuine silence.
     */
    private static int busiestUncoveredChannelInLongestGap(List<ChartNote> chart,
        List<NoteEvent> timeline, int[] chosen)
    {
        if (chart.size() < 2)
        {
            return -1;
        }
        long gapStart = 0;
        long gapEnd = 0;
        long longest = 0;
        for (int i = 1; i < chart.size(); i++)
        {
            long g = chart.get(i).hitTimeMs - chart.get(i - 1).hitTimeMs;
            if (g > longest)
            {
                longest = g;
                gapStart = chart.get(i - 1).hitTimeMs;
                gapEnd = chart.get(i).hitTimeMs;
            }
        }
        if (longest < 4000)
        {
            return -1;
        }
        boolean[] inChosen = new boolean[16];
        for (int c : chosen)
        {
            if (c >= 0 && c < 16)
            {
                inChosen[c] = true;
            }
        }
        int[] cnt = new int[16];
        for (NoteEvent e : timeline)
        {
            if (e.channel >= 0 && e.channel < 16 && e.channel != 9 && !inChosen[e.channel]
                && e.timestampMs > gapStart && e.timestampMs < gapEnd)
            {
                cnt[e.channel]++;
            }
        }
        int best = -1;
        int bestCnt = 0;
        for (int c = 0; c < 16; c++)
        {
            if (cnt[c] > bestCnt)
            {
                bestCnt = cnt[c];
                best = c;
            }
        }
        return bestCnt >= 4 ? best : -1;
    }

    /** Client thread: state what's actually being charted (Manual vs Smart), then the channel list. */
    private void postAnalysis(List<ChannelInfo> infos, int[] smart, int[] chosen, boolean isManual,
        long noteGapMs, int gapChannel)
    {
        if (infos.isEmpty())
        {
            chat("RuneHero: no instrument channels found in this track.");
            return;
        }
        Set<Integer> smartSet = new HashSet<>();
        for (int c : smart)
        {
            smartSet.add(c);
        }

        // What's ACTUALLY being charted (Manual channels override Smart), then Smart's suggestion.
        chat("[RuneHero] Now charting (" + (isManual ? "Manual" : "Smart") + "): "
            + describeChannels(chosen, infos) + (chosen.length >= 3 ? "  (3 lanes!)" : ""));
        if (isManual)
        {
            chat("Smart suggests: " + describeChannels(smart, infos)
                + "  (set Channel A/B/C to -1 to use it).");
        }
        chat(String.format("Longest pause between notes (this difficulty): %.1fs.", noteGapMs / 1000.0));
        if (gapChannel >= 0)
        {
            chat("During that pause, ch" + gapChannel + " " + instrumentFor(infos, gapChannel)
                + " has the melody — add it (Channel A/B/C) to fill the gap.");
        }
        chat("Channels (Smart marked; set Channel A/B/C to override):");

        // Smart-picked channels first, then the rest by descending note count.
        List<ChannelInfo> ordered = new ArrayList<>(infos);
        ordered.sort((a, b) ->
        {
            boolean sa = smartSet.contains(a.channel);
            boolean sb2 = smartSet.contains(b.channel);
            if (sa != sb2)
            {
                return sa ? -1 : 1;
            }
            return Integer.compare(b.noteCount, a.noteCount);
        });
        for (ChannelInfo ci : ordered)
        {
            String inst = ci.isDrums() ? "Percussion (drums)" : GmInstruments.name(ci.program);
            String line = "  ch" + ci.channel + "  " + inst + " - " + ci.noteCount + " notes, "
                + GmInstruments.noteName(ci.minPitch) + "-" + GmInstruments.noteName(ci.maxPitch);
            if (smartSet.contains(ci.channel))
            {
                line += "  <- SMART";
            }
            chat(line);
        }
    }

    /** Human-readable list of the channels a chart was built from, e.g. "ch3 Acoustic Grand Piano". */
    private static String describeChannels(int[] channels, List<ChannelInfo> infos)
    {
        if (channels.length == 0)
        {
            return "all instruments";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < channels.length; i++)
        {
            if (i > 0)
            {
                sb.append(" + ");
            }
            sb.append("ch").append(channels[i]).append(' ').append(instrumentFor(infos, channels[i]));
        }
        return sb.toString();
    }

    private static String instrumentFor(List<ChannelInfo> infos, int channel)
    {
        for (ChannelInfo ci : infos)
        {
            if (ci.channel == channel)
            {
                return ci.isDrums() ? "drums" : GmInstruments.name(ci.program);
            }
        }
        return "?";
    }

    /** Client thread: end-of-chart summary. */
    private void postResults()
    {
        int total = session.totalNotes();
        if (total == 0)
        {
            return;
        }
        int hits = session.hits();
        int acc = (int) Math.round(100.0 * hits / total);
        String rank = acc >= 98 ? "S" : acc >= 95 ? "A" : acc >= 88 ? "B"
            : acc >= 75 ? "C" : acc >= 60 ? "D" : "E";
        String fc = session.misses() == 0 ? "FULL COMBO! " : "";
        chat("[RuneHero] " + fc + "Rank " + rank + " — " + hits + "/" + total + " (" + acc
            + "%), max combo " + session.maxCombo() + ", score " + session.score() + ".");
    }

    private void chat(String message)
    {
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
    }

    /** Manually chosen channels from config A/B/C (distinct, in order); empty = Smart auto-pick. */
    private int[] manualChannels()
    {
        int[] raw = {config.channelA(), config.channelB(), config.channelC()};
        List<Integer> picked = new ArrayList<>(3);
        for (int c : raw)
        {
            if (c >= 0 && !picked.contains(c))
            {
                picked.add(c);
            }
        }
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = picked.get(i);
        }
        return out;
    }

    private Keybind fretBind(Lane lane)
    {
        switch (lane)
        {
            case COMBAT:
                return config.combatKey();
            case INVENTORY:
                return config.inventoryKey();
            case EQUIPMENT:
                return config.equipmentKey();
            case PRAYER:
                return config.prayerKey();
            case MAGIC:
            default:
                return config.magicKey();
        }
    }
}
