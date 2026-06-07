package com.sob.runehero;

import java.awt.Color;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Keybind;

/**
 * The frets — the inventory side-tabs players actually use: Combat, Inventory, Equipment,
 * Prayer, Magic. {@code screenPos} is the tab's left-to-right index within the full 7-tab main
 * row (Combat=0 … Magic=6), so notes draw over the correct icon while the unused Skills(1) and
 * Quests(2) tabs are skipped. The {@link Keybind} is only a default; the player rebinds each
 * fret in config. We never read which tab is open — judging is keypress-only.
 *
 * Each lane is themed as one of the five Catalytic runes; {@code runeItemId} is the OSRS item id
 * used to draw the real rune sprite as the falling note, and {@code color} tints the backing/glow.
 */
enum Lane
{
    COMBAT("Combat", 0, KeyEvent.VK_Z, 563, "Law", new Color(0x4C, 0x7D, 0xF0)),
    INVENTORY("Inventory", 3, KeyEvent.VK_X, 560, "Death", new Color(0xDA, 0xDA, 0xDA)),
    EQUIPMENT("Equipment", 4, KeyEvent.VK_C, 565, "Blood", new Color(0xC4, 0x20, 0x2A)),
    PRAYER("Prayer", 5, KeyEvent.VK_V, 566, "Soul", new Color(0x9D, 0xB8, 0xDF)),
    MAGIC("Magic", 6, KeyEvent.VK_B, 21880, "Wrath", new Color(0x33, 0xC5, 0x6B));

    final String label;
    final int screenPos;
    final Keybind defaultKey;
    final int runeItemId;
    final String runeName;
    final Color color;

    Lane(String label, int screenPos, int defaultKeyCode, int runeItemId, String runeName, Color color)
    {
        this.label = label;
        this.screenPos = screenPos;
        this.defaultKey = new Keybind(defaultKeyCode, 0);
        this.runeItemId = runeItemId;
        this.runeName = runeName;
        this.color = color;
    }

    static final Lane[] ORDER = values();
    static final int COUNT = ORDER.length; // 5
}
