package com.midiraja.cli;

import com.midiraja.MidirajaCommand;
import com.midiraja.cli.AudioLibResolver;
import com.midiraja.midi.NativeAudioEngine;
import com.midiraja.midi.psg.PsgSynthProvider;
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

    @Option(names = {"--tube"}, description = "Apply analog vacuum tube saturation. (Recommended: 1.5 - 2.0).")
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
        com.midiraja.dsp.AudioProcessor pipeline = new com.midiraja.dsp.FloatToShortSink(audio, 1);
        
        // Global Tube Saturation
        if (tubeDrive.isPresent()) {
            pipeline = new com.midiraja.dsp.TubeSaturationFilter(pipeline, tubeDrive.get());
            pipeline = new com.midiraja.dsp.ShortToFloatFilter(pipeline);
        }
        
        var provider = new PsgSynthProvider(pipeline, finalChips, vibratoDepth, dutySweep, useScc, smooth);

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), false);
        return runner.run(provider, true, Optional.empty(), Optional.empty(), files, common);
    }
}
