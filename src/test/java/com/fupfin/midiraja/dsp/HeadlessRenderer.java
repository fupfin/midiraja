package com.fupfin.midiraja.dsp;

import java.io.FileOutputStream;

import com.fupfin.midiraja.midi.NativeAudioEngine;
import com.fupfin.midiraja.midi.beep.BeepSynthProvider;

public class HeadlessRenderer
{
    public static void main(String[] args) throws Exception
    {
        if (args.length < 5)
        {
            System.out.println("Usage: HeadlessRenderer <mux> <synth> <voices> <ditherLevel> <lpfCutoff>");
            System.exit(1);
        }

        String mux = args[0];
        String synth = args[1];
        int voices = Integer.parseInt(args[2]);
        double ditherLevel = Double.parseDouble(args[3]);
        double lpfCutoff = Double.parseDouble(args[4]);

        // Pass these parameters via system properties to the engine
        // (We will temporarily patch BeepSynthProvider to read these properties for tuning)
        System.setProperty("midiraja.tune.dither", String.valueOf(ditherLevel));
        System.setProperty("midiraja.tune.lpf", String.valueOf(lpfCutoff));

        java.io.File dummyLib = java.io.File.createTempFile("dummy", ".dylib");

        NativeAudioEngine engine = new NativeAudioEngine(dummyLib.getAbsolutePath())
        {
            FileOutputStream fos = new FileOutputStream("render_dump.raw");
            int framesWritten = 0;
            @Override
            public void init(int rate, int ch, int buf)
            {
            }

            @Override
            public int push(short[] pcm)
            {
                return push(pcm, 0, pcm.length);
            }

            @Override
            public int push(short[] pcm, int offset, int length)
            {
                try
                {
                    byte[] buf = new byte[length * 2];
                    for (int i = 0; i < length; i++)
                    {
                        short s = pcm[offset + i];
                        buf[i * 2] = (byte) (s & 0xFF);
                        buf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                    }
                    fos.write(buf);
                    framesWritten += length;
                    return length;
                }
                catch (Exception e)
                {
                    return -1;
                }
            }

            @Override
            public void close()
            {
            }
        };

        BeepSynthProvider provider = new BeepSynthProvider(new FloatToShortSink(engine, 1), voices, 1.0, 1.1, 32, mux,
                synth);
        provider.openPort(0);

        // Play a solid C4 note
        byte[] noteOn = new byte[] { (byte) 0x90, 60, 100 };
        provider.sendMessage(noteOn);

        // Sleep until the audio thread finishes rendering 1 second
        Thread.sleep(2000);
        System.exit(0);
    }
}
