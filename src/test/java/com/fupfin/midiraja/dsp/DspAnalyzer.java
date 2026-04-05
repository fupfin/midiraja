package com.fupfin.midiraja.dsp;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates raw PCM files for DSP pipeline spectral analysis.
 *
 * <p>
 * Outputs stereo 16-bit LE PCM files to {@code dsp_analysis/} (or a custom directory),
 * then analyze them with:
 *
 * <pre>
 *   python3 scripts/analyze_audio.py dsp_analysis/
 * </pre>
 *
 * <p>
 * To run from the project root:
 *
 * <pre>
 *   ./gradlew testClasses &amp;&amp; java -cp build/classes/java/test:build/classes/java/main \
 *       com.fupfin.midiraja.dsp.DspAnalyzer [output-dir]
 * </pre>
 */
public class DspAnalyzer
{
    static final int SR = 44100;
    static final int FRAMES = SR * 2; // 2 seconds

    // ── capture sink ──────────────────────────────────────────────────────────

    static class CaptureSink implements AudioProcessor
    {
        final float[] left = new float[FRAMES];
        final float[] right = new float[FRAMES];
        int written = 0;

        @Override
        public void process(float[] l, float[] r, int frames)
        {
            int n = Math.min(frames, FRAMES - written);
            System.arraycopy(l, 0, left, written, n);
            System.arraycopy(r, 0, right, written, n);
            written += n;
        }
    }

    // ── signal generators ─────────────────────────────────────────────────────

    static float[] sine(double freq)
    {
        float[] buf = new float[FRAMES];
        for (int i = 0; i < FRAMES; i++)
            buf[i] = (float) (0.5 * Math.sin(2 * Math.PI * freq * i / SR));
        return buf;
    }

    static float[] whiteNoise(long seed)
    {
        var rng = new java.util.Random(seed);
        float[] buf = new float[FRAMES];
        for (int i = 0; i < FRAMES; i++)
            buf[i] = (rng.nextFloat() * 2 - 1) * 0.5f;
        return buf;
    }

    // ── pipeline runners ──────────────────────────────────────────────────────

    static CaptureSink runDry(float[] signal)
    {
        var sink = new CaptureSink();
        sink.process(signal.clone(), signal.clone(), FRAMES);
        return sink;
    }

    static CaptureSink runAmigaOnly(float[] signal)
    {
        var sink = new CaptureSink();
        new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, sink)
                .process(signal.clone(), signal.clone(), FRAMES);
        return sink;
    }

    static CaptureSink runCompactMac(float[] signal)
    {
        var sink = new CaptureSink();
        new CompactMacSimulatorFilter(true, false, sink)
                .process(signal.clone(), signal.clone(), FRAMES);
        return sink;
    }

    static CaptureSink runReverbOnly(float[] signal, ReverbFilter.Preset preset, float level)
    {
        var sink = new CaptureSink();
        new ReverbFilter(sink, preset, level)
                .process(signal.clone(), signal.clone(), FRAMES);
        return sink;
    }

    /** NEW order: AmigaPaula first, then Reverb. */
    static CaptureSink runAmigaThenReverb(float[] signal, ReverbFilter.Preset preset, float level)
    {
        var sink = new CaptureSink();
        var reverb = new ReverbFilter(sink, preset, level);
        new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, reverb)
                .process(signal.clone(), signal.clone(), FRAMES);
        return sink;
    }

    /** OLD order: Reverb first, then AmigaPaula. */
    static CaptureSink runReverbThenAmiga(float[] signal, ReverbFilter.Preset preset, float level)
    {
        var sink = new CaptureSink();
        var amiga = new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500, sink);
        new ReverbFilter(amiga, preset, level)
                .process(signal.clone(), signal.clone(), FRAMES);
        return sink;
    }

    // ── I/O ───────────────────────────────────────────────────────────────────

    static void save(CaptureSink sink, String path) throws IOException
    {
        Files.createDirectories(Path.of(path).getParent());
        try (var fos = new FileOutputStream(path))
        {
            for (int i = 0; i < sink.written; i++)
            {
                int l = (int) (Math.max(-1f, Math.min(1f, sink.left[i])) * 32767);
                int r = (int) (Math.max(-1f, Math.min(1f, sink.right[i])) * 32767);
                fos.write(l & 0xff);
                fos.write((l >> 8) & 0xff);
                fos.write(r & 0xff);
                fos.write((r >> 8) & 0xff);
            }
        }
        System.out.println("  wrote: " + path);
    }

    // ── main ──────────────────────────────────────────────────────────────────

    // ── multi-tone signal (simulates music harmonic content) ─────────────────

    static float[] multiTone(double... freqs)
    {
        float[] buf = new float[FRAMES];
        float amp = 0.5f / freqs.length;
        for (double f : freqs)
            for (int i = 0; i < FRAMES; i++)
                buf[i] += (float) (amp * Math.sin(2 * Math.PI * f * i / SR));
        return buf;
    }

    public static void main(String[] args) throws Exception
    {
        String dir = args.length > 0 ? args[0] : "dsp_analysis";
        var preset = ReverbFilter.Preset.ROOM;
        float level = 0.5f;

        System.out.println("Generating DSP pipeline comparison files → " + dir + "/");

        float[] sine440 = sine(440);
        float[] noise = whiteNoise(42);
        // Three harmonics simulating a typical synth note (fundamental + 2nd + 3rd harmonic)
        float[] chord = multiTone(440, 880, 1320);

        System.out.println("  [sine 440 Hz]");
        save(runDry(sine440), dir + "/sine_dry.raw");
        save(runAmigaOnly(sine440), dir + "/sine_amiga.raw");
        save(runReverbOnly(sine440, preset, level), dir + "/sine_reverb.raw");
        save(runAmigaThenReverb(sine440, preset, level), dir + "/sine_new.raw");
        save(runReverbThenAmiga(sine440, preset, level), dir + "/sine_old.raw");

        System.out.println("  [white noise]");
        save(runDry(noise), dir + "/noise_dry.raw");
        save(runAmigaOnly(noise), dir + "/noise_amiga.raw");
        save(runAmigaThenReverb(noise, preset, level), dir + "/noise_new.raw");
        save(runReverbThenAmiga(noise, preset, level), dir + "/noise_old.raw");

        System.out.println("  [chord: 440+880+1320 Hz — simulates synth harmonic content]");
        save(runDry(chord), dir + "/chord_dry.raw");
        save(runAmigaOnly(chord), dir + "/chord_amiga.raw");
        save(runAmigaThenReverb(chord, preset, level), dir + "/chord_new.raw");

        System.out.println("  [frequency sweep: amiga only, individual notes]");
        for (int freq : new int[] { 100, 200, 500, 1000, 1500, 2000, 3000 })
        {
            save(runDry(sine(freq)), dir + "/sweep_dry_" + freq + ".raw");
            save(runAmigaOnly(sine(freq)), dir + "/sweep_amiga_" + freq + ".raw");
        }

        System.out.println("\n  [CompactMac: sine 440 Hz — THD + output level]");
        save(runCompactMac(sine440), dir + "/sine_compactmac.raw");

        System.out.println("  [CompactMac: frequency sweep]");
        for (int freq : new int[] { 100, 200, 500, 1000, 2000, 3000, 5000, 8000, 10000 })
        {
            save(runDry(sine(freq)), dir + "/sweep_dry_" + freq + ".raw");
            save(runCompactMac(sine(freq)), dir + "/sweep_compactmac_" + freq + ".raw");
        }

        System.out.printf("%nAnalyze with:%n  python3 scripts/analyze_audio.py %s%n", dir);
    }
}
