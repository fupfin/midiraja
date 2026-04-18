/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.IOException;
import java.util.Arrays;

/**
 * {@link ChipHandler} for Ricoh RF5C68 PCM — percussion-only chip.
 *
 * <p>
 * The RF5C68 is an 8-channel 8-bit sign-magnitude PCM chip (12.5 MHz clock) used in the FM Towns.
 * Seven synthetic drum samples are packed into a single RF5C68 RAM data block (type 0xC0,
 * 16-bit addressing) at {@link #initSilence} time. Each channel (0–6) is assigned one drum type and configured
 * once via direct register writes. Triggering a drum disables then re-enables its channel, which
 * resets the playback address to the sample start and restarts playback cleanly.
 *
 * <p>
 * RF5C68 sign-magnitude encoding: bit 7 = sign (1 = positive), bits 6–0 = magnitude.
 * The byte value {@code 0xFF} is the hardware end-of-sample marker and must not appear as audio.
 * After the marker, the chip jumps to the loop-start address; all channels point to a two-byte
 * silent-loop stub ({@code [0x80, 0xFF]}), giving one-shot playback with silent looping.
 */
final class Rf5c68Handler implements ChipHandler
{

    /**
     * Frequency step register value for playback at {@link VgmWriter#RF5C68_SAMPLE_RATE} Hz.
     *
     * <p>
     * RF5C68 output rate = clock / 384 Hz. The 32-bit addr accumulator advances by {@code step}
     * per output sample; byte address = addr >> 11. So:
     * {@code step = sampleRate * 2048 * 384 / clock}.
     */
    private static final int FREQ_STEP = (int) Math.round(
            VgmWriter.RF5C68_SAMPLE_RATE * 2048.0 * 384.0 / VgmWriter.RF5C68_CLOCK);

    /**
     * GM percussion note → drum channel index (0–6), or −1 if not mapped.
     * Array is indexed by MIDI note number (0–127).
     */
    private static final int[] NOTE_TO_DRUM = buildNoteMap();

    /**
     * Sign-magnitude PCM drum samples loaded from classpath resources.
     * Each {@code rf5c68_drum_N.bin} was extracted from FluidR3_GM.sf3 at build time
     * and resampled/encoded to RF5C68 sign-magnitude 8-bit at {@link VgmWriter#RF5C68_SAMPLE_RATE}.
     */
    private static final byte[][] DRUM_SAMPLES = loadDrumSamples();

    /**
     * Start register value for each drum channel.
     * RF5C68 start register (reg 0x06) = byte-address-in-wave-RAM / 256.
     */
    private static final int[] DRUM_START_REGS;

    /** Byte address of the two-byte silent-loop stub at the end of WAVE_RAM. */
    private static final int SILENT_LOOP_ADDR;

    /** Complete wave RAM contents uploaded via a single PCM data block at init time. */
    private static final byte[] WAVE_RAM;

    static
    {
        // ── Compute 256-byte-aligned layout ──────────────────────────────────
        // ST register encodes start address as (reg * 256), so each sample must
        // begin on a 256-byte boundary.  Pad each sample+terminator to that boundary.
        int[] paddedSizes = new int[DRUM_SAMPLES.length];
        DRUM_START_REGS = new int[DRUM_SAMPLES.length];
        int offset = 0;
        for (int i = 0; i < DRUM_SAMPLES.length; i++)
        {
            DRUM_START_REGS[i] = offset / 256;
            // +1 for the 0xFF end-of-sample terminator
            paddedSizes[i] = ((DRUM_SAMPLES[i].length + 1 + 255) / 256) * 256;
            offset += paddedSizes[i];
        }
        SILENT_LOOP_ADDR = offset;

        // ── Build flat wave RAM byte array ────────────────────────────────────
        byte[] ram = new byte[offset + 2]; // +2: silent stub [0x80][0xFF]
        offset = 0;
        for (int i = 0; i < DRUM_SAMPLES.length; i++)
        {
            System.arraycopy(DRUM_SAMPLES[i], 0, ram, offset, DRUM_SAMPLES[i].length);
            ram[offset + DRUM_SAMPLES[i].length] = (byte) 0xFF; // end-of-sample marker
            // remaining padding bytes stay 0x00 (negative zero = silence)
            offset += paddedSizes[i];
        }
        // Silent-loop stub: one silent sample followed by end marker (loops back to itself)
        ram[offset]     = (byte) 0x80; // +0 in sign-magnitude = silence
        ram[offset + 1] = (byte) 0xFF; // end marker → jumps back to loop-start address
        WAVE_RAM = ram;
    }

    /**
     * Current state of the RF5C68 channel-enable register (reg 0x08).
     * Bit N = 0 → channel N enabled; bit N = 1 → channel N disabled (inverted logic).
     * Starts fully disabled (0xFF).
     */
    private int activeChannels = 0xFF;

    // ── ChipHandler ───────────────────────────────────────────────────────────

    @Override
    public ChipType chipType()
    {
        return ChipType.RF5C68;
    }

    @Override
    public int slotCount()
    {
        return 0; // percussion-only chip; no melodic slots
    }

    @Override
    public int percussionPriority()
    {
        return 3; // PCM > FM (2) > PSG noise (1)
    }

    /**
     * Loads all 7 drum samples into RF5C68 wave RAM, enables the chip, and configures
     * each channel (ENV, PAN, frequency step, loop address, start address).
     */
    @Override
    public void initSilence(VgmWriter w)
    {
        // Upload entire wave RAM contents (samples + silent-loop stub) starting at address 0
        w.writeRf5c68Ram(0, WAVE_RAM);

        // Enable chip: reg 0x07, bit 7 = 1; bit 6 = 0 → wbank-select mode, wbank = 0
        w.writeRf5c68(0x07, 0x80);

        // Disable all 8 channels (inverted: all bits set = all disabled)
        activeChannels = 0xFF;
        w.writeRf5c68(0x08, 0xFF);

        int silentLo = SILENT_LOOP_ADDR & 0xFF;
        int silentHi = (SILENT_LOOP_ADDR >> 8) & 0xFF;

        for (int ch = 0; ch < DRUM_SAMPLES.length; ch++)
        {
            // Select channel ch: bit 7 = chip-enable, bit 6 = 1 → channel-select mode
            w.writeRf5c68(0x07, 0xC0 | ch);
            w.writeRf5c68(0x00, 0xFF);                        // ENV = max
            w.writeRf5c68(0x01, 0xFF);                        // PAN = both L+R
            w.writeRf5c68(0x02, FREQ_STEP & 0xFF);            // FDL
            w.writeRf5c68(0x03, (FREQ_STEP >> 8) & 0xFF);     // FDH
            w.writeRf5c68(0x04, silentLo);                    // LSL (loop → silent stub)
            w.writeRf5c68(0x05, silentHi);                    // LSH
            // ST: writing while channel is disabled resets addr to start (rf5c68 hw behaviour)
            w.writeRf5c68(0x06, DRUM_START_REGS[ch]);         // ST
        }
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        // No melodic slots — never called
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        // No melodic slots — never called
    }

    /**
     * Triggers or stops a drum hit on the channel mapped to {@code note}.
     *
     * <p>
     * On note-on: disable the channel (which resets its playback address to the sample start),
     * then immediately re-enable it to begin playback from the beginning. This allows clean
     * retriggering even while the sample is still playing.
     *
     * <p>
     * On note-off (velocity = 0): disable the channel to cut the sample.
     */
    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        int drumIdx = note >= 0 && note < NOTE_TO_DRUM.length ? NOTE_TO_DRUM[note] : -1;
        if (drumIdx < 0)
            return; // unmapped GM percussion note — ignore

        if (velocity == 0)
        {
            // Note-off: disable channel to stop playback
            activeChannels |= (1 << drumIdx);
            w.writeRf5c68(0x08, activeChannels & 0xFF);
            return;
        }

        // Retrigger sequence: disable → (addr resets to start) → re-enable → playback starts
        w.writeRf5c68(0x08, (activeChannels | (1 << drumIdx)) & 0xFF);
        activeChannels &= ~(1 << drumIdx);
        w.writeRf5c68(0x08, activeChannels & 0xFF);
    }

    @Override
    public void finalSilence(VgmWriter w)
    {
        activeChannels = 0xFF;
        w.writeRf5c68(0x08, 0xFF); // disable all channels
    }

    // ── Note map builder ──────────────────────────────────────────────────────

    private static int[] buildNoteMap()
    {
        int[] map = new int[128];
        Arrays.fill(map, -1);

        // 0 = Bass drum (GM 35, 36)
        map[35] = 0;
        map[36] = 0;

        // 1 = Snare (GM 38, 40)
        map[38] = 1;
        map[40] = 1;

        // 2 = Crash / cymbal (GM 49, 51, 52, 53, 55, 57, 59)
        for (int n : new int[] { 49, 51, 52, 53, 55, 57, 59 })
            map[n] = 2;

        // 3 = Closed hi-hat (GM 42, 44)
        map[42] = 3;
        map[44] = 3;

        // 4 = Tom (GM 41, 43, 45, 47, 48, 50)
        for (int n : new int[] { 41, 43, 45, 47, 48, 50 })
            map[n] = 4;

        // 5 = Rim shot (GM 37, 39)
        map[37] = 5;
        map[39] = 5;

        // 6 = Open hi-hat (GM 46)
        map[46] = 6;

        return map;
    }

    // ── Resource loader ───────────────────────────────────────────────────────

    /**
     * Loads 7 RF5C68 drum samples from classpath resources.
     * Resources are generated at build time from FluidR3_GM.sf3 by
     * {@code scripts/extract_drum_samples.py}.
     */
    private static byte[][] loadDrumSamples()
    {
        byte[][] samples = new byte[7][];
        for (int i = 0; i < 7; i++)
        {
            String name = "rf5c68_drum_" + i + ".bin";
            try (var in = Rf5c68Handler.class.getResourceAsStream(name))
            {
                if (in == null)
                    throw new IllegalStateException(name + " not found in classpath;"
                            + " run: python3 scripts/extract_drum_samples.py");
                samples[i] = in.readAllBytes();
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Failed to load " + name, e);
            }
        }
        return samples;
    }
}
