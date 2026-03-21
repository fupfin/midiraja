/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.ui.Logo;
import com.fupfin.midiraja.ui.Theme;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Interactive engine selection menu shown when {@code midra} is run without a subcommand.
 *
 * <p>
 * Combines built-in zero-setup synthesizers with available OS MIDI ports into a single list, then
 * lets the user pick one with arrow keys.
 */
public class EngineSelector
{
    public sealed interface Choice permits Choice.Builtin, Choice.Port
    {
        record Builtin(String engineName) implements Choice
        {
        }

        record Port(int portIndex) implements Choice
        {
        }
    }

    // Built-in engines ordered by audio quality (best first)
    private static final List<TerminalSelector.Item<Choice>> BUILTIN_ITEMS = List.of(
            builtin("soundfont", "SoundFont            (FluidR3 GM SF3 bundled)"),
            builtin("patch    ", "GUS wavetable        (FreePats bundled)"),
            builtin("opn      ", "OPN FM synthesis     (Sega Genesis / PC-98)"),
            builtin("opl      ", "OPL FM synthesis     (DOS / AdLib / Sound Blaster)"),
            builtin("psg      ", "PSG chiptune         (MSX / ZX Spectrum / Atari ST)"),
            builtin("1bit     ", "1-Bit Beeper         (Apple II / PC Speaker)"));

    private static TerminalSelector.Item<Choice> builtin(String name, String desc)
    {
        return TerminalSelector.Item.of(new Choice.Builtin(name.strip()), name, desc);
    }

    /**
     * Resets the terminal to canonical (cooked) mode by running {@code stty sane}.
     *
     * <p>
     * A previous run may have exited while the terminal was in raw mode (e.g. if the JLine terminal
     * was not closed cleanly). This call resets the OS-level tty state so that subsequent JLine
     * opens capture cooked mode as their "original" attributes and restore it correctly on close.
     */
    private static void resetTerminalToSane()
    {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        try
        {
            new ProcessBuilder("stty", "sane")
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(1, TimeUnit.SECONDS);
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * Shows the engine selection menu and returns the user's choice, or {@code null} if the user
     * quit.
     */
    @Nullable
    public static Choice select(List<MidiPort> ports, UiModeOptions uiOpts, PrintStream err)
            throws Exception
    {
        resetTerminalToSane();

        var items = buildItems(ports);
        var config = new TerminalSelector.FullScreenConfig(
                " SELECT PLAYBACK ENGINE ",
                64, 84,
                Logo.WIDTH + 4,
                Logo.LINES.size() + 2, // +1 for subtitle, +1 for blank line
                (buf, width) -> {
                    int logoPad = Math.max(0, (width - Logo.WIDTH) / 2);
                    for (int li = 0; li < Logo.LINES.size(); li++)
                        buf.repeat(" ", logoPad).append(Logo.LINE_COLORS.get(li))
                                .append(Logo.LINES.get(li)).append(Theme.COLOR_RESET).appendLine();
                    int subtitlePad = Math.max(0, (width - Logo.SUBTITLE.length()) / 2);
                    buf.repeat(" ", subtitlePad)
                            .append(Theme.COLOR_VU).append(Logo.VU_BARS)
                            .append(Theme.COLOR_DIM_FG).append("  ").append(Logo.SUBTITLE_TEXT)
                            .append(Theme.COLOR_RESET).appendLine();
                    buf.appendLine();
                });

        return TerminalSelector.select(items, config, uiOpts.fullMode, uiOpts.miniMode,
                uiOpts.classicMode, err);
    }

    static List<TerminalSelector.Item<Choice>> buildItems(List<MidiPort> ports)
    {
        var list = new ArrayList<TerminalSelector.Item<Choice>>();
        if (!ports.isEmpty())
        {
            ports.forEach(p -> list.add(
                    TerminalSelector.Item.of(new Choice.Port(p.index()), p.name(), "OS MIDI port")));
            list.add(TerminalSelector.Item
                    .separator("── Built-in Engines ───────────────────────────────────────"));
        }
        list.addAll(BUILTIN_ITEMS);
        return list;
    }
}
