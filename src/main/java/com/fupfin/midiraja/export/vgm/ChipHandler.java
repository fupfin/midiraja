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
     * Returns the percussion quality level for this handler.
     *
     * <ul>
     * <li>0 – no percussion support (e.g. SCC wave channel)</li>
     * <li>1 – PSG noise channel (low quality, e.g. AY-3-8910, SN76489)</li>
     * <li>2 – FM synthesis percussion (good quality, e.g. YM2413 rhythm, OPL3, YM2612)</li>
     * <li>3 – PCM sample playback (excellent quality, reserved for future use)</li>
     * </ul>
     *
     * When multiple handlers have the same maximum priority, the first one in the handler list wins.
     */
    int percussionPriority();

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
     * Triggers a percussion hit.  Only called when {@link #percussionPriority()} {@code > 0}.
     * Handlers that return {@code 0} from {@link #percussionPriority()} may rely on this default
     * no-op.
     *
     * @param note
     *            GM percussion note number
     * @param velocity
     *            MIDI velocity (1-127)
     * @param w
     *            the VGM writer
     */
    default void handlePercussion(int note, int velocity, VgmWriter w)
    {
    }

    /**
     * Writes a final all-notes-off for every output this chip controls, including any percussion
     * channel not managed by the melody slot pool.  Called once after all MIDI events have been
     * processed so that notes with missing NOTE_OFF events do not sustain into silence.
     *
     * <p>
     * The default implementation calls {@link #silenceSlot} for every melody slot and, for handlers
     * that support rhythm, calls {@link #handlePercussion handlePercussion(0, 0, w)} to silence the
     * percussion channel.  Override when a handler owns registers outside the melody slot pool that
     * need explicit zeroing (e.g. the AY-3-8910 noise channel).
     */
    default void finalSilence(VgmWriter w)
    {
        for (int slot = 0; slot < slotCount(); slot++)
            silenceSlot(slot, w);
        if (percussionPriority() > 0)
            handlePercussion(0, 0, w);
    }
}
