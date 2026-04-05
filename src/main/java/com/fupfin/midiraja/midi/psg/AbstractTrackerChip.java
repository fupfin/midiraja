/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.psg;

/**
 * Abstract base for tracker-driven sound chips (PSG, SCC). Provides common channel bookkeeping and
 * shared implementations of note management methods: {@link #updateNote}, {@link #tryStealChannel},
 * {@link #handleNoteOff}, and {@link #forceArpeggioFallback}.
 *
 * <p>
 * Subclasses hold their chip-specific channel arrays and provide the channel accessor/mutator
 * hooks required by the shared algorithms.
 */
abstract class AbstractTrackerChip implements TrackerSynthChip
{
    /**
     * Common channel state shared by all tracker chip types.
     */
    protected static class Channel
    {
        int midiChannel = -1;
        int midiNote = -1;
        boolean active = false;
        long activeFrames = 0;

        int volume15 = 0;
        final int[] arpNotes = new int[4];
        int arpSize = 0;
        int arpIndex = 0;

        double baseFrequency = 0.0;

        void resetCommon()
        {
            active = false;
            volume15 = 0;
            arpSize = 0;
            arpIndex = 0;
            activeFrames = 0;
            midiChannel = -1;
            midiNote = -1;
            baseFrequency = 0.0;
        }
    }

    // --- Abstract accessors over the chip-specific channel array ---

    protected abstract int getNumChannels();

    protected abstract Channel getChannel(int index);

    /**
     * Resets a channel's chip-specific state (frequency accumulators, waveforms, etc.) in addition
     * to the common state already reset by {@link Channel#resetCommon()}.
     */
    protected abstract void resetChannelSpecific(int index);

    /**
     * Called when a new note is activated on a channel so the subclass can update chip-specific
     * frequency accumulators or other state.
     */
    protected abstract void onNoteActivated(int index, int ch, int note, int velocity);

    // --- Shared TrackerSynthChip implementations ---

    @Override
    public boolean updateNote(int ch, int note, int velocity)
    {
        for (int i = 0; i < getNumChannels(); i++)
        {
            Channel c = getChannel(i);
            if (c.active && c.midiChannel == ch)
            {
                if (c.arpSize > 0)
                {
                    for (int j = 0; j < c.arpSize; j++)
                    {
                        if (c.arpNotes[j] == note)
                        {
                            c.volume15 = (int) ((velocity / 127.0) * 15.0);
                            return true;
                        }
                    }
                }
                else if (c.midiNote == note)
                {
                    c.volume15 = (int) ((velocity / 127.0) * 15.0);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean tryStealChannel(int ch, int note, int velocity)
    {
        int targetCh = -1;
        int minVolume = Integer.MAX_VALUE;
        int targetMidiCh = -1;

        for (int i = 0; i < getNumChannels(); i++)
        {
            if (skipChannelForStealing(i))
                continue;
            Channel c = getChannel(i);
            if (c.active && c.midiChannel != 9)
            {
                if (c.volume15 < minVolume
                        || (c.volume15 == minVolume && c.midiChannel > targetMidiCh))
                {
                    minVolume = c.volume15;
                    targetMidiCh = c.midiChannel;
                    targetCh = i;
                }
            }
        }

        int newVol = (int) ((velocity / 127.0) * 15.0);
        if (targetCh != -1 && (minVolume <= newVol || (ch < 4 && targetMidiCh >= 4)))
        {
            Channel c = getChannel(targetCh);
            c.resetCommon();
            resetChannelSpecific(targetCh);
            c.active = true;
            c.midiChannel = ch;
            c.midiNote = note;
            c.volume15 = newVol;
            onNoteActivated(targetCh, ch, note, velocity);
            return true;
        }
        return false;
    }

    /**
     * Returns true if the channel at {@code index} should be excluded from voice stealing.
     * Default: no exclusions. Override to protect specific channels (e.g., PSG noise channel 2).
     */
    protected boolean skipChannelForStealing(int index)
    {
        return false;
    }

    @Override
    public void handleNoteOff(int ch, int note)
    {
        for (int i = 0; i < getNumChannels(); i++)
        {
            Channel c = getChannel(i);
            if (c.active && c.midiChannel == ch)
            {
                if (c.arpSize > 1)
                {
                    int removeIdx = -1;
                    for (int j = 0; j < c.arpSize; j++)
                    {
                        if (c.arpNotes[j] == note)
                        {
                            removeIdx = j;
                            break;
                        }
                    }

                    if (removeIdx != -1)
                    {
                        for (int j = removeIdx; j < c.arpSize - 1; j++)
                        {
                            c.arpNotes[j] = c.arpNotes[j + 1];
                        }
                        c.arpSize--;

                        if (c.arpIndex >= c.arpSize)
                        {
                            c.arpIndex = 0;
                        }
                    }
                }
                else if (c.midiNote == note)
                {
                    c.active = false;
                    onChannelDeactivated(i);
                }
            }
        }
    }

    /**
     * Called when a channel is deactivated via note-off. Default: no-op. Override to update
     * chip-specific state (e.g., PSG hardware envelope).
     */
    protected void onChannelDeactivated(int index)
    {
    }

    @Override
    public void forceArpeggioFallback(int ch, int note, int velocity)
    {
        int targetCh = findArpeggioTarget(ch);

        if (targetCh == -1)
        {
            targetCh = getArpeggioFallbackChannel();
            Channel c = getChannel(targetCh);
            c.resetCommon();
            resetChannelSpecific(targetCh);
            c.active = true;
            c.midiChannel = ch;
            c.midiNote = note;
            c.volume15 = (int) ((velocity / 127.0) * 15.0);
            c.arpNotes[0] = note;
            c.arpSize = 1;
            onNoteActivated(targetCh, ch, note, velocity);
            return;
        }

        Channel c = getChannel(targetCh);

        if (c.arpSize == 0 && c.active)
        {
            c.arpNotes[0] = c.midiNote;
            c.arpSize = 1;
        }

        boolean exists = false;
        for (int i = 0; i < c.arpSize; i++)
        {
            if (c.arpNotes[i] == note)
                exists = true;
        }

        if (!exists)
        {
            c.arpNotes[c.arpSize++] = note;
            c.volume15 = Math.max(c.volume15, (int) ((velocity / 127.0) * 15.0));
        }
    }

    /**
     * Finds the first channel already playing {@code ch} that has room in its arpeggio buffer.
     * Returns -1 if none found.
     */
    private int findArpeggioTarget(int ch)
    {
        for (int i = 0; i < getNumChannels(); i++)
        {
            if (skipChannelForArpeggio(i))
                continue;
            Channel c = getChannel(i);
            if (c.active && c.midiChannel == ch && c.arpSize < 4)
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns true if the channel at {@code index} should be excluded from arpeggio search.
     * Default: no exclusions. Override to skip noise-only channels (e.g., PSG channel 2).
     */
    protected boolean skipChannelForArpeggio(int index)
    {
        return false;
    }

    /**
     * Returns the index of the fallback channel for arpeggio hard-steal when no existing channel
     * has room.
     */
    protected abstract int getArpeggioFallbackChannel();
}
