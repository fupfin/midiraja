package com.fupfin.midiraja.midi.gus;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.dsp.AudioProcessor;

class GusSynthProviderTest
{

    static class DummyProcessor implements AudioProcessor
    {
        int processCount = 0;

        @Override
        public void process(float[] l, float[] r, int f)
        {
        }

        @Override
        public void processInterleaved(short[] pcm, int f, int c)
        {
            processCount++;
        }
    }

    private static void exerciseProvider(GusSynthProvider provider)
    {
        provider.sendMessage(new byte[] { (byte) 0x90, 60, 100 });
        provider.sendMessage(new byte[] { (byte) 0x80, 60, 0 });
        provider.panic();
    }

    @Test
    void testGusSynthProviderLifecycle() throws Exception
    {
        DummyProcessor dummy = new DummyProcessor();
        File dummyDir = new File(System.getProperty("java.io.tmpdir"));

        GusSynthProvider provider = new GusSynthProvider(dummy, dummyDir.getAbsolutePath());
        provider.openPort(0);

        Thread.sleep(100);

        assertTrue(dummy.processCount > 0, "Render thread should be pushing audio frames");

        assertDoesNotThrow(() -> exerciseProvider(provider));

        provider.closePort();
    }
}
