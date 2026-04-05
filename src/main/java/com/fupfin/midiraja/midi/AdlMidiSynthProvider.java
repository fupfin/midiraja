/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.dsp.AudioProcessor;

/**
 * SoftSynthProvider backed by libADLMIDI (OPL2/OPL3 FM synthesis).
 */
public class AdlMidiSynthProvider extends AbstractSoftSynthProvider<AdlMidiNativeBridge>
{
    private final int emulatorId;
    private final int numChips;
    private final @Nullable String dacMode;

    public AdlMidiSynthProvider(AdlMidiNativeBridge bridge, @Nullable AudioProcessor audioOut,
            int emulatorId, int numChips, @Nullable String dacMode)
    {
        super(bridge, audioOut);
        this.emulatorId = emulatorId;
        this.numChips = numChips;
        this.dacMode = dacMode;
    }

    @Override
    public long getAudioLatencyNanos()
    {
        return 4096L * 1_000_000_000L / SAMPLE_RATE;
    }

    private static final String[] CHIP_NAMES = { "OPL3", "OPL3", "OPL3", "OPL3", "OPL3",
            "ESFM", "OPL2", "OPL2", "OPL3" };
    private static final String[] EMULATOR_NAMES = { "Nuked OPL3 v1.8", "Nuked OPL3 v1.7.4",
            "DosBox", "Opal", "Java", "ESFMu", "MAME OPL2", "YMFM OPL2", "YMFM OPL3" };

    @Override
    public List<MidiPort> getOutputPorts()
    {
        return buildFmSynthPorts(CHIP_NAMES, EMULATOR_NAMES, emulatorId, numChips, dacMode);
    }

    /** OPL output is relatively quiet; boost to match reference level. */
    @Override
    protected float calibrationGain()
    {
        return 1.78f;
    }

    @Override
    public void openPort(int portIndex) throws Exception
    {
        bridge.init(SAMPLE_RATE);
        bridge.switchEmulator(emulatorId);
        bridge.setNumChips(numChips);
    }

    @Override
    public void loadSoundbank(String path) throws Exception
    {
        if (path.startsWith("bank:"))
        {
            bridge.setBank(Integer.parseInt(path.substring(5)));
        }
        else
        {
            bridge.loadBankFile(path);
        }

        if (audioOut != null)
        {
            startRenderThread("AdlMidiRenderThread");
        }
    }
}
