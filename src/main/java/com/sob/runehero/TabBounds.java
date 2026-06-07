package com.sob.runehero;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Comparator;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Resolves the on-screen rectangle of each of the 7 main tab buttons (Combat .. Magic)
 * for whichever client layout is active. Used ONLY to know where to draw the falling-note
 * lanes — never to read which tab is selected.
 *
 * STONE0..STONE6 are the 7 main tab "stones" in every desktop layout. We resolve all 7 and
 * sort them by canvas-X so lane 0 is always the leftmost icon, regardless of how the client
 * orders the stones internally or which layout is in use.
 */
@Singleton
final class TabBounds
{
    /** The 7 main tabs (Combat..Magic). resolve() returns this many rects, indexed by screen-X. */
    static final int MAIN_TAB_COUNT = 7;

    private static final int[] FIXED = {
        InterfaceID.Toplevel.STONE0, InterfaceID.Toplevel.STONE1, InterfaceID.Toplevel.STONE2,
        InterfaceID.Toplevel.STONE3, InterfaceID.Toplevel.STONE4, InterfaceID.Toplevel.STONE5,
        InterfaceID.Toplevel.STONE6,
    };
    private static final int[] RESIZABLE_CLASSIC = {
        InterfaceID.ToplevelOsrsStretch.STONE0, InterfaceID.ToplevelOsrsStretch.STONE1,
        InterfaceID.ToplevelOsrsStretch.STONE2, InterfaceID.ToplevelOsrsStretch.STONE3,
        InterfaceID.ToplevelOsrsStretch.STONE4, InterfaceID.ToplevelOsrsStretch.STONE5,
        InterfaceID.ToplevelOsrsStretch.STONE6,
    };
    private static final int[] RESIZABLE_MODERN = {
        InterfaceID.ToplevelPreEoc.STONE0, InterfaceID.ToplevelPreEoc.STONE1,
        InterfaceID.ToplevelPreEoc.STONE2, InterfaceID.ToplevelPreEoc.STONE3,
        InterfaceID.ToplevelPreEoc.STONE4, InterfaceID.ToplevelPreEoc.STONE5,
        InterfaceID.ToplevelPreEoc.STONE6,
    };
    private static final int[][] LAYOUTS = {FIXED, RESIZABLE_MODERN, RESIZABLE_CLASSIC};

    private final Client client;

    @Inject
    TabBounds(Client client)
    {
        this.client = client;
    }

    /**
     * @return 7 rectangles indexed by lane (left to right), or null if the tab row
     *         isn't currently visible. Call from the client thread (e.g. overlay render).
     */
    Rectangle[] resolve()
    {
        for (int[] layout : LAYOUTS)
        {
            Rectangle[] r = tryLayout(layout);
            if (r != null)
            {
                return r;
            }
        }
        return null;
    }

    private Rectangle[] tryLayout(int[] ids)
    {
        Widget[] ws = new Widget[ids.length];
        for (int i = 0; i < ids.length; i++)
        {
            int id = ids[i];
            Widget w = client.getWidget(id >>> 16, id & 0xFFFF);
            if (w == null || w.isHidden())
            {
                return null;
            }
            ws[i] = w;
        }
        Arrays.sort(ws, Comparator
            .comparingInt((Widget w) -> w.getBounds().x)
            .thenComparingInt(w -> w.getBounds().y));
        Rectangle[] out = new Rectangle[ws.length];
        for (int i = 0; i < ws.length; i++)
        {
            out[i] = ws[i].getBounds();
        }
        return out;
    }
}
