package com.fupfin.midiraja.tools;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Measures RMS and peak levels (dBFS) of 16-bit stereo WAV files.
 *
 * <p>
 * Usage: ./gradlew measureLoudness -PwavFiles="a.wav,b.wav,..."
 */
public class WavRmsMeter
{
    record Result(String name, double rmsL, double rmsR, double peakL, double peakR)
    {
        double rmsLR()
        {
            return (rmsL + rmsR) / 2.0;
        }

        double peakLR()
        {
            return Math.max(peakL, peakR);
        }

        static double toDb(double linear)
        {
            return linear > 0 ? 20.0 * Math.log10(linear) : -120.0;
        }

        void print()
        {
            System.out.printf("%-30s  RMS: %6.1f dBFS  Peak: %6.1f dBFS  (L: %5.1f / R: %5.1f)%n",
                    name, toDb(rmsLR()), toDb(peakLR()), toDb(rmsL), toDb(rmsR));
        }
    }

    public static Result measure(Path wav) throws IOException
    {
        try (RandomAccessFile f = new RandomAccessFile(wav.toFile(), "r"))
        {
            // Parse RIFF header to find 'data' chunk
            byte[] tag = new byte[4];
            f.read(tag); // "RIFF"
            f.skipBytes(4); // fileSize
            f.read(tag); // "WAVE"

            int channels = 2;
            int bitsPerSample = 16;
            long dataOffset = 44;
            long dataLen = f.length() - 44;

            // Walk chunks to find fmt and data
            f.seek(12);
            while (f.getFilePointer() < f.length() - 8)
            {
                byte[] id = new byte[4];
                f.read(id);
                byte[] szBytes = new byte[4];
                f.read(szBytes);
                int chunkSize = ByteBuffer.wrap(szBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
                long next = f.getFilePointer() + chunkSize;

                String chunkId = new String(id, StandardCharsets.US_ASCII);
                if ("fmt ".equals(chunkId) && chunkSize >= 16)
                {
                    f.skipBytes(2); // audioFormat
                    byte[] chBytes = new byte[2];
                    f.read(chBytes);
                    channels = ByteBuffer.wrap(chBytes).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
                    f.skipBytes(10); // sampleRate(4) + byteRate(4) + blockAlign(2)
                    byte[] bpsBytes = new byte[2];
                    f.read(bpsBytes);
                    bitsPerSample = ByteBuffer.wrap(bpsBytes).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
                }
                else if ("data".equals(chunkId))
                {
                    dataOffset = f.getFilePointer();
                    // If size is 0 the writer was killed before close() patched the header —
                    // fall back to reading whatever bytes are actually in the file.
                    dataLen = chunkSize > 0 ? chunkSize : (f.length() - dataOffset);
                    break;
                }
                f.seek(next);
            }

            if (bitsPerSample != 16)
            {
                throw new IOException("Only 16-bit PCM supported, got " + bitsPerSample);
            }

            f.seek(dataOffset);
            int totalSamples = (int) (dataLen / 2);
            int frames = totalSamples / channels;
            byte[] buf = new byte[(int) dataLen];
            f.readFully(buf);

            double sumSqL = 0, sumSqR = 0;
            double peakL = 0, peakR = 0;

            for (int i = 0; i < frames; i++)
            {
                int off = i * channels * 2;
                short rawL = (short) ((buf[off] & 0xFF) | (buf[off + 1] << 8));
                double l = rawL / 32768.0;
                sumSqL += l * l;
                peakL = Math.max(peakL, Math.abs(l));

                if (channels >= 2)
                {
                    short rawR = (short) ((buf[off + 2] & 0xFF) | (buf[off + 3] << 8));
                    double r = rawR / 32768.0;
                    sumSqR += r * r;
                    peakR = Math.max(peakR, Math.abs(r));
                }
                else
                {
                    sumSqR = sumSqL;
                    peakR = peakL;
                }
            }

            double rmsL = Math.sqrt(sumSqL / frames);
            double rmsR = Math.sqrt(sumSqR / frames);
            return new Result(wav.getFileName().toString(), rmsL, rmsR, peakL, peakR);
        }
    }

    public static void main(String[] args) throws IOException
    {
        if (args.length == 0)
        {
            System.out.println("Usage: provide WAV file paths as arguments");
            return;
        }

        System.out.println();
        System.out.printf("%-30s  %-22s  %-22s  %s%n", "File", "RMS", "Peak", "L / R RMS");
        System.out.println("-".repeat(90));

        double minRms = Double.MAX_VALUE;
        Result minResult = null;

        for (String arg : args)
        {
            Result r = measure(Path.of(arg));
            r.print();
            double db = Result.toDb(r.rmsLR());
            if (db > -120 && db < minRms)
            {
                minRms = db;
                minResult = r;
            }
        }

        System.out.println();
        System.out.println("Suggested calibration relative to quietest engine:");
        System.out.println("-".repeat(90));
        for (String arg : args)
        {
            Result r = measure(Path.of(arg));
            double dbDiff = Result.toDb(r.rmsLR()) - minRms;
            double linearFactor = Math.pow(10, -dbDiff / 20.0);
            String mark = (minResult != null && r.name().equals(minResult.name())) ? " ← reference" : "";
            System.out.printf("%-30s  %+6.1f dB  →  calibrationGain *= %.4f%s%n",
                    r.name(), dbDiff, linearFactor, mark);
        }
        System.out.println();
    }
}
