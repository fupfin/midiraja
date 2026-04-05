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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.midi.AbstractFFMBridge;

/**
 * macOS media key integration via MPRemoteCommandCenter + MPNowPlayingInfoCenter.
 * Requires macOS 10.12.2+ and libmidiraja_mediakeys.dylib.
 *
 * <p>
 * Call sequence: {@code start()} → any number of {@code drainAndUpdate()} → {@code close()}.
 * All methods are safe to call out of order or multiple times.
 *
 * <p>
 * Native commands are dispatched directly from the macOS RunLoop thread; all
 * {@link com.fupfin.midiraja.engine.PlaybackCommands} methods are thread-safe so no queue is needed.
 */
public final class MacOSMediaSession implements MediaKeyIntegration
{
    private static final Logger log = Logger.getLogger(MacOSMediaSession.class.getName());

    // ── FunctionDescriptors for NativeMetadataConsistencyTest ────────────────

    static final FunctionDescriptor DESC_REGISTER = FunctionDescriptor.ofVoid(ADDRESS);
    static final FunctionDescriptor DESC_UPDATE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE,
            JAVA_INT);
    static final FunctionDescriptor DESC_UNREGISTER = FunctionDescriptor.ofVoid();
    static final FunctionDescriptor DESC_UPCALL = FunctionDescriptor.ofVoid(JAVA_INT);

    /** Returns all downcall descriptors for {@code NativeMetadataConsistencyTest}. */
    public static List<FunctionDescriptor> allDowncallDescriptors()
    {
        return List.of(DESC_REGISTER, DESC_UPDATE, DESC_UNREGISTER);
    }

    /** Returns all upcall descriptors for {@code NativeMetadataConsistencyTest}. */
    public static List<FunctionDescriptor> allUpcallDescriptors()
    {
        return List.of(DESC_UPCALL);
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Arena arena;
    private final MethodHandle registerCommands;
    private final MethodHandle updateNowPlaying;
    private final MethodHandle unregister;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile @Nullable PlaybackCommands commands;

    // ── Constructor ───────────────────────────────────────────────────────────

    public MacOSMediaSession()
    {
        this.arena = Arena.ofShared();
        var linker = Linker.nativeLinker();
        var lib = AbstractFFMBridge.tryLoadLibrary(
                arena, "mediakeys", "libmidiraja_mediakeys.dylib");

        registerCommands = linker.downcallHandle(
                lib.find("macos_register_commands").orElseThrow(), DESC_REGISTER);
        updateNowPlaying = linker.downcallHandle(
                lib.find("macos_update_now_playing").orElseThrow(), DESC_UPDATE);
        unregister = linker.downcallHandle(
                lib.find("macos_unregister").orElseThrow(), DESC_UNREGISTER);
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
                    .findStatic(MacOSMediaSession.class, "onNativeCommand",
                            MethodType.methodType(void.class, MacOSMediaSession.class, int.class));
            var bound = callbackMH.bindTo(this);
            var stub = Linker.nativeLinker().upcallStub(bound, DESC_UPCALL, arena);
            registerCommands.invokeExact(stub);
        }
        catch (Throwable e)
        {
            log.warning("MacOSMediaSession.start() failed: " + e.getMessage());
        }
    }

    @Override
    public void drainAndUpdate(NowPlayingInfo info)
    {
        if (!started.get())
            return;
        try
        {
            var titleSeg = arena.allocateFrom(info.title());
            var artistSeg = info.artist().isEmpty()
                    ? MemorySegment.NULL
                    : arena.allocateFrom(info.artist());
            double durationSec = info.durationMicros() / 1_000_000.0;
            double positionSec = info.positionMicros() / 1_000_000.0;
            updateNowPlaying.invokeExact(titleSeg, artistSeg, durationSec, positionSec,
                    info.isPlaying() ? 1 : 0);
        }
        catch (Throwable e)
        {
            log.fine("MacOSMediaSession.drainAndUpdate() failed: " + e.getMessage());
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
                unregister.invokeExact();
            }
            catch (Throwable e)
            {
                log.fine("MacOSMediaSession.close() unregister failed: " + e.getMessage());
            }
        }
        if (arena.scope().isAlive())
        {
            arena.close();
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Called from the native macOS RunLoop thread.
     * All {@link PlaybackCommands} methods are thread-safe (AtomicBoolean / volatile-backed),
     * so dispatch directly — no queue needed, and commands work even while paused.
     */
    @SuppressWarnings("unused")
    private static void onNativeCommand(MacOSMediaSession self, int command)
    {
        var cmds = self.commands;
        if (cmds == null)
            return;
        switch (command)
        {
            case 0 -> cmds.togglePause();
            case 1 -> cmds.requestStop(PlaybackStatus.NEXT);
            case 2 -> cmds.requestStop(PlaybackStatus.PREVIOUS);
            case 3 -> cmds.seekRelative(+10_000_000L);
            case 4 -> cmds.seekRelative(-10_000_000L);
            default -> log.fine("Unknown media command: " + command);
        }
    }
}
