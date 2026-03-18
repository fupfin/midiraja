package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fupfin.midiraja.midi.AbstractFFMBridge;
import java.util.List;
import org.junit.jupiter.api.Test;

class InfoCommandTest {

    @Test
    void versionAndRuntimeLinesPresent() {
        String report = InfoCommand.buildReport("1.2.3", "abc1234", false,
                "Mac OS X", "aarch64", "25.0.1",
                null, null, null,
                List.of(), List.of(), null, null, new String[0]);

        assertTrue(report.contains("1.2.3"));
        assertTrue(report.contains("abc1234"));
        assertTrue(report.contains("Mac OS X"));
        assertTrue(report.contains("aarch64"));
        assertTrue(report.contains("25.0.1"));
        assertTrue(report.contains("JVM"));
    }

    @Test
    void nativeImageRuntime() {
        String report = InfoCommand.buildReport("1.0.0", "a", true,
                "Linux", "x86_64", "21",
                null, null, null,
                List.of(), List.of(), null, null, new String[0]);

        assertTrue(report.contains("native-image"));
    }

    @Test
    void environmentShowsValuesAndNotSet() {
        String report = InfoCommand.buildReport("1.0.0", "a", false,
                "Linux", "x86_64", "21",
                "/usr/lib/jvm/java-21", null, "/usr/lib",
                List.of(), List.of(), null, null, new String[0]);

        assertTrue(report.contains("/usr/lib/jvm/java-21"));
        assertTrue(report.contains("(not set)"));
        assertTrue(report.contains("/usr/lib"));
    }

    @Test
    void libraryFoundAndNotFound() {
        var found = new AbstractFFMBridge.LibProbeResult(true, "/opt/homebrew/lib/libmt32emu.dylib");
        var notFound = new AbstractFFMBridge.LibProbeResult(false, null);
        var libs = List.of(
                new InfoCommand.LibInfo("libmt32emu", found),
                new InfoCommand.LibInfo("libADLMIDI", notFound));

        String report = InfoCommand.buildReport("1.0.0", "a", false,
                "Mac OS X", "aarch64", "21",
                null, null, null,
                libs, List.of(), null, null, new String[0]);

        assertTrue(report.contains("\u2713"));
        assertTrue(report.contains("\u2717"));
        assertTrue(report.contains("/opt/homebrew/lib/libmt32emu.dylib"));
        assertTrue(report.contains("not found"));
    }

    @Test
    void midiPortsRendered() {
        var ports = List.of("[0] IAC Driver", "[1] External Synth");

        String report = InfoCommand.buildReport("1.0.0", "a", false,
                "Mac OS X", "aarch64", "21",
                null, null, null,
                List.of(), ports, null, null, new String[0]);

        assertTrue(report.contains("[0] IAC Driver"));
        assertTrue(report.contains("[1] External Synth"));
    }

    @Test
    void soundFontFoundAndNotFound() {
        String sf3 = "/home/user/.local/share/midra/soundfonts/FluidR3_GM.sf3";

        String withSf = InfoCommand.buildReport("1.0.0", "a", false,
                "Linux", "x86_64", "21",
                null, null, null,
                List.of(), List.of(), sf3, null, new String[0]);
        assertTrue(withSf.contains(sf3));

        String withoutSf = InfoCommand.buildReport("1.0.0", "a", false,
                "Linux", "x86_64", "21",
                null, null, null,
                List.of(), List.of(), null, null, new String[0]);
        assertTrue(withoutSf.contains("not found"));
    }

    @Test
    void gusPatchesFoundEmbeddedAndNotFound() {
        String report = InfoCommand.buildReport("1.0.0", "a", false,
                "Linux", "x86_64", "21",
                null, null, null,
                List.of(), List.of(), null, "/usr/share/midra/eawpats", new String[0]);
        assertTrue(report.contains("/usr/share/midra/eawpats"));

        String embedded = InfoCommand.buildReport("1.0.0", "a", false,
                "Linux", "x86_64", "21",
                null, null, null,
                List.of(), List.of(), null, "(embedded FreePats)", new String[0]);
        assertTrue(embedded.contains("(embedded FreePats)"));

        String notFound = InfoCommand.buildReport("1.0.0", "a", false,
                "Linux", "x86_64", "21",
                null, null, null,
                List.of(), List.of(), null, null, new String[0]);
        assertTrue(notFound.contains("GUS Patches"));
        assertTrue(notFound.contains("not found"));
    }

    @Test
    void librarySearchPathsPresent() {
        String report = InfoCommand.buildReport("1.0.0", "a", false,
                "Mac OS X", "aarch64", "21",
                null, null, null,
                List.of(), List.of(), null, null,
                new String[]{"/opt/homebrew/lib", "/usr/local/lib"});

        assertTrue(report.contains("/opt/homebrew/lib"));
        assertTrue(report.contains("/usr/local/lib"));
    }
}
