/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.jspecify.annotations.Nullable;

/**
 * Converts a MIDI {@link Sequence} to VGM using an arbitrary combination of {@link ChipHandler}s.
 *
 * <p>
 * Voice allocation is managed globally across all handlers. FM handlers (YM2413, OPL3) are
 * preferred over PSG handlers (AY-3-8910) because {@link ChipHandlers#create} places them first
 * in the handler list. Voice stealing evicts the last handler's highest-indexed active slot first.
 *
 * <p>
 * MIDI channel 9 (percussion) is routed to the handler with the highest
 * {@link ChipHandler#percussionPriority()} value.
 */
public final class CompositeVgmExporter
{
    private final List<ChipHandler> handlers;
    private final List<ChipType> chipTypes;
    private final RoutingMode mode;

    public CompositeVgmExporter(List<ChipHandler> handlers)
    {
        this(handlers, RoutingMode.SEQUENTIAL);
    }

    public CompositeVgmExporter(List<ChipHandler> handlers, RoutingMode mode)
    {
        this.handlers = List.copyOf(handlers);
        this.chipTypes = handlers.stream().map(ChipHandler::chipType).toList();
        this.mode = mode;
    }

    public void export(Sequence sequence, OutputStream out)
    {
        try (var writer = new VgmWriter(out, chipTypes))
        {
            var events = addMissingPercussionNoteOffs(
                    mergeAndSort(sequence), sequence.getResolution());
            var state = new CompositeState(handlers, mode);
            state.initSilence(writer);

            long prevTick = 0;
            int resolution = sequence.getResolution();
            double ticksPerSample = VgmWriter.ticksPerSample(resolution, 500_000);

            for (var event : events)
            {
                long tick = event.getTick();
                int waitSamples = (int) Math.round((tick - prevTick) / ticksPerSample);
                if (waitSamples > 0)
                    writer.waitSamples(waitSamples);
                prevTick = tick;

                if (event.getMessage() instanceof MetaMessage meta)
                {
                    int usPerBeat = VgmWriter.tempoUsPerBeat(meta);
                    if (usPerBeat > 0)
                        ticksPerSample = VgmWriter.ticksPerSample(resolution, usPerBeat);
                }
                else if (event.getMessage() instanceof ShortMessage msg)
                    state.handleMessage(msg, writer);
            }
            state.finalSilence(writer);
        }
    }

    private static List<MidiEvent> mergeAndSort(Sequence sequence)
    {
        var events = new ArrayList<MidiEvent>();
        for (Track track : sequence.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
                events.add(track.get(i));
        }
        events.sort(Comparator.comparingLong(MidiEvent::getTick));
        return events;
    }

    /**
     * Injects synthetic NOTE_OFF events for MIDI channel 9 (percussion) hits that have no
     * corresponding NOTE_OFF within a short auto-decay window.
     *
     * <p>
     * Many MIDI files omit ch9 NOTE_OFFs because acoustic drum samples decay naturally. PSG noise
     * channels (AY-3-8910) do not decay on their own, so without explicit silencing the noise
     * persists indefinitely. This method adds synthetic NOTE_OFFs so that the noise channel is
     * silenced after {@code resolution / 2} ticks (≈ 1/8 note at the default tempo).
     */
    static List<MidiEvent> addMissingPercussionNoteOffs(List<MidiEvent> events, int resolution)
    {
        int autoDecayTicks = resolution / 2; // ≈ 1/8 note (250 ms at 120 BPM)
        var pendingOns = new HashMap<Integer, Long>(); // note → last unmatched NOTE_ON tick
        var injections = new ArrayList<MidiEvent>();

        for (var event : events)
        {
            if (!(event.getMessage() instanceof ShortMessage msg) || msg.getChannel() != 9)
                continue;
            int note = msg.getData1();
            boolean isOn = msg.getCommand() == ShortMessage.NOTE_ON && msg.getData2() > 0;
            boolean isOff = msg.getCommand() == ShortMessage.NOTE_OFF
                    || (msg.getCommand() == ShortMessage.NOTE_ON && msg.getData2() == 0);

            if (isOn)
            {
                if (pendingOns.containsKey(note))
                {
                    // Previous hit for same note had no NOTE_OFF — inject one just before this hit
                    long onTick = pendingOns.get(note);
                    long offTick = Math.min(onTick + autoDecayTicks, event.getTick());
                    injections.add(percNoteOff(note, offTick));
                }
                pendingOns.put(note, event.getTick());
            }
            else if (isOff)
            {
                pendingOns.remove(note);
            }
        }

        // Remaining pending NOTE_ONs reached end of sequence with no NOTE_OFF
        for (var entry : pendingOns.entrySet())
            injections.add(percNoteOff(entry.getKey(), entry.getValue() + autoDecayTicks));

        if (injections.isEmpty())
            return events;

        var result = new ArrayList<>(events);
        result.addAll(injections);
        result.sort(Comparator.comparingLong(MidiEvent::getTick));
        return result;
    }

    private static MidiEvent percNoteOff(int note, long tick)
    {
        try
        {
            return new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, note, 0), tick);
        }
        catch (InvalidMidiDataException e)
        {
            throw new IllegalStateException("Invalid percussion note: " + note, e);
        }
    }

    // ── Composite voice state ─────────────────────────────────────────────────

    private static final class CompositeState
    {
        private static final int PSG_PREFERRED_PROGRAM_MIN = 112;

        private final List<ChipHandler> handlers;
        private final RoutingMode mode;
        private final int totalSlots;
        private final int[] handlerOffset; // global slot offset for each handler
        private final int[] noteSlot; // MIDI note in each global slot, -1 = free
        private final int[] chanSlot; // MIDI channel for each global slot
        private final boolean[] active; // whether each global slot is playing
        private final int[] program = new int[16]; // per MIDI channel GM program
        private final int[] cc7 = new int[16];    // MIDI CC7 volume (0-127, default 127)
        private final int[] cc11 = new int[16];   // MIDI CC11 expression (0-127, default 127)
        private final int[] pitchBend = new int[16];    // 14-bit pitch bend (0–16383, center 8192)
        private final int[] bendRange = new int[16];    // bend range in semitones (1–24, default 2)
        private final int[] rpnMsb = new int[16];       // RPN MSB accumulator; 0x7F = null RPN
        private final int[] rpnLsb = new int[16];       // RPN LSB accumulator
        private int[] slotVelocity;                     // original unscaled velocity per global slot
        private final boolean[] sustainActive = new boolean[16]; // CC64 sustain pedal state
        private boolean[] sustainedSlots;               // slots held open by sustain pedal
        // CHANNEL mode: handler index assigned to each MIDI channel (-1 = unassigned)
        private final int[] chanHandler = new int[16];
        private int nextHandlerRoundRobin = 0; // round-robin counter for CHANNEL mode
        /** Handler with highest percussionPriority(), or null if none. */
        @Nullable
        private final ChipHandler percussionHandler;

        CompositeState(List<ChipHandler> handlers, RoutingMode mode)
        {
            this.handlers = handlers;
            this.mode = mode;
            percussionHandler = handlers.stream()
                    .max(Comparator.comparingInt(ChipHandler::percussionPriority))
                    .filter(h -> h.percussionPriority() > 0)
                    .orElse(null);
            handlerOffset = new int[handlers.size()];
            int offset = 0;
            for (int i = 0; i < handlers.size(); i++)
            {
                handlerOffset[i] = offset;
                offset += handlers.get(i).slotCount();
            }
            totalSlots = offset;
            noteSlot = new int[totalSlots];
            chanSlot = new int[totalSlots];
            active = new boolean[totalSlots];
            Arrays.fill(noteSlot, -1);
            Arrays.fill(chanSlot, -1);
            Arrays.fill(chanHandler, -1);
            Arrays.fill(cc7, 127);
            Arrays.fill(cc11, 127);
            Arrays.fill(pitchBend, 8192);
            Arrays.fill(bendRange, 2);
            Arrays.fill(rpnMsb, 0x7F);
            Arrays.fill(rpnLsb, 0x7F);
            slotVelocity = new int[totalSlots];
            sustainedSlots = new boolean[totalSlots];
        }

        void initSilence(VgmWriter w)
        {
            for (var handler : handlers)
                handler.initSilence(w);
        }

        void finalSilence(VgmWriter w)
        {
            for (var handler : handlers)
                handler.finalSilence(w);
        }

        void handleMessage(ShortMessage msg, VgmWriter w)
        {
            int status = msg.getCommand();
            int midiCh = msg.getChannel();
            int d1 = msg.getData1();
            int d2 = msg.getData2();

            switch (status)
            {
                case ShortMessage.PROGRAM_CHANGE -> program[midiCh] = d1;
                case ShortMessage.PITCH_BEND ->
                {
                    int bend = (d2 << 7) | d1; // 14-bit: d1=LSB, d2=MSB
                    pitchBend[midiCh] = bend;
                    for (int g = 0; g < totalSlots; g++)
                        if (active[g] && chanSlot[g] == midiCh)
                        {
                            int[] hl = handlerAndLocal(g);
                            handlers.get(hl[0]).updatePitch(
                                    hl[1], noteSlot[g], bend, bendRange[midiCh], w);
                        }
                }
                case ShortMessage.CONTROL_CHANGE ->
                {
                    switch (d1)
                    {
                        case 7 -> { cc7[midiCh] = d2; applyVolumeToChannel(midiCh, w); }
                        case 11 -> { cc11[midiCh] = d2; applyVolumeToChannel(midiCh, w); }
                        case 64 -> handleSustain(midiCh, d2, w);
                        case 100 -> rpnLsb[midiCh] = d2;
                        case 101 -> rpnMsb[midiCh] = d2;
                        case 6 ->
                        {
                            if (rpnMsb[midiCh] == 0 && rpnLsb[midiCh] == 0) // RPN 0 = pitch bend range
                                bendRange[midiCh] = Math.clamp(d2, 1, 24);
                        }
                        default -> { }
                    }
                }
                case ShortMessage.NOTE_ON ->
                {
                    if (d2 > 0)
                        noteOn(midiCh, d1, d2, w);
                    else
                        noteOff(midiCh, d1, w);
                }
                case ShortMessage.NOTE_OFF -> noteOff(midiCh, d1, w);
            }
        }

        private void noteOn(int midiCh, int note, int velocity, VgmWriter w)
        {
            if (midiCh == 9)
            {
                if (percussionHandler != null)
                    percussionHandler.handlePercussion(note, velocity, w);
                return;
            }

            int globalSlot = resolveSlot(midiCh, program[midiCh], w);

            noteSlot[globalSlot] = note;
            chanSlot[globalSlot] = midiCh;
            active[globalSlot] = true;
            slotVelocity[globalSlot] = velocity; // remember for mid-note volume updates

            // Scale velocity by CC7 (volume) and CC11 (expression)
            int effectiveVelocity = (int) ((long) velocity * cc7[midiCh] * cc11[midiCh] / (127L * 127));

            int[] handlerAndLocal = handlerAndLocal(globalSlot);
            handlers.get(handlerAndLocal[0]).startNote(
                    handlerAndLocal[1], note, effectiveVelocity, program[midiCh], w);
            if (pitchBend[midiCh] != 8192)
                handlers.get(handlerAndLocal[0]).updatePitch(
                        handlerAndLocal[1], note, pitchBend[midiCh], bendRange[midiCh], w);
        }

        /**
         * Resolves a global slot for a new note, applying routing strategy and PSG preference.
         * Silences the slot if it was previously active (voice steal).
         */
        private int resolveSlot(int midiCh, int prog, VgmWriter w)
        {
            int globalSlot;

            // Option B: PSG-preferred programs always prefer AY8910 slots
            if (prog >= PSG_PREFERRED_PROGRAM_MIN && hasAy8910())
            {
                globalSlot = findFreeSlotInAy8910();
                if (globalSlot < 0)
                    globalSlot = stealFromAy8910();
            }
            else if (mode == RoutingMode.CHANNEL)
            {
                // Option A: prefer the handler assigned to this MIDI channel
                globalSlot = findFreeSlotForChannel(midiCh);
                if (globalSlot < 0)
                    globalSlot = findFreeSlot();
                if (globalSlot < 0)
                    globalSlot = stealSlot();
            }
            else
            {
                // Option C: fill handlers sequentially
                globalSlot = findFreeSlot();
                if (globalSlot < 0)
                    globalSlot = stealSlot();
            }

            if (active[globalSlot])
                silenceGlobalSlot(globalSlot, w);
            return globalSlot;
        }

        /** Returns true if any handler is an AY8910. */
        private boolean hasAy8910()
        {
            for (var h : handlers)
            {
                if (h.chipType() == ChipType.AY8910)
                    return true;
            }
            return false;
        }

        /** Finds the first free slot in any AY8910 handler; -1 if none. */
        private int findFreeSlotInAy8910()
        {
            for (int hi = 0; hi < handlers.size(); hi++)
            {
                if (handlers.get(hi).chipType() != ChipType.AY8910)
                    continue;
                int base = handlerOffset[hi];
                int count = handlers.get(hi).slotCount();
                for (int local = 0; local < count; local++)
                {
                    if (!active[base + local])
                        return base + local;
                }
            }
            return -1;
        }

        /** Steals the highest-indexed active slot from an AY8910 handler. */
        private int stealFromAy8910()
        {
            for (int hi = handlers.size() - 1; hi >= 0; hi--)
            {
                if (handlers.get(hi).chipType() != ChipType.AY8910)
                    continue;
                int base = handlerOffset[hi];
                int count = handlers.get(hi).slotCount();
                for (int local = count - 1; local >= 0; local--)
                {
                    if (active[base + local])
                        return base + local;
                }
            }
            return stealSlot(); // fallback: no AY8910 active slots found
        }

        /**
         * Finds the first free slot in the handler assigned to {@code midiCh}.
         * Assigns a handler round-robin on first use. Returns -1 if handler is full.
         */
        private int findFreeSlotForChannel(int midiCh)
        {
            if (chanHandler[midiCh] < 0)
                chanHandler[midiCh] = nextHandlerRoundRobin++ % handlers.size();

            int hi = chanHandler[midiCh];
            int base = handlerOffset[hi];
            int count = handlers.get(hi).slotCount();
            for (int local = 0; local < count; local++)
            {
                if (!active[base + local])
                    return base + local;
            }
            return -1;
        }

        private void noteOff(int midiCh, int note, VgmWriter w)
        {
            if (midiCh == 9)
            {
                if (percussionHandler != null)
                    percussionHandler.handlePercussion(note, 0, w);
                return;
            }
            for (int g = 0; g < totalSlots; g++)
            {
                if (active[g] && noteSlot[g] == note && chanSlot[g] == midiCh)
                {
                    if (sustainActive[midiCh])
                    {
                        sustainedSlots[g] = true; // defer note-off until pedal released
                        return;
                    }
                    silenceGlobalSlot(g, w);
                    active[g] = false;
                    noteSlot[g] = -1;
                    chanSlot[g] = -1;
                    return;
                }
            }
        }

        private void silenceGlobalSlot(int globalSlot, VgmWriter w)
        {
            sustainedSlots[globalSlot] = false;
            int[] hl = handlerAndLocal(globalSlot);
            handlers.get(hl[0]).silenceSlot(hl[1], w);
        }

        private void applyVolumeToChannel(int midiCh, VgmWriter w)
        {
            for (int g = 0; g < totalSlots; g++)
                if (active[g] && chanSlot[g] == midiCh)
                {
                    int eff = (int) ((long) slotVelocity[g] * cc7[midiCh] * cc11[midiCh]
                            / (127L * 127));
                    int[] hl = handlerAndLocal(g);
                    handlers.get(hl[0]).updateVolume(hl[1], eff, w);
                }
        }

        private void handleSustain(int midiCh, int value, VgmWriter w)
        {
            boolean active64 = value >= 64;
            if (!active64 && sustainActive[midiCh])
            {
                // Pedal released — send deferred note-offs
                for (int g = 0; g < totalSlots; g++)
                    if (sustainedSlots[g] && chanSlot[g] == midiCh)
                    {
                        silenceGlobalSlot(g, w);
                        active[g] = false;
                        noteSlot[g] = -1;
                        chanSlot[g] = -1;
                    }
            }
            sustainActive[midiCh] = active64;
        }

        private int findFreeSlot()
        {
            for (int g = 0; g < totalSlots; g++)
            {
                if (!active[g])
                    return g;
            }
            return -1;
        }

        private int stealSlot()
        {
            // Steal from the last handler first (typically the lowest-quality chip),
            // highest local slot index within that handler
            for (int hi = handlers.size() - 1; hi >= 0; hi--)
            {
                int base = handlerOffset[hi];
                int count = handlers.get(hi).slotCount();
                for (int local = count - 1; local >= 0; local--)
                {
                    int g = base + local;
                    if (active[g])
                        return g;
                }
            }
            return 0;
        }

        private int[] handlerAndLocal(int globalSlot)
        {
            for (int hi = handlers.size() - 1; hi >= 0; hi--)
            {
                if (globalSlot >= handlerOffset[hi])
                    return new int[] { hi, globalSlot - handlerOffset[hi] };
            }
            return new int[] { 0, globalSlot };
        }
    }
}
