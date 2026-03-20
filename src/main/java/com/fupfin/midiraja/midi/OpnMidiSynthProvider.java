/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import com.fupfin.midiraja.dsp.AudioProcessor;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * SoftSynthProvider backed by libOPNMIDI (OPN2/OPNA FM synthesis).
 */
public class OpnMidiSynthProvider extends AbstractSoftSynthProvider<OpnMidiNativeBridge>
{
    private final int emulatorId;
    private final int numChips;
    private final @Nullable String dacMode;

    public OpnMidiSynthProvider(OpnMidiNativeBridge bridge, @Nullable AudioProcessor audioOut,
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

    private static final String[] CHIP_NAMES     = {"YM2612", "YM3438", "YM2612",
            "OPN2", "OPNA", "YM2608", "OPNA"};
    private static final String[] EMULATOR_NAMES = {"MAME YM2612", "Nuked YM3438", "GENS",
            "YMFM OPN2", "NP2 OPNA", "MAME YM2608", "YMFM OPNA"};

    @Override
    public List<MidiPort> getOutputPorts()
    {
        return buildFmSynthPorts(CHIP_NAMES, EMULATOR_NAMES, emulatorId, numChips, dacMode);
    }

    /** OPN output is slightly quiet; boost to match reference level. */
    @Override
    protected float calibrationGain()
    {
        return 1.15f;
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
        if (path.isEmpty())
        {
            try (var stream = OpnMidiSynthProvider.class
                    .getResourceAsStream("/com/midiraja/midi/opn-gm.wopn"))
            {
                if (stream == null)
                    throw new Exception("Built-in OPN2 GM bank not found in resources");
                bridge.loadBankData(stream.readAllBytes());
            }
        }
        else
        {
            bridge.loadBankFile(path);
        }

        if (audioOut != null)
        {
            startRenderThread("OpnMidiRenderThread");
        }
    }
}
