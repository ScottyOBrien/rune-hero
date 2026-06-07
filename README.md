# RuneHero

A Guitar Hero-style rhythm minigame played on your Old School RuneScape inventory tabs.

Install from the RuneLite Plugin Hub, then enable **RuneHero**.

## How it works

Notes — drawn as the five Catalytic runes (Law, Death, Blood, Soul, Wrath) — fall toward five
lanes: Combat, Inventory, Equipment, Prayer, Magic. When a note reaches the hit line, **tap that
lane's key** (or, if you turn on *Require strum*, hold the lane key and press strum). Hits flash
green/gold, misses flash red, and your combo glows hotter the longer your streak runs.

Charts are generated automatically from the in-game music track playing when you press start.
The melody is reduced to a single playable line (you can only be in one lane at a time, so no
chords). **Smart** mode auto-picks the 1–3 instruments that best carry the tune; or set **Channel
A/B/C** by hand — press the **Analyze** key (default F11) to see each channel's instrument so you
can pick the melody yourself.

Pick **Easy / Medium / Hard / Expert** to scale lane count, note density, fall speed, and timing
window. Notes can fall onto your real inventory tabs, or onto a free-floating rune board in the
middle of the screen (**Note position** setting).

### Starting a song

OSRS doesn't expose how far into a track it is, so to line the notes up with the music: press
**Start** (default F12) to *arm*, then toggle Music **off then on** in the game settings. RuneHero
snaps the chart to that fresh restart. Use **Sync offset** to fine-tune. (The plugin runs
on its own clock from there — the music you hear is just background.)

## Data collection

None. RuneHero reads music data from your local OSRS cache and runs entirely on your machine.

## License

BSD-2-Clause. The vendored MIDI track loader (`vendored/`) is derived from RuneLite
(Copyright 2016-2017, Adam) under the same license.
