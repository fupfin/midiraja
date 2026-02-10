package com.fupfin.midiraja.midi.gus;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.fupfin.midiraja.dsp.AudioProcessor;
import java.io.File;

class GusSynthProviderTest {

    static class DummyProcessor implements AudioProcessor {
        int processCount = 0;
        @Override public void process(float[] l, float[] r, int f) {}
        @Override public void processInterleaved(short[] pcm, int f, int c) {
            processCount++;
        }
    }

    @Test
    void testGusSynthProviderLifecycle() throws Exception {
        DummyProcessor dummy = new DummyProcessor();
        File dummyDir = new File(System.getProperty("java.io.tmpdir"));
        
        GusSynthProvider provider = new GusSynthProvider(dummy, dummyDir.getAbsolutePath());
        provider.openPort(0);
        
        Thread.sleep(100);
        
        assertTrue(dummy.processCount > 0, "Render thread should be pushing audio frames");
        
        assertDoesNotThrow(() -> {
            provider.sendMessage(new byte[] { (byte)0x90, 60, 100 });
            provider.sendMessage(new byte[] { (byte)0x80, 60, 0 });
            provider.panic();
        });
        
        provider.closePort();
    }
}
