package com.sob.runehero;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("runehero")
public interface RuneHeroConfig extends Config
{
    @ConfigSection(name = "Gameplay", description = "Start a song, difficulty and instruments", position = 0)
    String gameplaySection = "gameplaySection";

    @ConfigSection(name = "Controls", description = "Fret keys and optional strum", position = 1)
    String controlsSection = "controlsSection";

    @ConfigSection(name = "Visuals", description = "How the play field looks", position = 2)
    String visualsSection = "visualsSection";

    // ---- Gameplay (Start/Analyze first — they're what you reach for) ----

    @ConfigItem(
        keyName = "startKey",
        name = "Start / stop",
        description = "Arm RuneHero, then toggle the in-game Music setting off and back on to start "
            + "the song you're hearing. Press again to stop.",
        section = gameplaySection, position = 0
    )
    default Keybind startKey()
    {
        return new Keybind(KeyEvent.VK_F12, 0);
    }

    @ConfigItem(
        keyName = "analyzeKey",
        name = "Analyze track",
        description = "Print the current track's instruments to the chat box (and what Smart will "
            + "chart), so you can pick the melody with Channel A/B/C. Doesn't start a song.",
        section = gameplaySection, position = 1
    )
    default Keybind analyzeKey()
    {
        return new Keybind(KeyEvent.VK_F11, 0);
    }

    @ConfigItem(
        keyName = "difficulty",
        name = "Difficulty",
        description = "Easy/Medium use 3 lanes; Hard/Expert use all 5. Higher = more notes, faster "
            + "fall, tighter timing.",
        section = gameplaySection, position = 2
    )
    default Difficulty difficulty()
    {
        return Difficulty.HARD;
    }

    @Range(min = -1, max = 15)
    @ConfigItem(
        keyName = "melodyChannelA",
        name = "Channel A",
        description = "Which instruments to chart. Leave A, B and C all at -1 for Smart auto-pick; "
            + "set any to a MIDI channel (0-15) to choose manually — press Analyze (F11) to find it.",
        section = gameplaySection, position = 3
    )
    default int channelA()
    {
        return -1;
    }

    @Range(min = -1, max = 15)
    @ConfigItem(
        keyName = "melodyChannelB",
        name = "Channel B",
        description = "Optional second instrument to blend in (-1 = unused), for melodies split "
            + "across two instruments.",
        section = gameplaySection, position = 4
    )
    default int channelB()
    {
        return -1;
    }

    @Range(min = -1, max = 15)
    @ConfigItem(
        keyName = "melodyChannelC",
        name = "Channel C",
        description = "Optional third instrument (-1 = unused), to fill long pauses on tracks that "
            + "spread the melody across three instruments.",
        section = gameplaySection, position = 5
    )
    default int channelC()
    {
        return -1;
    }

    @Range(min = -1000, max = 1000)
    @Units(Units.MILLISECONDS)
    @ConfigItem(
        keyName = "syncOffsetMs",
        name = "Sync offset",
        description = "Nudge the notes earlier (-) or later (+), in milliseconds, if they feel "
            + "slightly off from the music.",
        section = gameplaySection, position = 6
    )
    default int syncOffsetMs()
    {
        return -100;
    }

    // ---- Controls ----

    @ConfigItem(
        keyName = "requireStrum",
        name = "Require strum",
        description = "Off: tap a fret key in time to play its note. On: hold the fret AND press "
            + "Strum together (true Guitar Hero feel; lets you hold a fret and re-strum repeats).",
        section = controlsSection, position = 0
    )
    default boolean requireStrum()
    {
        return false;
    }

    @ConfigItem(
        keyName = "strumKey",
        name = "Strum",
        description = "Used only when 'Require strum' is on: press in time, with the correct fret held.",
        section = controlsSection, position = 1
    )
    default Keybind strumKey()
    {
        return new Keybind(KeyEvent.VK_SPACE, 0);
    }

    @ConfigItem(keyName = "combatKey", name = "Fret 1 — Combat",
        description = "Combat lane (Law rune).", section = controlsSection, position = 2)
    default Keybind combatKey()
    {
        return Lane.COMBAT.defaultKey;
    }

    @ConfigItem(keyName = "inventoryKey", name = "Fret 2 — Inventory",
        description = "Inventory lane (Death rune).", section = controlsSection, position = 3)
    default Keybind inventoryKey()
    {
        return Lane.INVENTORY.defaultKey;
    }

    @ConfigItem(keyName = "equipmentKey", name = "Fret 3 — Equipment",
        description = "Equipment lane (Blood rune).", section = controlsSection, position = 4)
    default Keybind equipmentKey()
    {
        return Lane.EQUIPMENT.defaultKey;
    }

    @ConfigItem(keyName = "prayerKey", name = "Fret 4 — Prayer",
        description = "Prayer lane (Soul rune).", section = controlsSection, position = 5)
    default Keybind prayerKey()
    {
        return Lane.PRAYER.defaultKey;
    }

    @ConfigItem(keyName = "magicKey", name = "Fret 5 — Magic",
        description = "Magic lane (Wrath rune).", section = controlsSection, position = 6)
    default Keybind magicKey()
    {
        return Lane.MAGIC.defaultKey;
    }

    // ---- Visuals ----

    @ConfigItem(
        keyName = "notePosition",
        name = "Note position",
        description = "Where the notes fall: a board in the centre of the screen, or onto your real "
            + "inventory tabs.",
        section = visualsSection, position = 0
    )
    default NotePosition notePosition()
    {
        return NotePosition.CENTER;
    }

    @Range(min = 80, max = 400)
    @ConfigItem(
        keyName = "fallDistance",
        name = "Fall distance",
        description = "How far (pixels) the notes fall before the hit line. Higher = more lead time.",
        section = visualsSection, position = 1
    )
    default int fallDistance()
    {
        return 220;
    }

    @ConfigItem(
        keyName = "runeIcons",
        name = "Rune note icons",
        description = "Draw notes as their Catalytic rune (Law / Death / Blood / Soul / Wrath). "
            + "Off = plain colored notes.",
        section = visualsSection, position = 2
    )
    default boolean runeIcons()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showJudgment",
        name = "Show judgment",
        description = "Show PERFECT / GOOD / MISS and combo callouts at the hit line.",
        section = visualsSection, position = 3
    )
    default boolean showJudgment()
    {
        return true;
    }

    enum NotePosition
    {
        CENTER("Centre of screen"),
        OVER_TABS("Over inventory tabs");

        private final String label;

        NotePosition(String label)
        {
            this.label = label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }
}
