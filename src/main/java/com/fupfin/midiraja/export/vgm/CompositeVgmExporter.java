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
import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Converts a MIDI {@link Sequence} to VGM using an arbitrary combination of {@link ChipHandler}s.
 *
 * <p>
 * Voice allocation is managed globally across all handlers. FM handlers (YM2413, OPL3) are
 * preferred over PSG handlers (AY-3-8910) because {@link ChipHandlers#create} places them first
 * in the handler list. Voice stealing evicts the last handler's highest-indexed active slot first.
 *
 * <p>
 * MIDI channel 9 (percussion) is routed to the first handler that reports
 * {@link ChipHandler#supportsRhythm()}.
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
            var events = mergeAndSort(sequence);
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
        // CHANNEL mode: handler index assigned to each MIDI channel (-1 = unassigned)
        private final int[] chanHandler = new int[16];
        private int nextHandlerRoundRobin = 0; // round-robin counter for CHANNEL mode

        CompositeState(List<ChipHandler> handlers, RoutingMode mode)
        {
            this.handlers = handlers;
            this.mode = mode;
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
        }

        void initSilence(VgmWriter w)
        {
            for (var handler : handlers)
                handler.initSilence(w);
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
                // Route percussion to first handler that supports rhythm
                for (var handler : handlers)
                {
                    if (handler.supportsRhythm())
                    {
                        handler.handlePercussion(note, velocity, w);
                        return;
                    }
                }
                return;
            }

            int globalSlot = resolveSlot(midiCh, program[midiCh], w);

            noteSlot[globalSlot] = note;
            chanSlot[globalSlot] = midiCh;
            active[globalSlot] = true;

            int[] handlerAndLocal = handlerAndLocal(globalSlot);
            handlers.get(handlerAndLocal[0]).startNote(
                    handlerAndLocal[1], note, velocity, program[midiCh], w);
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
            for (int g = 0; g < totalSlots; g++)
            {
                if (active[g] && noteSlot[g] == note && chanSlot[g] == midiCh)
                {
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
            int[] hl = handlerAndLocal(globalSlot);
            handlers.get(hl[0]).silenceSlot(hl[1], w);
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
