package com.sob.runehero;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The play field: falling rune notes, per-lane targets that light up, lane "flames" + combo glow,
 * streak multiplier, milestone callouts, hit-burst rune shards, and an end card. Layout is either
 * OVER_TABS (notes fall onto the real inventory tabs) or CENTER (a free-floating board mid-screen).
 * All motion derives from {@link GameSession#elapsedMs(long)}; also drives the per-frame miss sweep.
 */
class RuneHeroOverlay extends Overlay
{
    private static final int JUDGMENT_LIFE_MS = 500;
    private static final int FLASH_MS = 350;
    private static final int MILESTONE_LIFE_MS = 1300;
    private static final int END_CARD_MS = 6000;
    private static final float COMBO_FULL = 30f;
    private static final double SHARD_LIFE_SEC = 0.6;
    private static final double SHARD_GRAVITY = 700; // px/s^2
    private static final int[] MILESTONES = {10, 25, 50, 75, 100, 150, 200, 300, 400, 500};

    private static final Color PERFECT = new Color(90, 230, 120);
    private static final Color GOOD = new Color(240, 210, 80);
    private static final Color MISS = new Color(230, 80, 80);
    private static final Color GOLD = new Color(255, 200, 60);
    private static final Color FLAME = new Color(255, 140, 40);

    private final GameSession session;
    private final TabBounds tabBounds;
    private final RuneHeroConfig config;
    private final ItemManager itemManager;
    private final Client client;

    private final BufferedImage[] runeImg = new BufferedImage[Lane.COUNT];
    private final long[] lastFlashSpawned = new long[Lane.COUNT];
    private final List<Shard> shards = new ArrayList<>();
    private final Random rng = new Random();

    private int lastMilestone;
    private long milestoneNanos = Long.MIN_VALUE;
    private int milestoneValue;

    @Inject
    RuneHeroOverlay(GameSession session, TabBounds tabBounds, RuneHeroConfig config,
        ItemManager itemManager, Client client)
    {
        this.session = session;
        this.tabBounds = tabBounds;
        this.config = config;
        this.itemManager = itemManager;
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        long nowNanos = System.nanoTime();

        if (session.isArmed() && !session.isRunning())
        {
            drawArmedIndicator(g);
            return null;
        }
        if (!session.isRunning())
        {
            drawEndCard(g, nowNanos);
            return null;
        }

        Lane[] active = session.difficulty().lanes();
        Rectangle[] cells = computeCells(active);
        if (cells == null)
        {
            return null;
        }
        Rectangle[] cellByLane = new Rectangle[Lane.COUNT];
        for (int i = 0; i < active.length; i++)
        {
            cellByLane[active[i].ordinal()] = cells[i];
        }

        session.sweepMisses(nowNanos);

        long now = session.elapsedMs(nowNanos);
        long lead = session.difficulty().leadTimeMs;
        int fallDistance = config.fallDistance();
        int hitLineY = cells[0].y;
        int topY = hitLineY - fallDistance;
        int combo = session.combo();
        float comboT = Math.min(combo / COMBO_FULL, 1f);
        boolean center = config.notePosition() == RuneHeroConfig.NotePosition.CENTER;
        boolean runes = config.runeIcons();

        if (combo == 0)
        {
            lastMilestone = 0;
        }

        Stroke prevStroke = g.getStroke();

        // Lane tracks (rising "flame" that warms toward gold with the combo), targets, held frets.
        for (int i = 0; i < active.length; i++)
        {
            Lane lane = active[i];
            Rectangle cell = cells[i];
            int laneW = Math.max(cell.width - 2, 6);
            int lx = cell.x + cell.width / 2 - laneW / 2;

            Color flameTop = new Color(255, 255, 255, 14);
            Color flameBottom = lerp(Color.WHITE, FLAME, comboT, 24 + (int) (comboT * 150));
            g.setPaint(new GradientPaint(lx, topY, flameTop, lx, hitLineY, flameBottom));
            g.fillRect(lx, topY, laneW, fallDistance);

            if (center)
            {
                g.setColor(new Color(0, 0, 0, 130));
                g.fillRoundRect(cell.x, cell.y, cell.width, cell.height, 8, 8);
                BufferedImage img = runes ? runeImage(lane) : null;
                if (img != null)
                {
                    g.drawImage(img, cell.x + 2, cell.y + 2, cell.width - 4, cell.height - 4, null);
                }
                else
                {
                    g.setColor(alpha(lane.color, 150));
                    g.fillRoundRect(cell.x + 3, cell.y + 3, cell.width - 6, cell.height - 6, 6, 6);
                }
                g.setColor(alpha(lane.color, 170));
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(cell.x, cell.y, cell.width, cell.height, 8, 8);
            }

            if (session.isFretHeld(lane))
            {
                g.setColor(alpha(lane.color, 110));
                g.fillRect(cell.x, cell.y, cell.width, cell.height);
            }
        }

        // Hit line — brightens and thickens toward gold as the combo grows.
        Rectangle first = cells[0];
        Rectangle last = cells[active.length - 1];
        g.setColor(lerp(Color.WHITE, GOLD, comboT, 190 + (int) (comboT * 65)));
        g.setStroke(new BasicStroke(2f + comboT * 2f));
        g.drawLine(first.x, hitLineY, last.x + last.width, hitLineY);

        // Falling notes.
        for (ChartNote note : session.visibleNotes(nowNanos))
        {
            Rectangle cell = cellByLane[note.lane.ordinal()];
            if (cell == null)
            {
                continue;
            }
            int cx = cell.x + cell.width / 2;
            double progress = (double) (note.hitTimeMs - now) / lead;
            if (progress < 0)
            {
                progress = 0;
            }
            int y = (int) (hitLineY - progress * fallDistance);
            int size = Math.max(Math.min(cell.width, cell.height) - 2, 10);
            BufferedImage img = runes ? runeImage(note.lane) : null;
            g.setColor(alpha(note.lane.color, 70));
            g.fillRoundRect(cx - size / 2, y - size / 2, size, size, 8, 8);
            if (img != null)
            {
                g.drawImage(img, cx - size / 2, y - size / 2, size, size, null);
            }
            g.setColor(alpha(note.lane.color.brighter(), 200));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(cx - size / 2, y - size / 2, size, size, 8, 8);
        }

        // Per-lane flashes + spawn hit-burst shards on new hits.
        for (int i = 0; i < active.length; i++)
        {
            maybeSpawnBurst(active[i], cells[i], hitLineY, nowNanos);
            drawLaneFlash(g, cells[i], hitLineY, active[i], nowNanos);
        }
        drawShards(g, nowNanos);

        g.setStroke(prevStroke);
        drawHud(g, first, last, topY, hitLineY, comboT, combo, nowNanos);
        return null;
    }

    private Rectangle[] computeCells(Lane[] active)
    {
        if (config.notePosition() == RuneHeroConfig.NotePosition.CENTER)
        {
            int n = active.length;
            int cw = client.getCanvasWidth();
            int ch = client.getCanvasHeight();
            if (cw <= 0 || ch <= 0)
            {
                return null;
            }
            int cell = 42;
            int gap = 12;
            int totalW = n * cell + (n - 1) * gap;
            int x0 = (cw - totalW) / 2;
            int fall = config.fallDistance();
            int playH = fall + cell;
            int topAreaY = Math.max(70, (ch - playH) / 2 + 30);
            int hitY = topAreaY + fall;
            Rectangle[] cells = new Rectangle[n];
            for (int i = 0; i < n; i++)
            {
                cells[i] = new Rectangle(x0 + i * (cell + gap), hitY, cell, cell);
            }
            return cells;
        }

        Rectangle[] tabs = tabBounds.resolve();
        if (tabs == null || tabs.length < TabBounds.MAIN_TAB_COUNT)
        {
            return null;
        }
        Rectangle[] cells = new Rectangle[active.length];
        for (int i = 0; i < active.length; i++)
        {
            cells[i] = tabs[active[i].screenPos];
        }
        return cells;
    }

    private void maybeSpawnBurst(Lane lane, Rectangle cell, int hitLineY, long nowNanos)
    {
        long fn = session.flashNanos(lane);
        if (fn == Long.MIN_VALUE || fn == lastFlashSpawned[lane.ordinal()])
        {
            return;
        }
        lastFlashSpawned[lane.ordinal()] = fn;
        GameSession.Judgment j = session.flashJudgment(lane);
        if (j == GameSession.Judgment.MISS)
        {
            return;
        }
        int count = j == GameSession.Judgment.PERFECT ? 9 : 4;
        if (shards.size() > 200)
        {
            return;
        }
        int cx = cell.x + cell.width / 2;
        for (int i = 0; i < count; i++)
        {
            double ang = -Math.PI / 2 + (rng.nextDouble() - 0.5) * Math.PI; // upward fan
            double spd = 120 + rng.nextDouble() * 180;
            Shard s = new Shard();
            s.x0 = cx;
            s.y0 = hitLineY;
            s.vx = Math.cos(ang) * spd;
            s.vy = Math.sin(ang) * spd;
            s.born = fn;
            s.color = lane.color;
            shards.add(s);
        }
    }

    private void drawShards(Graphics2D g, long nowNanos)
    {
        for (Iterator<Shard> it = shards.iterator(); it.hasNext(); )
        {
            Shard s = it.next();
            double age = (nowNanos - s.born) / 1_000_000_000.0;
            if (age < 0 || age >= SHARD_LIFE_SEC)
            {
                it.remove();
                continue;
            }
            int x = (int) (s.x0 + s.vx * age);
            int y = (int) (s.y0 + s.vy * age + 0.5 * SHARD_GRAVITY * age * age);
            int a = (int) (220 * (1 - age / SHARD_LIFE_SEC));
            g.setColor(alpha(s.color, a));
            g.fillOval(x - 2, y - 2, 5, 5);
        }
    }

    private void drawLaneFlash(Graphics2D g, Rectangle cell, int hitLineY, Lane lane, long nowNanos)
    {
        long fn = session.flashNanos(lane);
        if (fn == Long.MIN_VALUE)
        {
            return;
        }
        long age = (nowNanos - fn) / 1_000_000L;
        if (age < 0 || age >= FLASH_MS)
        {
            return;
        }
        float p = age / (float) FLASH_MS;
        Color base = judgeColor(session.flashJudgment(lane));
        int cx = cell.x + cell.width / 2;
        int a = (int) (220 * (1f - p));

        g.setColor(alpha(base, (int) (a * 0.45f)));
        g.fillRect(cell.x, cell.y, cell.width, cell.height);

        int r = (int) (cell.width * 0.45f + p * cell.width);
        g.setColor(alpha(base, a));
        g.setStroke(new BasicStroke(2.5f));
        g.drawOval(cx - r, hitLineY - r, r * 2, r * 2);
    }

    private void drawHud(Graphics2D g, Rectangle first, Rectangle last, int topY, int hitLineY,
        float comboT, int combo, long nowNanos)
    {
        int left = first.x;
        int centerX = (first.x + last.x + last.width) / 2;
        Font baseFont = g.getFont();

        g.setFont(baseFont.deriveFont(Font.BOLD, 12f));
        String score = "Score " + session.score();
        int scoreY = Math.max(topY - 8, 14);
        g.setColor(Color.BLACK);
        g.drawString(score, left + 1, scoreY + 1);
        g.setColor(Color.WHITE);
        g.drawString(score, left, scoreY);

        if (combo >= 2)
        {
            g.setFont(baseFont.deriveFont(Font.BOLD, 13f + comboT * 9f));
            String text = Integer.toString(combo);
            int w = g.getFontMetrics().stringWidth(text);
            int cy = Math.max(topY - 8, 16);
            g.setColor(Color.BLACK);
            g.drawString(text, centerX - w / 2 + 1, cy + 1);
            g.setColor(lerp(Color.WHITE, GOLD, comboT, 255));
            g.drawString(text, centerX - w / 2, cy);

            int mult = session.multiplier();
            if (mult > 1)
            {
                g.setFont(baseFont.deriveFont(Font.BOLD, 11f + comboT * 4f));
                String mt = "x" + mult;
                int mw = g.getFontMetrics().stringWidth(mt);
                g.setColor(Color.BLACK);
                g.drawString(mt, centerX - mw / 2 + 1, cy + 17);
                g.setColor(alpha(GOLD, 255));
                g.drawString(mt, centerX - mw / 2, cy + 16);
            }
        }

        for (int m : MILESTONES)
        {
            if (combo >= m && m > lastMilestone)
            {
                lastMilestone = m;
                milestoneValue = m;
                milestoneNanos = nowNanos;
            }
        }
        if (milestoneNanos != Long.MIN_VALUE)
        {
            long mAge = (nowNanos - milestoneNanos) / 1_000_000L;
            if (mAge >= 0 && mAge < MILESTONE_LIFE_MS)
            {
                float mp = mAge / (float) MILESTONE_LIFE_MS;
                int a = (int) (255 * (1f - mp));
                float pop = 1f + 0.5f * (1f - Math.min(mp * 3f, 1f));
                g.setFont(baseFont.deriveFont(Font.BOLD, 26f * pop));
                String text = milestoneValue + " COMBO!";
                int w = g.getFontMetrics().stringWidth(text);
                int my = Math.max(topY - 34, 40);
                g.setColor(new Color(0, 0, 0, a));
                g.drawString(text, centerX - w / 2 + 2, my + 2);
                g.setColor(alpha(GOLD, a));
                g.drawString(text, centerX - w / 2, my);
            }
        }

        if (!config.showJudgment())
        {
            return;
        }
        GameSession.Judgment j = session.lastJudgment();
        if (j == null)
        {
            return;
        }
        long age = (nowNanos - session.lastJudgmentNanos()) / 1_000_000L;
        if (age < 0 || age >= JUDGMENT_LIFE_MS)
        {
            return;
        }
        int alpha = (int) (255 * (1f - age / (float) JUDGMENT_LIFE_MS));
        g.setFont(baseFont.deriveFont(Font.BOLD, 15f));
        g.setColor(alpha(judgeColor(j), Math.max(0, alpha)));
        g.drawString(j.name(), left, hitLineY - 8);
    }

    private void drawEndCard(Graphics2D g, long nowNanos)
    {
        long e = session.endedNanos();
        if (e == Long.MIN_VALUE)
        {
            return;
        }
        long age = (nowNanos - e) / 1_000_000L;
        if (age < 0 || age >= END_CARD_MS)
        {
            return;
        }
        int total = session.totalNotes();
        if (total == 0)
        {
            return;
        }
        int cw = client.getCanvasWidth();
        int ch = client.getCanvasHeight();
        if (cw <= 0 || ch <= 0)
        {
            return;
        }

        int hits = session.hits();
        int acc = (int) Math.round(100.0 * hits / total);
        String rank = acc >= 98 ? "S" : acc >= 95 ? "A" : acc >= 88 ? "B"
            : acc >= 75 ? "C" : acc >= 60 ? "D" : "E";
        boolean fc = session.misses() == 0;

        int alpha = 255;
        long fade = 700;
        if (age > END_CARD_MS - fade)
        {
            alpha = (int) (255 * (END_CARD_MS - age) / (double) fade);
        }
        alpha = Math.max(0, Math.min(255, alpha));

        int pw = 280;
        int ph = 156;
        int px = (cw - pw) / 2;
        int py = (ch - ph) / 2;
        g.setColor(new Color(0, 0, 0, (int) (alpha * 0.72f)));
        g.fillRoundRect(px, py, pw, ph, 16, 16);
        g.setColor(alpha(GOLD, alpha));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(px, py, pw, ph, 16, 16);

        Font bf = g.getFont();
        int midX = cw / 2;

        g.setFont(bf.deriveFont(Font.BOLD, 40f));
        drawCentered(g, "RANK " + rank, midX, py + 52, alpha(rankColor(rank), alpha), alpha);

        int statsY = py + 84;
        if (fc)
        {
            g.setFont(bf.deriveFont(Font.BOLD, 16f));
            drawCentered(g, "FULL COMBO!", midX, py + 80, alpha(GOLD, alpha), alpha);
            statsY = py + 104;
        }

        g.setFont(bf.deriveFont(Font.BOLD, 13f));
        drawCentered(g, hits + "/" + total + "  (" + acc + "%)", midX, statsY, alpha(Color.WHITE, alpha), alpha);
        drawCentered(g, "Max combo " + session.maxCombo() + "    Score " + session.score(),
            midX, statsY + 20, alpha(Color.WHITE, alpha), alpha);
    }

    private void drawCentered(Graphics2D g, String text, int centerX, int y, Color color, int shadowAlpha)
    {
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        g.setColor(new Color(0, 0, 0, Math.max(0, Math.min(255, shadowAlpha))));
        g.drawString(text, centerX - w / 2 + 1, y + 1);
        g.setColor(color);
        g.drawString(text, centerX - w / 2, y);
    }

    private void drawArmedIndicator(Graphics2D g)
    {
        Lane[] active = session.difficulty().lanes();
        Rectangle[] cells = computeCells(active);
        if (cells == null)
        {
            return;
        }
        Rectangle first = cells[0];
        Rectangle last = cells[active.length - 1];
        int left = first.x;
        int width = Math.max((last.x + last.width) - left, 300);
        int top = first.y - config.fallDistance();
        int boxH = 64;

        g.setColor(new Color(0, 0, 0, 185));
        g.fillRect(left, top, width, boxH);
        g.setColor(GOLD);
        g.drawRect(left, top, width, boxH);

        Font baseFont = g.getFont();
        g.setFont(baseFont.deriveFont(Font.BOLD, 20f));
        g.setColor(GOLD);
        g.drawString("RUNEHERO — ARMED", left + 10, top + 26);
        g.setFont(baseFont.deriveFont(Font.BOLD, 15f));
        g.setColor(Color.WHITE);
        String hint = session.armSawMusicOff()
            ? "Now turn Music back ON to start ▶"
            : "Turn Music OFF then ON to sync to the song";
        g.drawString(hint, left + 10, top + 50);
    }

    private BufferedImage runeImage(Lane lane)
    {
        int i = lane.ordinal();
        if (runeImg[i] == null)
        {
            runeImg[i] = itemManager.getImage(lane.runeItemId);
        }
        return runeImg[i];
    }

    private static Color rankColor(String rank)
    {
        switch (rank)
        {
            case "S":
                return GOLD;
            case "A":
                return PERFECT;
            case "B":
                return new Color(90, 200, 235);
            case "C":
                return Color.WHITE;
            case "D":
                return FLAME;
            default:
                return MISS;
        }
    }

    private static Color judgeColor(GameSession.Judgment j)
    {
        if (j == GameSession.Judgment.PERFECT)
        {
            return PERFECT;
        }
        if (j == GameSession.Judgment.GOOD)
        {
            return GOOD;
        }
        return MISS;
    }

    private static Color alpha(Color c, int a)
    {
        int aa = a < 0 ? 0 : Math.min(a, 255);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), aa);
    }

    private static Color lerp(Color from, Color to, float t, int a)
    {
        float u = t < 0 ? 0 : Math.min(t, 1f);
        int r = (int) (from.getRed() + (to.getRed() - from.getRed()) * u);
        int gg = (int) (from.getGreen() + (to.getGreen() - from.getGreen()) * u);
        int b = (int) (from.getBlue() + (to.getBlue() - from.getBlue()) * u);
        return new Color(r, gg, b, Math.min(Math.max(a, 0), 255));
    }

    private static final class Shard
    {
        double x0;
        double y0;
        double vx;
        double vy;
        long born;
        Color color;
    }
}
