package com.sob.runehero;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Side-load bootstrap for IntelliJ. Run config:
 *   Main class:  com.sob.runehero.RuneHeroPluginTest
 *   Classpath:   rune-hero.test  (NOT .main — the RuneLite client is testImplementation)
 *   VM options:  -ea            (REQUIRED — without it the client exits with no client.log)
 *   Working dir: project root
 */
public class RuneHeroPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(RuneHeroPlugin.class);
        RuneLite.main(args);
    }
}
