/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

import java.util.Locale;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.export.vgm.ChipHandlers;
import com.fupfin.midiraja.export.vgm.ChipSpec;
import com.fupfin.midiraja.export.vgm.FmBankOverride;
import com.fupfin.midiraja.export.vgm.RoutingMode;

final class VgmCliSupport
{
    private VgmCliSupport()
    {
    }

    static ChipSpec resolvePlaybackChipSpec(@Nullable String system, @Nullable String chips)
    {
        if (chips != null)
            return ChipHandlers.parseChips(chips);
        String normalizedSystem = system != null ? system.toLowerCase(Locale.ROOT) : "megadrive";
        return resolvePresetChipSpec(normalizedSystem, normalizedSystem);
    }

    static ChipSpec resolveExportChipSpec(@Nullable String system, @Nullable String chips)
    {
        if (system != null)
            return resolvePresetChipSpec(system.toLowerCase(Locale.ROOT), system);
        return ChipHandlers.parseChips(requireNonNull(chips));
    }

    static <T> T withBankOverride(@Nullable String bankSpec, Callable<T> action)
    {
        FmBankOverride.apply(bankSpec == null ? "" : bankSpec);
        try
        {
            return action.call();
        }
        catch (RuntimeException | Error e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw throwUnchecked(e);
        }
        finally
        {
            FmBankOverride.clear();
        }
    }

    private static ChipSpec resolvePresetChipSpec(String lookupKey, String reportedSystem)
    {
        var chips = ChipHandlers.PRESETS.get(lookupKey);
        if (chips == null)
            throw new IllegalArgumentException(
                    "Unknown --system value: '" + reportedSystem + "'. Valid values: "
                            + ChipHandlers.PRESETS.keySet());
        return new ChipSpec(chips, RoutingMode.SEQUENTIAL);
    }

    private static RuntimeException throwUnchecked(Throwable throwable)
    {
        VgmCliSupport.<RuntimeException>throwAny(throwable);
        throw new AssertionError("unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwAny(Throwable throwable) throws T
    {
        throw (T) throwable;
    }
}
