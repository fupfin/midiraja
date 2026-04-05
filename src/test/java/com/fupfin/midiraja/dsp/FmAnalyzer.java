
package com.fupfin.midiraja.dsp;

import java.io.FileOutputStream;

import com.fupfin.midiraja.midi.NativeAudioEngine;
import com.fupfin.midiraja.midi.beep.BeepSynthProvider;

public class FmAnalyzer
{
    public static void main(String[] args) throws Exception
    {
        // Create a dummy library file to satisfy System.load temporarily
        java.io.File dummyLib = java.io.File.createTempFile("dummy", ".dylib");

        NativeAudioEngine engine = new NativeAudioEngine(dummyLib.getAbsolutePath())
        {
            FileOutputStream fos = new FileOutputStream("fm_test.raw");
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
                    for (int i = 0; i < length; i++)
                    {
                        short s = pcm[offset + i];
                        fos.write(s & 0xFF);
                        fos.write((s >> 8) & 0xFF);
                    }
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

        // Single Voice, XOR Synth, DSD Mux
        BeepSynthProvider provider = new BeepSynthProvider(new com.fupfin.midiraja.dsp.FloatToShortSink(engine, 1), 1,
                1.0, 1.1, 32, "dsd", "xor");
        provider.openPort(0);

        byte[] noteOn = new byte[] { (byte) 0x90, 60, 100 };
        provider.sendMessage(noteOn);

        Thread.sleep(1000);
        System.exit(0);
    }
}
