package com.sob.runehero;

/** Per-channel summary for the instrument readout: which instrument, how many notes, what register. */
final class ChannelInfo
{
    final int channel;
    final int program;   // GM instrument number, or -1 if unknown
    final int noteCount;
    final int minPitch;
    final int maxPitch;
    final double avgPitch;

    ChannelInfo(int channel, int program, int noteCount, int minPitch, int maxPitch, double avgPitch)
    {
        this.channel = channel;
        this.program = program;
        this.noteCount = noteCount;
        this.minPitch = minPitch;
        this.maxPitch = maxPitch;
        this.avgPitch = avgPitch;
    }

    boolean isDrums()
    {
        return channel == 9;
    }
}
