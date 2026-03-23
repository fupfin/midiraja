/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import com.fupfin.midiraja.midi.MidiOutProvider;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javax.sound.midi.Sequence;

/**
 * Factory for constructing a {@link PlaybackEngine} instance per track.
 *
 * <p>The default production implementation is the constructor reference
 * {@code MidiPlaybackEngine::new}. Tests inject a lambda that returns a mock engine whose
 * {@code start()} returns a predetermined {@link PlaybackEngine.PlaybackStatus} immediately,
 * allowing the playlist loop in {@code PlaybackRunner} to be exercised without real audio threads.
 */
@FunctionalInterface
public interface PlaybackEngineFactory
{
    /**
     * @throws Exception propagated from the engine constructor or provider
     */
    PlaybackEngine create(Sequence sequence, MidiOutProvider provider,
            PlaylistContext context,
            PlaybackPipeline pipeline, BooleanSupplier isShuttingDown,
            double initialSpeed,
            Optional<Long> startTimeMicroseconds) throws Exception;
}
