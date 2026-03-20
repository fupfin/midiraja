/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import com.fupfin.midiraja.dsp.AudioProcessor;
import java.util.List;
import javax.sound.midi.Sequence;
import org.jspecify.annotations.Nullable;

/**
 * SoftSynthProvider backed by TinySoundFont (SF2/SF3 SoundFont synthesis).
 */
public class TsfSynthProvider extends AbstractSoftSynthProvider<TsfNativeBridge>
{
    private final @Nullable String retroMode;
    private String portName = "SoundFont";

    public TsfSynthProvider(TsfNativeBridge bridge, @Nullable AudioProcessor audioOut,
            @Nullable String retroMode)
    {
        super(bridge, audioOut);
        this.retroMode = retroMode;
    }

    @Override
    public long getAudioLatencyNanos()
    {
        return 4096L * 1_000_000_000L / SAMPLE_RATE;
    }

    @Override
    public List<MidiPort> getOutputPorts()
    {
        return List.of(new MidiPort(0, portName));
    }

    /** FluidR3 GM SF3 is mastered loud; attenuate to match reference level. */
    @Override
    protected float calibrationGain()
    {
        return 0.60f;
    }

    @Override
    public void openPort(int portIndex)
    {
        // openPort is a no-op for TSF; device creation happens in loadSoundbank
    }

    /**
     * After {@link #loadSoundbank} is called, {@code tsf_reset()} in
     * {@link AbstractSoftSynthProvider#prepareForNewTrack} sets {@code f->channels = NULL}.
     * {@code tsf_channel_note_on()} is a silent no-op while {@code f->channels} is NULL, so every
     * NoteOn event is dropped until a CC event triggers the lazy {@code tsf_channel_init()}.
     *
     * <p>We fix this by queuing CC121 (Reset All Controllers) on all 16 channels immediately after
     * the super call. The render thread will process them before the first NoteOn from the MIDI
     * file, ensuring {@code f->channels} is initialised at full volume.
     */
    @Override
    public void prepareForNewTrack(Sequence sequence)
    {
        super.prepareForNewTrack(sequence);
        for (int ch = 0; ch < 16; ch++)
        {
            eventQueue.offer(new byte[] {(byte) (0xB0 | ch), 121, 0});
        }
    }

    @Override
    public void loadSoundbank(String path) throws Exception
    {
        bridge.loadSoundfontFile(path, SAMPLE_RATE);
        String name = new java.io.File(path).getName();
        portName = "SoundFont (" + name + ")" + AbstractSoftSynthProvider.retroTag(retroMode);

        if (audioOut != null)
        {
            startRenderThread("TsfRenderThread");
        }
    }
}
