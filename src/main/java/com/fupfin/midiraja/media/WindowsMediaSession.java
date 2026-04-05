/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.media;

import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.midi.AbstractFFMBridge;

/**
 * Windows media key integration via SystemMediaTransportControls (SMTC).
 * Requires Windows 10+ and midiraja_mediakeys.dll.
 *
 * <p>
 * Call sequence: {@code start()} → any number of {@code drainAndUpdate()} → {@code close()}.
 * All methods are safe to call out of order or multiple times.
 *
 * <p>
 * Native commands are enqueued from the native callback thread and drained on the caller's
 * thread (the engine playback thread) in {@link #drainAndUpdate}.
 */
public final class WindowsMediaSession implements MediaKeyIntegration
{
    private static final Logger log = Logger.getLogger(WindowsMediaSession.class.getName());

    // ── FunctionDescriptors for NativeMetadataConsistencyTest ────────────────

    static final FunctionDescriptor DESC_START = FunctionDescriptor.ofVoid(ADDRESS);
    static final FunctionDescriptor DESC_UPDATE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG,
            JAVA_INT);
    static final FunctionDescriptor DESC_STOP = FunctionDescriptor.ofVoid();
    static final FunctionDescriptor DESC_UPCALL = FunctionDescriptor.ofVoid(JAVA_INT);

    /** Returns all downcall descriptors for {@code NativeMetadataConsistencyTest}. */
    public static List<FunctionDescriptor> allDowncallDescriptors()
    {
        return List.of(DESC_START, DESC_UPDATE, DESC_STOP);
    }

    /** Returns all upcall descriptors for {@code NativeMetadataConsistencyTest}. */
    public static List<FunctionDescriptor> allUpcallDescriptors()
    {
        return List.of(DESC_UPCALL);
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Arena arena;
    private final MethodHandle smtcStart;
    private final MethodHandle smtcUpdate;
    private final MethodHandle smtcStop;

    private final ConcurrentLinkedQueue<Integer> pendingCommands = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile @Nullable PlaybackCommands commands;

    // ── Constructor ───────────────────────────────────────────────────────────

    public WindowsMediaSession()
    {
        this.arena = Arena.ofShared();
        var linker = Linker.nativeLinker();
        var lib = AbstractFFMBridge.tryLoadLibrary(
                arena, "mediakeys", "midiraja_mediakeys.dll");

        smtcStart = linker.downcallHandle(
                lib.find("windows_smtc_start").orElseThrow(), DESC_START);
        smtcUpdate = linker.downcallHandle(
                lib.find("windows_smtc_update").orElseThrow(), DESC_UPDATE);
        smtcStop = linker.downcallHandle(
                lib.find("windows_smtc_stop").orElseThrow(), DESC_STOP);
    }

    // ── MediaKeyIntegration ───────────────────────────────────────────────────

    @Override
    public void start(PlaybackCommands commands)
    {
        if (!started.compareAndSet(false, true))
            return;
        this.commands = commands;
        try
        {
            var callbackMH = MethodHandles.lookup()
                    .findStatic(WindowsMediaSession.class, "onNativeCommand",
                            MethodType.methodType(void.class, WindowsMediaSession.class, int.class));
            var bound = callbackMH.bindTo(this);
            var stub = Linker.nativeLinker().upcallStub(bound, DESC_UPCALL, arena);
            smtcStart.invokeExact(stub);
        }
        catch (Throwable e)
        {
            log.warning("WindowsMediaSession.start() failed: " + e.getMessage());
        }
    }

    @Override
    public void drainAndUpdate(NowPlayingInfo info)
    {
        if (!started.get())
            return;
        drainQueue();
        try
        {
            var titleSeg = allocWideString(info.title());
            var artistSeg = info.artist().isEmpty()
                    ? MemorySegment.NULL
                    : allocWideString(info.artist());
            smtcUpdate.invokeExact(titleSeg, artistSeg,
                    info.durationMicros() * 10L, info.positionMicros() * 10L,
                    info.isPlaying() ? 1 : 0);
        }
        catch (Throwable e)
        {
            log.fine("WindowsMediaSession.drainAndUpdate() failed: " + e.getMessage());
        }
    }

    @Override
    public void close()
    {
        if (started.getAndSet(false))
        {
            commands = null;
            try
            {
                smtcStop.invokeExact();
            }
            catch (Throwable e)
            {
                log.fine("WindowsMediaSession.close() stop failed: " + e.getMessage());
            }
        }
        if (arena.scope().isAlive())
        {
            arena.close();
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /** Called from native thread — enqueue only, never call PlaybackCommands directly. */
    @SuppressWarnings("unused")
    private static void onNativeCommand(WindowsMediaSession self, int command)
    {
        self.pendingCommands.add(command);
    }

    /** Drains pending commands on the caller's thread (the engine playback thread). */
    private void drainQueue()
    {
        var cmds = commands;
        if (cmds == null)
            return;
        Integer cmd;
        while ((cmd = pendingCommands.poll()) != null)
        {
            switch (cmd)
            {
                case 0 -> cmds.togglePause();
                case 1 -> cmds.requestStop(PlaybackStatus.NEXT);
                case 2 -> cmds.requestStop(PlaybackStatus.PREVIOUS);
                case 3 -> cmds.seekRelative(+10_000_000L);
                case 4 -> cmds.seekRelative(-10_000_000L);
                default -> log.fine("Unknown media command: " + cmd);
            }
        }
    }

    /** Allocates a null-terminated UTF-16LE wide string in the shared arena. */
    private MemorySegment allocWideString(String s)
    {
        byte[] bytes = (s + "\0").getBytes(StandardCharsets.UTF_16LE);
        var seg = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return seg;
    }
}
