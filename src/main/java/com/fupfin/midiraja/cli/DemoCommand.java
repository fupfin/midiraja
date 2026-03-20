/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.ui.Theme;
import java.util.Locale;
import java.util.Set;
import com.fupfin.midiraja.dsp.AudioProcessor;
import com.fupfin.midiraja.dsp.FloatToShortSink;
import com.fupfin.midiraja.dsp.ShortToFloatFilter;
import com.fupfin.midiraja.midi.AdlMidiSynthProvider;
import com.fupfin.midiraja.midi.FFMAdlMidiNativeBridge;
import com.fupfin.midiraja.midi.FFMOpnMidiNativeBridge;
import com.fupfin.midiraja.midi.FFMTsfNativeBridge;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.NativeAudioEngine;
import com.fupfin.midiraja.midi.OpnMidiSynthProvider;
import com.fupfin.midiraja.midi.TsfSynthProvider;
import com.fupfin.midiraja.midi.beep.BeepSynthProvider;
import com.fupfin.midiraja.midi.gus.GusSynthProvider;
import com.fupfin.midiraja.midi.psg.PsgSynthProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

@Command(name = "demo", description = "Play the curated demo playlist showcasing all synthesis engines.",
        mixinStandardHelpOptions = true)
public class DemoCommand implements Callable<Integer>
{
    @ParentCommand
    private @Nullable MidirajaCommand parent;

    @Mixin
    private CommonOptions common = new CommonOptions();

    @Mixin
    private FxOptions fxOptions = new FxOptions();

    @Override
    public Integer call() throws Exception
    {
        AppLogger.configure(common.logLevel.orElse(null));
        var p = requireNonNull(parent);
        File demoDir = findDemoDirectory();
        if (demoDir == null)
        {
            p.getErr().println("Error: Demo MIDI files not found.");
            p.getErr().println("Expected at: " + getSearchPaths());
            return 1;
        }

        List<File> allFiles = new PlaylistParser(p.getErr(), common.isVerbose()).parse(List.of(demoDir), common);
        if (allFiles.isEmpty())
        {
            p.getErr().println("Error: No MIDI files found in " + demoDir.getPath());
            return 1;
        }

        int total = allFiles.size();
        int currentIdx = 0;
        String deferredError = null;
        try
        {
            while (currentIdx >= 0 && currentIdx < total)
            {
                File file = allFiles.get(currentIdx);

                PlaybackStatus nav = DemoTransitionScreen.show(
                        currentIdx, total,
                        extractSongTitle(file.getName()),
                        extractEngineName(file.getName()),
                        common.uiOptions.classicMode, p.getOut());

                if (nav == PlaybackStatus.QUIT_ALL) break;
                if (nav == PlaybackStatus.PREVIOUS)
                {
                    currentIdx = (currentIdx - 1 + total) % total;
                    continue;
                }
                if (nav == PlaybackStatus.NEXT)
                {
                    currentIdx = (currentIdx + 1) % total;
                    continue;
                }

                ProviderWithArgs pwa;
                try
                {
                    pwa = selectProviderForTrack(file.getName());
                }
                catch (Exception e)
                {
                    deferredError = "Failed to start engine for '" + file.getName() + "': " + e.getMessage();
                    break;
                }

                PlaybackRunner runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
                runner.setFxOptions(fxOptions);
                runner.setSuppressAltScreenRestore(true);
                runner.setSuppressHoldAtEnd(true);
                runner.setExitOnNavBoundary(true);

                int exitCode = runner.run(pwa.provider, true, Optional.empty(), pwa.soundbank, List.of(file), common, List.of());

                if (exitCode != 0 || MidirajaCommand.SHUTTING_DOWN)
                {
                    if (exitCode != 0)
                        deferredError = "Playback failed for '" + file.getName() + "'. Run with --verbose for details.";
                    break;
                }

                PlaybackStatus playNav = runner.getLastRawStatus();
                if (playNav == PlaybackStatus.PREVIOUS)
                    currentIdx = (currentIdx - 1 + total) % total;
                else if (playNav == PlaybackStatus.NEXT)
                    currentIdx = (currentIdx + 1) % total;
                else if (playNav == PlaybackStatus.FINISHED)
                    currentIdx++;
                // QUIT_ALL: stay at same currentIdx — transition screen lets user press Q to exit
            }
        }
        finally
        {
            if (!common.uiOptions.classicMode)
            {
                p.getOut().print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR
                        + Theme.COLOR_RESET + "\033[?7h\n");
                p.getOut().flush();
            }
            if (deferredError != null)
                p.getErr().println("\nError: " + deferredError);
        }

        return 0;
    }

    private static class ProviderWithArgs {
        final MidiOutProvider provider;
        final Optional<String> soundbank;
        ProviderWithArgs(MidiOutProvider p, Optional<String> s) {
            this.provider = p;
            this.soundbank = s;
        }
    }

    private ProviderWithArgs selectProviderForTrack(String fileName) throws Exception
    {
        if (fileName.contains("-tsf-")) {
            var pipeline = buildPipeline(2);
            var provider = new TsfSynthProvider(new FFMTsfNativeBridge(), pipeline, null);
            if (fxOptions.masterGain != null) provider.setMasterGain(fxOptions.masterGain);
            return new ProviderWithArgs(provider, Optional.ofNullable(findResource("soundfonts/FluidR3_GM.sf3")));
        }
        if (fileName.contains("-gus-")) {
            var pipeline = buildPipeline(2);
            var patchDir = findResource("freepats");
            var provider = new GusSynthProvider(pipeline, patchDir);
            if (fxOptions.masterGain != null) provider.setMasterGain(fxOptions.masterGain);
            return new ProviderWithArgs(provider, Optional.ofNullable(patchDir));
        }
        if (fileName.contains("-opl3-")) {
            var pipeline = buildPipeline(2);
            var provider = new AdlMidiSynthProvider(new FFMAdlMidiNativeBridge(), pipeline, 0, 4, common.retroMode.orElse(null));
            if (fxOptions.masterGain != null) provider.setMasterGain(fxOptions.masterGain);
            return new ProviderWithArgs(provider, Optional.of("bank:0"));
        }
        if (fileName.contains("-opn2-")) {
            var pipeline = buildPipeline(2);
            var provider = new OpnMidiSynthProvider(new FFMOpnMidiNativeBridge(), pipeline, 0, 4, common.retroMode.orElse(null));
            if (fxOptions.masterGain != null) provider.setMasterGain(fxOptions.masterGain);
            return new ProviderWithArgs(provider, Optional.of(""));
        }
        if (fileName.contains("-psg-")) {
            var pipeline = buildPipeline(1);
            var provider = new PsgSynthProvider(pipeline, 4, 5.0, 25.0, false, false);
            if (fxOptions.masterGain != null) provider.setMasterGain(fxOptions.masterGain);
            return new ProviderWithArgs(provider, Optional.empty());
        }
        if (fileName.contains("-beep-")) {
            var pipeline = buildPipeline(1);
            var provider = new BeepSynthProvider(pipeline, 2, 1.0, 1.1, 1, "dsd", "square");
            if (fxOptions.masterGain != null) provider.setMasterGain(fxOptions.masterGain);
            return new ProviderWithArgs(provider, Optional.empty());
        }
        throw new IllegalArgumentException("Unknown engine tag in demo filename: " + fileName);
    }

    private AudioProcessor buildPipeline(int channels) throws Exception {
        var audio = new NativeAudioEngine(AudioLibResolver.resolve());
        audio.init(44100, channels, 4096);
        if (common.dumpWav.isPresent()) {
            audio.enableDump(common.dumpWav.get());
        }
        AudioProcessor pipeline = new FloatToShortSink(audio, channels);
        pipeline = common.wrapRetroPipeline(pipeline);
        pipeline = fxOptions.wrapWithFloatConversion(pipeline, common);
        return pipeline;
    }

    @Nullable
    private File findDemoDirectory()
    {
        String[] paths = {
            "src/main/resources/demomidi",
            "share/midra/demomidi",
            "share/demomidi",
            "../share/midra/demomidi"
        };
        
        String midraData = System.getenv("MIDRA_DATA");
        if (midraData != null) {
            File f = new File(midraData, "demomidi");
            if (f.exists()) return f;
        }

        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) return f;
        }
        return null;
    }

    private @Nullable String findResource(String subPath) {
        String midraData = System.getenv("MIDRA_DATA");
        if (midraData != null) {
            File f = new File(midraData, subPath);
            if (f.exists()) return f.getAbsolutePath();
        }
        File dev = new File("build", subPath);
        if (dev.exists()) return dev.getAbsolutePath();
        return null;
    }

    private String getSearchPaths() {
        return "MIDRA_DATA/demomidi, src/main/resources/demomidi, or share/midra/demomidi";
    }

    private static final Set<String> ENGINE_TAGS =
            Set.of("tsf", "gus", "opl3", "opn2", "psg", "beep", "munt", "fluid");

    /** Extracts a human-readable title from a demo filename like {@code 03-gus-entertainer.mid}. */
    static String extractSongTitle(String fileName)
    {
        String base = fileName.replaceFirst("\\.[^.]+$", "");   // strip extension
        String[] parts = base.split("-");
        var sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++)
        {
            if (i == 0) continue;                               // skip leading number
            if (i == 1 && ENGINE_TAGS.contains(parts[i].toLowerCase(Locale.ROOT))) continue;
            if (!sb.isEmpty()) sb.append(' ');
            String word = parts[i];
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.isEmpty() ? base : sb.toString();
    }

    /** Returns a display name for the synthesis engine inferred from the demo filename. */
    static String extractEngineName(String fileName)
    {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("-tsf-"))  return "TinySoundFont (General MIDI SF3)";
        if (lower.contains("-gus-"))  return "GUS Patches (FreePats)";
        if (lower.contains("-opl3-")) return "OPL3 FM Synthesis (libADLMIDI)";
        if (lower.contains("-opn2-")) return "OPN2 FM Synthesis (libOPNMIDI)";
        if (lower.contains("-psg-"))  return "PSG Chip Synthesis (AY-3-8910)";
        if (lower.contains("-beep-")) return "PC Beeper";
        if (lower.contains("-munt-")) return "MT-32 Emulation (Munt)";
        if (lower.contains("-fluid-")) return "FluidSynth";
        return "TinySoundFont (General MIDI SF3)";
    }
}
