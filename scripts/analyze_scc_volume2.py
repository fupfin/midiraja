import subprocess
import os

java_code = """
package com.midiraja.midi.psg;

public class PsgVolumeTest {
    public static void main(String[] args) {
        PsgChip psg = new PsgChip(44100, 0, 0);
        SccChip scc = new SccChip(44100, 0, false); // smooth
        SccChip sccRaw = new SccChip(44100, 0, true); // raw
        
        // 1. Test PSG max volume (Square wave)
        psg.updateNote(0, 60, 127);
        double psgMax = 0, psgRms = 0;
        for (int i=0; i<44100; i++) {
            double v = psg.render();
            if (Math.abs(v) > psgMax) psgMax = Math.abs(v);
            psgRms += v*v;
        }
        psgRms = Math.sqrt(psgRms/44100);
        
        // 2. Test SCC Smooth max volume (Square default wave)
        scc.setProgram(0, 80); // Lead (Square)
        scc.updateNote(0, 60, 127);
        double sccMax = 0, sccRms = 0;
        for (int i=0; i<44100; i++) {
            double v = scc.render();
            if (Math.abs(v) > sccMax) sccMax = Math.abs(v);
            sccRms += v*v;
        }
        sccRms = Math.sqrt(sccRms/44100);

        // 3. Test SCC Raw max volume (Square default wave)
        sccRaw.setProgram(0, 80); // Lead (Square)
        sccRaw.updateNote(0, 60, 127);
        double sccRawMax = 0, sccRawRms = 0;
        for (int i=0; i<44100; i++) {
            double v = sccRaw.render();
            if (Math.abs(v) > sccRawMax) sccRawMax = Math.abs(v);
            sccRawRms += v*v;
        }
        sccRawRms = Math.sqrt(sccRawRms/44100);
        
        System.out.printf("PSG        - Max: %.4f, RMS: %.4f%n", psgMax, psgRms);
        System.out.printf("SCC Smooth - Max: %.4f, RMS: %.4f%n", sccMax, sccRms);
        System.out.printf("SCC Raw    - Max: %.4f, RMS: %.4f%n", sccRawMax, sccRawRms);
    }
}
"""

with open("src/test/java/com/midiraja/midi/psg/PsgVolumeTest.java", "w") as f:
    f.write(java_code)

