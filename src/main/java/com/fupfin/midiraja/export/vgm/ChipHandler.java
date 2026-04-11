/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * Strategy interface for a single chip's voice allocation and register programming.
 *
 * <p>
 * Each implementation handles one physical chip (e.g. one AY-3-8910, one YM2413).
 * Slot indices passed to {@link #silenceSlot} are <em>local</em> (0-based within this handler);
 * the {@link CompositeVgmExporter} performs the global↔local translation.
 */
interface ChipHandler
{
    /** Returns the chip type this handler manages. */
    ChipType chipType();

    /** Number of independent voices this chip provides. */
    int slotCount();

    /**
     * Returns {@code true} if this handler can process MIDI channel 9 (percussion).
     * When multiple handlers support rhythm, the first one in the handler list wins.
     */
    boolean supportsRhythm();

    /** Writes initial silence (key-off / mute) state for all chip registers. */
    void initSilence(VgmWriter w);

    /**
     * Handles a non-percussion MIDI message (NOTE_ON, NOTE_OFF, PROGRAM_CHANGE).
     *
     * @param msg
     *            the MIDI short message
     * @param program
     *            current GM program number for the MIDI channel (0-127)
     * @param localSlot
     *            the local slot index to use for NOTE_ON voice allocation (ignored for NOTE_OFF
     *            and PROGRAM_CHANGE — the composite resolves those)
     * @param w
     *            the VGM writer
     */
    void startNote(int localSlot, int note, int velocity, int program, VgmWriter w);

    /** Silences the given local slot (key-off or amplitude zero). */
    void silenceSlot(int localSlot, VgmWriter w);

    /**
     * Triggers a percussion hit.  Only called when {@link #supportsRhythm()} is {@code true}.
     *
     * @param note
     *            GM percussion note number
     * @param velocity
     *            MIDI velocity (1-127)
     * @param w
     *            the VGM writer
     */
    void handlePercussion(int note, int velocity, VgmWriter w);
}
