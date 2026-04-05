/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.lang.Math.*;

import com.fupfin.midiraja.engine.PlaybackPipeline;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.SysexFilter;
import com.fupfin.midiraja.midi.TransposeFilter;
import com.fupfin.midiraja.midi.VolumeFilter;

/** Standard pipeline: TransposeFilter → VolumeFilter → SysexFilter → MidiOutProvider. */
final class StandardPlaybackPipeline implements PlaybackPipeline
{
    private final MidiOutProvider provider;
    private final TransposeFilter transposeFilter;
    private final VolumeFilter volumeFilter;
    private final SysexFilter sysexFilter;

    StandardPlaybackPipeline(MidiOutProvider provider, int initialVolumePercent,
            int initialTranspose)
    {
        this.provider = provider;
        double initVol = provider.outputGain().isPresent()
                ? 1.0
                : max(0, min(100, initialVolumePercent)) / 100.0;
        this.sysexFilter = new SysexFilter(provider, false);
        this.volumeFilter = new VolumeFilter(this.sysexFilter, initVol);
        this.transposeFilter = new TransposeFilter(this.volumeFilter, initialTranspose);
    }

    @Override
    public void sendMessage(byte[] data) throws Exception
    {
        transposeFilter.sendMessage(data);
    }

    @Override
    public void adjustVolume(double delta)
    {
        var og = provider.outputGain();
        if (og.isPresent())
        {
            double newScale = max(0.0, min(1.5, og.get().getVolumeScale() + delta));
            og.get().setVolumeScale((float) newScale);
        }
        else
        {
            volumeFilter.adjust(delta);
            // Re-send CC7 on all channels so the hardware synth tracks the new volume
            for (int ch = 0; ch < 16; ch++)
            {
                byte[] msg = new byte[] { (byte) (0xB0 | ch), 7, (byte) 100 };
                try
                {
                    transposeFilter.sendMessage(msg);
                }
                catch (Exception _)
                {
                    /* best-effort */
                }
            }
        }
    }

    @Override
    public double getVolumeScale()
    {
        return provider.outputGain()
                .map(g -> (double) g.getVolumeScale())
                .orElseGet(() -> volumeFilter.getVolumeScale());
    }

    @Override
    public void adjustTranspose(int semitones)
    {
        transposeFilter.adjust(semitones);
        try
        {
            provider.panic();
        }
        catch (Exception _)
        {
            /* best-effort: silence notes at old pitch */
        }
    }

    @Override
    public int getCurrentTranspose()
    {
        return transposeFilter.getSemitones();
    }

    @Override
    public void setIgnoreSysex(boolean ignore)
    {
        sysexFilter.setIgnoreSysex(ignore);
    }
}
