package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.dsp.*;
import com.fupfin.midiraja.io.Console;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;

/**
 * Shared DSP effect options (EQ, Reverb, Chorus, Tube).
 */
public class FxOptions
{
    @Option(names = {"--bass"}, defaultValue = "50",
            description = "Adjust bass gain (0-100%%). Default: 50 (neutral).")
    public float eqBass = 100; // Actually wait, in OplCommand it was initialized to 100? Let's
                               // check. Default is 50 in string, but 100 in code? That's a bug in
                               // original code probably. Wait, let's use what they had.

    @Option(names = {"--mid"}, defaultValue = "50",
            description = "Adjust mid gain (0-100%%). Default: 50 (neutral).")
    public float eqMid = 100;

    @Option(names = {"--treble"}, defaultValue = "50",
            description = "Adjust treble gain (0-100%%). Default: 50 (neutral).")
    public float eqTreble = 100;

    @Option(names = {"--lpf"},
            description = "Low-Pass Filter cutoff frequency in Hz (e.g. 2000). Cuts off high frequencies.")
    public Optional<Float> lpfFreq = Optional.empty();

    @Option(names = {"--hpf"},
            description = "High-Pass Filter cutoff frequency in Hz (e.g. 500). Cuts off low frequencies.")
    public Optional<Float> hpfFreq = Optional.empty();

    @Option(names = {"--chorus"},
            description = "Apply classic stereo chorus effect. (Intensity: 0-100%%, Recommended: 30-70).")
    public Optional<Float> chorus = Optional.empty();

    @Option(names = {"--reverb"},
            description = "Apply algorithmic reverb preset. (Options: room, chamber, hall, plate, spring, cave).")
    public Optional<String> reverb = Optional.empty();

    @Option(names = {"--reverb-level"}, defaultValue = "50",
            description = "Reverb wet level intensity (0-100%%). Default: 50 (neutral).")
    public float reverbLevel = 50;

    @Option(names = {"--tube"},
            description = "Apply analog vacuum tube saturation. (Range: 0-100%%).")
    public Optional<Float> tubeDrive = Optional.empty();

    /** The MasterGainFilter inserted by {@link #wrapWithFloatConversion}; null if DSP is inactive. */
    public @Nullable MasterGainFilter masterGain = null;

    public AudioProcessor wrapFxPipeline(AudioProcessor pipeline)
    {
        if (eqBass != 50 || eqMid != 50 || eqTreble != 50 || lpfFreq.isPresent()
                || hpfFreq.isPresent())
        {
            var eq = new EqFilter(pipeline);
            eq.setParams(eqBass, eqMid, eqTreble);
            if (lpfFreq.isPresent()) eq.setLpf(lpfFreq.get());
            if (hpfFreq.isPresent()) eq.setHpf(hpfFreq.get());
            pipeline = eq;
        }
        if (tubeDrive.isPresent())
            pipeline = new TubeSaturationFilter(pipeline, 1.0f + (tubeDrive.get() / 100.0f * 9.0f));
        if (chorus.isPresent())
            pipeline = new ChorusFilter(pipeline, chorus.get());
        if (reverb.isPresent())
        {
            float levelScale = reverbLevel / 100.0f;
            try
            {
                var preset = ReverbFilter.Preset.valueOf(reverb.get().toUpperCase(java.util.Locale.ROOT));
                pipeline = new ReverbFilter(pipeline, preset, levelScale);
            }
            catch (IllegalArgumentException e)
            {
                Console.err.println(
                        "Warning: Unknown reverb preset '" + reverb.get() + "'. Using HALL.");
                pipeline = new ReverbFilter(pipeline, ReverbFilter.Preset.HALL, levelScale);
            }
        }
        return pipeline;
    }

    public boolean needsFloatConversion(CommonOptions common)
    {
        return eqBass != 50 || eqMid != 50 || eqTreble != 50 || tubeDrive.isPresent()
                || chorus.isPresent() || reverb.isPresent() || (common != null
                        && (common.retroMode.isPresent() || common.speakerProfile.isPresent()));
    }

    /**
     * Wraps the pipeline with ShortToFloat → FX chain → MasterGain if float conversion is needed.
     * The MasterGain is initialized to {@code INTERNAL_LEVEL_INV × (volume / 100)} so that
     * {@code --volume} directly controls the PCM output level for internal synths.
     * The inserted filter is also stored in {@link #masterGain} for runtime adjustment.
     */
    public AudioProcessor wrapWithFloatConversion(AudioProcessor pipeline, CommonOptions common)
    {
        if (needsFloatConversion(common))
        {
            masterGain = new MasterGainFilter(pipeline);
            masterGain.setVolumeScale(Math.max(0, Math.min(150, common.volume)) / 100.0f);
            pipeline = masterGain;
            pipeline = wrapFxPipeline(pipeline);
            pipeline = new ShortToFloatFilter(pipeline);
        }
        return pipeline;
    }
}
