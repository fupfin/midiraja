package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.cli.AudioLibResolver;
import com.fupfin.midiraja.midi.NativeAudioEngine;
import com.fupfin.midiraja.midi.psg.PsgSynthProvider;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

@Command(name = "psg", aliases = {"ay", "msx"}, mixinStandardHelpOptions = true,
         description = "Play using the Programmable Sound Generator (PSG) emulator with 8-bit tracker hacks")
public class PsgCommand implements java.util.concurrent.Callable<Integer>
{
    @ParentCommand @Nullable private MidirajaCommand parent;

    @Parameters(index = "0..*", arity = "1..*",
        description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Option(names = {"--chips"}, defaultValue = "4", description = "Number of virtual PSG chips to instantiate (1 to 16). Default: 4 (12 channels). Set to 1 for authentic harsh arpeggios.")
    private int chips = 4;
    
    @Option(names = {"--vibrato"}, defaultValue = "5.0", description = "Depth of the software vibrato LFO in parts per thousand (0-100). Default: 5.0 (subtle), 30.0 (heavy wobble). Set to 0 to disable.")
    private double vibratoDepth = 5.0;

    @Option(names = {"--duty-sweep"}, defaultValue = "25.0", description = "Width of the pulse-width sweep as a percentage (0-100). Default: 25.0 (gentle breathing), 45.0 (harsh wah-wah). Set to 0 to disable.")
    private double dutySweep = 25.0;

    @Option(names = {"--scc"}, description = "Enable Konami SCC (K051649) Sound Cartridge emulation. Uses 32-byte custom wavetables for richer instruments instead of pure square waves.")
    private boolean useScc = false;

    @Option(names = {"--smooth"}, description = "Enable linear interpolation and continuous volume scaling for the SCC emulator. Produces a smooth, modern 'studio' sound instead of the historically accurate gritty hardware sound.")
    private boolean smooth = false;

    @Mixin private CommonOptions common = new CommonOptions();

        @Option(names = {"--bass"}, defaultValue = "50", description = "Adjust bass gain (0-100%%). Default: 50 (neutral).")
    private float eqBass = 100;

    @Option(names = {"--mid"}, defaultValue = "50", description = "Adjust mid gain (0-100%%). Default: 50 (neutral).")
    private float eqMid = 100;

    @Option(names = {"--treble"}, defaultValue = "50", description = "Adjust treble gain (0-100%%). Default: 50 (neutral).")
    private float eqTreble = 100;

        @Option(names = {"--lpf"}, description = "Low-Pass Filter cutoff frequency in Hz (e.g. 2000). Cuts off high frequencies.")
    private Optional<Float> lpfFreq = Optional.empty();

    @Option(names = {"--hpf"}, description = "High-Pass Filter cutoff frequency in Hz (e.g. 500). Cuts off low frequencies.")
    private Optional<Float> hpfFreq = Optional.empty();

    @Option(names = {"--chorus"}, description = "Apply classic stereo chorus effect. (Intensity: 0-100%%, Recommended: 30-70).")
    private Optional<Float> chorus = Optional.empty();

    @Option(names = {"--reverb"}, description = "Apply algorithmic reverb preset. (Options: room, chamber, hall, plate, spring, cave).")
    private Optional<String> reverb = Optional.empty();

    @Option(names = {"--reverb-level"}, defaultValue = "50", description = "Reverb wet level intensity (0-100%%). Default: 50 (neutral).")
    private float reverbLevel = 50;

    @Option(names = {"--tube"}, description = "Apply analog vacuum tube saturation. (Range: 0-100%%, Recommended: 10-20).")
    private Optional<Float> tubeDrive = Optional.empty();

    @Override public Integer call() throws Exception
    {
        var p = java.util.Objects.requireNonNull(parent);
        
        // If SCC is requested and chips was left at default (4), change it to 2.
        // 2 chips in SCC mode gives the classic MSX + SCC combination (1 PSG + 1 SCC).
        int finalChips = chips;
        if (useScc && finalChips == 4) {
            finalChips = 2;
        }
        
        String audioLib = AudioLibResolver.resolve();
        var audio = new NativeAudioEngine(audioLib);
        audio.init(44100, 1, 4096);
        
        com.fupfin.midiraja.dsp.AudioProcessor pipeline = new com.fupfin.midiraja.dsp.FloatToShortSink(audio, 1);
        if (common != null && common.oneBitMode.isPresent()) {
            pipeline = new com.fupfin.midiraja.dsp.OneBitAcousticSimulatorFilter(true, common.oneBitMode.get(), pipeline);
        }
        if (common != null && common.mac128kMode) {
            pipeline = new com.fupfin.midiraja.dsp.Mac128kSimulatorFilter(true, pipeline);
        }
        if (common != null && common.eightBitMode) {
            pipeline = new com.fupfin.midiraja.dsp.EightBitQuantizerFilter(true, pipeline);
        }
        
        if (eqBass != 50 || eqMid != 50 || eqTreble != 50 || lpfFreq.isPresent() || hpfFreq.isPresent()) {
            var eq = new com.fupfin.midiraja.dsp.EqFilter(pipeline);
            eq.setParams(eqBass, eqMid, eqTreble);
            if (lpfFreq.isPresent()) eq.setLpf(lpfFreq.get());
            if (hpfFreq.isPresent()) eq.setHpf(hpfFreq.get());
            pipeline = eq;
        }
        if (tubeDrive.isPresent()) {
            pipeline = new com.fupfin.midiraja.dsp.TubeSaturationFilter(pipeline, 1.0f + (tubeDrive.get() / 100.0f * 9.0f));
        }
        if (chorus.isPresent()) {
            pipeline = new com.fupfin.midiraja.dsp.ChorusFilter(pipeline, chorus.get());
        }
        if (reverb.isPresent()) {
            
            float levelScale = reverbLevel / 100.0f;
            try {
                var preset = com.fupfin.midiraja.dsp.ReverbFilter.Preset.valueOf(reverb.get().toUpperCase(java.util.Locale.ROOT));
                pipeline = new com.fupfin.midiraja.dsp.ReverbFilter(pipeline, preset, levelScale);
            } catch (IllegalArgumentException e) {
                System.err.println("Warning: Unknown reverb preset '" + reverb.get() + "'. Using HALL.");
                pipeline = new com.fupfin.midiraja.dsp.ReverbFilter(pipeline, com.fupfin.midiraja.dsp.ReverbFilter.Preset.HALL, levelScale);
            }
        }
        
        if (eqBass != 50 || eqMid != 50 || eqTreble != 50 || tubeDrive.isPresent() || chorus.isPresent() || reverb.isPresent()) {
            pipeline = new com.fupfin.midiraja.dsp.ShortToFloatFilter(pipeline);
        }
        
        var provider = new PsgSynthProvider(pipeline, finalChips, vibratoDepth, dutySweep, useScc, smooth);

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), false);
        return runner.run(provider, true, Optional.empty(), Optional.empty(), files, common);
    }
}
