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

    @Mixin private final CommonOptions common = new CommonOptions();

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
        if (common != null && common.dumpWav.isPresent()) { audio.enableDump(common.dumpWav.get()); }
        
        com.fupfin.midiraja.dsp.AudioProcessor pipeline = new com.fupfin.midiraja.dsp.FloatToShortSink(audio, 1);
        // 1. Acoustic Speaker Simulation (Applied BEFORE final DAC to shape the signal)
        // Wait, physical speakers come AFTER the DAC.
        // But our pipeline runs BACKWARDS. The signal flows from the synth (upstream) to the sink.
        // The FloatToShortSink calls `pipeline.process()`. 
        // So the outermost wrapper is executed LAST in the physical signal chain.
        // The physical chain is: Synth -> Filter -> DAC -> Speaker.
        // Therefore, we must wrap in this order:
        // pipeline = Speaker(pipeline)
        // pipeline = DAC(pipeline)
        
        if (common != null && common.speakerProfile.isPresent()) {
            String profileStr = common.speakerProfile.get().toUpperCase(java.util.Locale.ROOT).replace("-", "_");
            try {
                com.fupfin.midiraja.dsp.AcousticSpeakerFilter.Profile profile = 
                    com.fupfin.midiraja.dsp.AcousticSpeakerFilter.Profile.valueOf(profileStr);
                pipeline = new com.fupfin.midiraja.dsp.AcousticSpeakerFilter(true, profile, pipeline);
            } catch (IllegalArgumentException e) {
                System.err.println("Warning: Unknown speaker profile '" + profileStr + "'. Ignoring.");
            }
        }

        // 2. Retro DAC Conversion
        if (common != null && common.retroMode.isPresent()) {
            String mode = common.retroMode.get().toLowerCase(java.util.Locale.ROOT);
            switch (mode) {
                case "mac128k":
                    // mac128k is a monolithic filter combining DAC and Speaker for now
                    pipeline = new com.fupfin.midiraja.dsp.Mac128kSimulatorFilter(true, pipeline);
                    break;
                case "ibmpc":
                case "1bit":
                case "realsound": // legacy mapped to IBM PC
                    pipeline = new com.fupfin.midiraja.dsp.OneBitHardwareFilter(true, "pwm", 18600.0, 64.0, 0.45f, pipeline);
                    break;
                case "covox":
                case "8bit":
                    pipeline = new com.fupfin.midiraja.dsp.CovoxDacFilter(true, pipeline);
                    break;
                case "apple2":
                    pipeline = new com.fupfin.midiraja.dsp.OneBitHardwareFilter(true, "pwm", 11025.0, 93.0, 0.35f, pipeline);
                    break;
                case "spectrum":
                    // ZX Spectrum Z80 at 3.5MHz allows high carrier (17.5kHz) and high precision (200 levels).
                    // Its speaker was tiny and piercing, so we use a lighter filter (alpha 0.50)
                    pipeline = new com.fupfin.midiraja.dsp.OneBitHardwareFilter(true, "pwm", 17500.0, 200.0, 0.50f, pipeline);
                    break;
                case "amiga":
                case "disneysound":
                    // Fallbacks for planned features
                    pipeline = new com.fupfin.midiraja.dsp.CovoxDacFilter(true, pipeline);
                    break;
                default:
                    System.err.println("Warning: Unknown DAC mode '" + mode + "'. Ignoring.");
            }
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
        
        if (eqBass != 50 || eqMid != 50 || eqTreble != 50 || tubeDrive.isPresent() || chorus.isPresent() || reverb.isPresent() || (common != null && (common.retroMode.isPresent() || common.speakerProfile.isPresent()))) {
            pipeline = new com.fupfin.midiraja.dsp.ShortToFloatFilter(pipeline);
        }
        
        var provider = new PsgSynthProvider(pipeline, finalChips, vibratoDepth, dutySweep, useScc, smooth);

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), false);
        return runner.run(provider, true, Optional.empty(), Optional.empty(), files, common);
    }
}
